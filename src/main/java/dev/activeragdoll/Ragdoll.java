package dev.activeragdoll;

import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.RaycastContext;
import org.joml.Vector3d;

/**
 * Classic-Minecraft style active ragdoll: every limb is ONE rigid stick (no knees/elbows).
 * Position-based-dynamics particles driven by soft "muscle" targets plus a procedural gait controller:
 * alternating foot placement, stance/swing timing, pelvis sway, hip/shoulder counter-rotation,
 * acceleration lean, arm counter-swing, idle shuffles, landing impacts.
 * The pelvis height is solved so the stiff planted legs exactly reach the ground (inverted pendulum).
 */
public final class Ragdoll {
	public static final double PX = 0.9375 / 16.0; // one model pixel in blocks
	public static final double LEG = 12 * PX, TORSO = 12 * PX, HEAD = 8 * PX, ARM = 10 * PX; // ARM = shoulder pivot -> hand
	static final double HIP = 2 * PX, SHOULDER = 6 * PX, SHOULDER_DROP = 2 * PX;
	static final double GRAVITY = 26.0;

	public static final int PELVIS = 0, CHEST = 1, HEADTOP = 2, FOOT_R = 3, FOOT_L = 4, HAND_R = 5, HAND_L = 6, N = 7;

	public final Vector3d[] x = vecs(N);
	private final Vector3d[] old = vecs(N), v = vecs(N);
	private final boolean[] contact = new boolean[N];

	private final Vector3d P = new Vector3d(), lastP = new Vector3d(), vel = new Vector3d(), prevVel = new Vector3d();
	private final Vector3d fwd = new Vector3d(), rgt = new Vector3d(), tUp = new Vector3d(), tRgtH = new Vector3d(), tRgtS = new Vector3d();
	private final Vector3d[] plant = vecs(2), swingFrom = vecs(2), swingTo = vecs(2), footTarget = vecs(2),
		desired = vecs(2), hipOff = vecs(2), shoulderOff = vecs(2);
	private final double[] footK = new double[2], swingT = new double[2], swingDur = {0.2, 0.2}, stanceT = new double[2];
	private final boolean[] swinging = new boolean[2];
	private final double[] hike = new double[2], swingBaseY = new double[2];
	private final Vector3d a = new Vector3d(), b = new Vector3d(), c = new Vector3d();

	private boolean init, ground, wasGround, sprinting;
	private long lastNanos;
	private double limp, muscle = 1.0, time, accF, airVy, swayAmt, idleT;
	private int lastHurt, lastSwing = -1;
	public double bodyYawDeg;
	/** Hip / shoulder counter-rotation in radians (+ = rotate the right side forward). Read by the renderer. */
	public double hipTwist, shoulderTwist;

	static Vector3d[] vecs(int n) {
		Vector3d[] arr = new Vector3d[n];
		for (int i = 0; i < n; i++) arr[i] = new Vector3d();
		return arr;
	}

	static double sgn(int side) { return side == 0 ? 1.0 : -1.0; } // 0 = right (+rgt), 1 = left
	static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }

	public void hipPos(int s, Vector3d out) { out.set(x[PELVIS]).add(hipOff[s]); }
	public void shoulderPos(int s, Vector3d out) { out.set(x[CHEST]).add(shoulderOff[s]); }

	// ------------------------------------------------------------------ update

	public void update(PlayerEntity p, ClientWorld w, float td, float bodyYawDeg, boolean forceLimp) {
		long now = System.nanoTime();
		double dt = init ? Math.min((now - lastNanos) * 1e-9, 0.05) : 0.0;
		lastNanos = now;

		this.bodyYawDeg = bodyYawDeg;
		Vec3d lp = p.getLerpedPos(td);
		P.set(lp.x, lp.y, lp.z);
		double yaw = Math.toRadians(bodyYawDeg);
		double sy = Math.sin(yaw), cy = Math.cos(yaw);
		fwd.set(-sy, 0, cy);
		rgt.set(-cy, 0, -sy);

		if (!init || x[PELVIS].distanceSquared(P) > 36.0) reset();

		if (dt <= 1e-4) { frame(); return; }

		double va = 1.0 - Math.exp(-dt / 0.06); vel.x += (clamp((P.x - lastP.x) / dt, -25, 25) - vel.x) * va;
		vel.y += (clamp((P.y - lastP.y) / dt, -25, 25) - vel.y) * va;
		vel.z += (clamp((P.z - lastP.z) / dt, -25, 25) - vel.z) * va;
		lastP.set(P);
		double ax = (vel.x - prevVel.x) / dt, az = (vel.z - prevVel.z) / dt;
		accF += (clamp(ax * fwd.x + az * fwd.z, -40, 40) - accF) * (1.0 - Math.exp(-dt / 0.15));
		prevVel.set(vel);
		sprinting = p.isSprinting();

		boolean dead = p.isDead() || p.getHealth() <= 0;
		if (p.hurtTime > lastHurt) {
			limp = Math.max(limp, 0.85);
			v[PELVIS].add((Math.random() - 0.5) * 3, 2.5, (Math.random() - 0.5) * 3);
			v[CHEST].add((Math.random() - 0.5) * 4, 1.5, (Math.random() - 0.5) * 4);
		}
		lastHurt = p.hurtTime;
		if (dead || forceLimp) limp = 1.0; else limp = Math.max(0.0, limp - dt / 0.7);
		muscle = 1.0 - limp;

		ground = p.isOnGround() && !p.isSwimming() && !p.isFallFlying() && !p.isClimbing()
			&& !p.hasVehicle() && !p.isSleeping() && muscle > 0.15;

		// landing impact: torso folds forward, arms fly up, brief loss of muscle tone
		if (!ground) {
			airVy = Math.min(airVy, vel.y);
		} else {
			if (!wasGround && airVy < -3.5) {
				double imp = clamp(-airVy * 0.025, 0.0, 0.35);
				limp = Math.max(limp, imp);
				muscle = 1.0 - limp;
				v[CHEST].y -= imp * 5.0;
				v[CHEST].fma(imp * 2.0, fwd);
				v[HEADTOP].y -= imp * 4.0;
				v[HAND_R].y += imp * 5.0;
				v[HAND_L].y += imp * 5.0;
			}
			airVy = 0;
		}
		wasGround = ground;

		int n = Math.max(1, Math.min(4, (int) Math.ceil(dt / 0.0167)));
		double h = dt / n;
		for (int i = 0; i < n; i++) sim(p, w, h);
		frame();
	}

	private void reset() {
		frame();
		x[PELVIS].set(P.x, P.y + LEG, P.z);
		x[CHEST].set(x[PELVIS]).add(0, TORSO, 0);
		x[HEADTOP].set(x[CHEST]).add(0, HEAD, 0);
		for (int s = 0; s < 2; s++) {
			double sg = sgn(s);
			a.set(P).fma(sg * HIP, rgt);
			plant[s].set(a);
			x[FOOT_R + s].set(a);
			b.set(x[CHEST]).add(0, -SHOULDER_DROP, 0).fma(sg * SHOULDER, rgt);
			x[HAND_R + s].set(b).add(0, -ARM, 0);
			swinging[s] = false;
			stanceT[s] = 0;
		}
		for (int i = 0; i < N; i++) { old[i].set(x[i]); v[i].zero(); }
		lastP.set(P);
		vel.zero();
		prevVel.zero();
		hipTwist = shoulderTwist = swayAmt = accF = airVy = idleT = 0;
		wasGround = true;
		init = true;
	}

	/** Right vector rotated toward forward by ang, flattened against the torso axis. */
	private void twistedRight(Vector3d out, double ang) {
		out.set(rgt).mul(Math.cos(ang)).fma(Math.sin(ang), fwd);
		out.fma(-out.dot(tUp), tUp);
		if (out.lengthSquared() < 1e-6) out.set(rgt);
		out.normalize();
	}

	private void frame() {
		tUp.set(x[CHEST]).sub(x[PELVIS]);
		if (tUp.lengthSquared() < 1e-8) tUp.set(0, 1, 0);
		tUp.normalize();
		twistedRight(tRgtH, hipTwist);
		twistedRight(tRgtS, shoulderTwist);
		for (int s = 0; s < 2; s++) {
			double sg = sgn(s);
			hipOff[s].set(tRgtH).mul(sg * HIP); hipOff[s].y += hike[s];
			shoulderOff[s].set(tUp).mul(-SHOULDER_DROP).fma(sg * SHOULDER, tRgtS);
		}
	}

	// ------------------------------------------------------------------ simulation step

	private void sim(PlayerEntity p, ClientWorld w, double h) {
		time += h;
		double speed = Math.hypot(vel.x, vel.z);
		boolean sneak = p.isInSneakingPose();
		frame();
		footControl(p, w, h, speed);

		double damp = Math.pow(0.99 - 0.08 * muscle, h * 60.0);
		for (int i = 0; i < N; i++) {
			old[i].set(x[i]);
			v[i].mul(damp);
			v[i].y -= GRAVITY * h;
			x[i].fma(h, v[i]);
			contact[i] = false;
		}

		if (muscle > 0.01) muscles(speed, sneak, h);
		for (int it = 0; it < 5; it++) constraints();

		collide(w, PELVIS, 0.12);
		collide(w, CHEST, 0.14);
		collide(w, HEADTOP, 0.12);
		for (int s = 0; s < 2; s++) {
			collide(w, FOOT_R + s, 0.02);
			collide(w, HAND_R + s, 0.05);
		}

		double fr = Math.pow(0.6, h * 60.0);
		for (int i = 0; i < N; i++) {
			v[i].set(x[i]).sub(old[i]).div(h);
			double len = v[i].length();
			if (len > 30.0) v[i].mul(30.0 / len);
			if (contact[i]) { v[i].x *= fr; v[i].z *= fr; }
		}
		if (!Double.isFinite(x[PELVIS].x + x[PELVIS].y + x[PELVIS].z + x[CHEST].y + x[HEADTOP].y)) init = false;
	}

	// ------------------------------------------------------------------ gait controller (foot placement)

	private double groundY(ClientWorld w, PlayerEntity p, double px, double top, double pz) {
		BlockHitResult hr = w.raycast(new RaycastContext(new Vec3d(px, top, pz), new Vec3d(px, top - 2.5, pz),
			RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, p));
		return hr.getType() == HitResult.Type.BLOCK ? hr.getPos().y : Double.NaN;
	}

	private void startSwing(int s, double dur) {
		swinging[s] = true;
		swingT[s] = 0;
		swingDur[s] = dur;
		swingFrom[s].set(plant[s]);
		swingTo[s].set(desired[s]);
		lastSwing = s;
	}

	private void footControl(PlayerEntity p, ClientWorld w, double h, double speed) {
		for (int s = 0; s < 2; s++) {
			desired[s].set(P).fma(sgn(s) * HIP * 1.25, rgt);
			desired[s].x += clamp(vel.x * 0.15, -0.35, 0.35);
			desired[s].z += clamp(vel.z * 0.15, -0.35, 0.35);
			double g = groundY(w, p, desired[s].x, P.y + 0.6, desired[s].z);
			desired[s].y = Double.isNaN(g) ? P.y : g;
		}

		if (!ground) {
			// airborne: legs scissor (last stepping leg stays forward), trailing against velocity
			double scis = (lastSwing == 0 ? 1.0 : -1.0) * 0.22 * Math.min(1.0, speed / 3.0);
			for (int s = 0; s < 2; s++) {
				swinging[s] = false;
				stanceT[s] = 0;
				plant[s].set(x[FOOT_R + s]);
				hipPos(s, a);
				footTarget[s].set(a).add(0, -LEG, 0).fma(-0.05, vel).fma(sgn(s) * 0.08, rgt).fma(sgn(s) * scis, fwd);
				footK[s] = 10.0 * muscle;
			}
			return;
		}

		for (int s = 0; s < 2; s++) if (!swinging[s]) stanceT[s] += h;

		int swingCount = (swinging[0] ? 1 : 0) + (swinging[1] ? 1 : 0);
		int maxSwing = speed > 4.6 ? 2 : 1;
		double thresh = 0.28 + 0.06 * Math.min(speed, 4.0);
		int best = -1;
		double bd = thresh;
		for (int s = 0; s < 2; s++) {
			if (swinging[s]) continue;
			boolean overreach = Math.hypot(plant[s].x - P.x, plant[s].z - P.z) > LEG * 0.8;
			double d = Math.hypot(plant[s].x - desired[s].x, plant[s].z - desired[s].z)
				+ 0.8 * Math.abs(plant[s].y - desired[s].y);
			// alternate feet: prefer the one that did not just step, and the one planted longest
			double eff = d + (s != lastSwing ? 0.04 : 0.0) + 0.05 * Math.min(stanceT[s], 0.5);
			if (overreach) { best = s; bd = 1e9; }
			else if (swingCount < maxSwing && d > thresh && eff > bd && stanceT[s] > 0.06) { bd = eff; best = s; }
		}
		double dur = clamp(0.46 - 0.06 * speed, 0.16, 0.4);
		if (best >= 0) {
			startSwing(best, dur);
			idleT = 0;
		} else if (speed < 0.3 && swingCount == 0) {
			// standing: after a pause, shuffle the most displaced foot back to a neutral stance
			idleT += h;
			if (idleT > 0.8) {
				int s0 = -1;
				double md = 0.1;
				for (int s = 0; s < 2; s++) {
					double d = Math.hypot(plant[s].x - desired[s].x, plant[s].z - desired[s].z);
					if (d > md) { md = d; s0 = s; }
				}
				if (s0 >= 0) startSwing(s0, 0.3);
				idleT = 0;
			}
		} else if (speed >= 0.3) {
			idleT = 0;
		}

		for (int s = 0; s < 2; s++) {
			if (swinging[s]) {
				swingT[s] += h / swingDur[s];
				double rem = Math.max(0.0, 1.0 - swingT[s]) * swingDur[s];
				swingTo[s].set(desired[s]).add(vel.x * rem * 0.6, 0, vel.z * rem * 0.6);
				double g = groundY(w, p, swingTo[s].x, P.y + 0.6, swingTo[s].z);
				swingTo[s].y = Double.isNaN(g) ? P.y : g;

				double t = Math.min(1.0, swingT[s]);
				double e = t * t * t * (10 - 15 * t + 6 * t * t);
				footTarget[s].set(swingFrom[s]).lerp(swingTo[s], e); swingBaseY[s] = footTarget[s].y;
				double stride = Math.hypot(swingTo[s].x - swingFrom[s].x, swingTo[s].z - swingFrom[s].z);
				// lift peaks early in the swing, higher when running, extra clearance for steps up
				double lift = 0.045 + 0.16 * Math.min(stride, 0.7) + (speed > 4.6 ? 0.06 : 0.0);
				footTarget[s].y += Math.sin(Math.PI * Math.pow(t, 0.7)) * lift;
				footTarget[s].y += Math.max(0.0, swingTo[s].y - swingFrom[s].y) * Math.sin(Math.PI * Math.min(1.0, t * 1.3));
				footK[s] = 90.0 * muscle;
				if (swingT[s] >= 1.0) { swinging[s] = false; plant[s].set(swingTo[s]); stanceT[s] = 0; }
			} else {
				double g = groundY(w, p, plant[s].x, P.y + 0.6, plant[s].z);
				if (!Double.isNaN(g)) plant[s].y += (g - plant[s].y) * Math.min(1.0, h * 25.0);
				footTarget[s].set(plant[s]);
				footK[s] = 70.0 * muscle;
			}
		}
	}

	// ------------------------------------------------------------------ muscles (soft targets)

	private void pull(int i, double tx, double ty, double tz, double kxz, double ky, double h) {
		double fxz = 1.0 - Math.exp(-kxz * h), fy = 1.0 - Math.exp(-ky * h);
		x[i].x += (tx - x[i].x) * fxz;
		x[i].y += (ty - x[i].y) * fy;
		x[i].z += (tz - x[i].z) * fxz;
	}

	private void pull(int i, Vector3d t, double k, double h) { pull(i, t.x, t.y, t.z, k, k, h); }

	/** Sets c to origin + dir * len (dir normalised). */
	private Vector3d reach(Vector3d origin, Vector3d target, double len) {
		c.set(target).sub(origin);
		if (c.lengthSquared() < 1e-8) c.set(0, -1, 0);
		c.normalize().mul(len).add(origin);
		return c;
	}

	/** Foot position for a swinging leg: horizontally at the path point, vertically on the leg-length sphere under the hip. */
	private Vector3d swingTip(Vector3d hip, Vector3d tgt, double baseY) {
		double dx = tgt.x - hip.x, dz = tgt.z - hip.z, d2 = dx * dx + dz * dz, l2 = LEG * LEG;
		if (d2 > l2 * 0.98) {
			double k = Math.sqrt(l2 * 0.98 / d2);
			dx *= k;
			dz *= k;
			d2 = l2 * 0.98;
		}
		double sy = hip.y - Math.sqrt(l2 - d2);
		c.set(hip.x + dx, Math.max(sy, baseY), hip.z + dz);
		return c;
	}

	private Vector3d reach2(Vector3d origin, Vector3d target) {
		Vector3d d = new Vector3d(target).sub(origin);
		if (d.lengthSquared() < 1e-8) d.set(0, -1, 0);
		return d.normalize().mul(ARM).add(origin);
	}

	private void muscles(double speed, boolean sneak, double h) {
		double m = muscle;
		boolean idle = speed < 0.5;

		// --- hip / shoulder counter-rotation (right foot forward -> right hip forward, left shoulder forward)
		double tw = 0, ts = 0;
		if (ground) {
			double fR = (footTarget[0].x - P.x) * fwd.x + (footTarget[0].z - P.z) * fwd.z;
			double fL = (footTarget[1].x - P.x) * fwd.x + (footTarget[1].z - P.z) * fwd.z;
			tw = clamp((fR - fL) * 0.35, -0.2, 0.2) * Math.min(1.0, 0.3 + speed * 0.4);
			ts = -tw * 1.2;
		}
		double kt = 1.0 - Math.exp(-16.0 * h);
		hipTwist += (tw - hipTwist) * kt;
		shoulderTwist += (ts - shoulderTwist) * kt;
		double hikeH = 0.07 + 0.015 * Math.min(speed, 6.0);
		double kh = 1.0 - Math.exp(-30.0 * h);
		for (int s = 0; s < 2; s++) {
			double ht = swinging[s] ? hikeH * Math.sin(Math.PI * Math.pow(Math.min(1.0, swingT[s]), 0.7)) : 0.0;
			hike[s] += (ht - hike[s]) * kh;
		}

		// --- pelvis follows the real player, shifts weight over the stance foot
		double swayT = 0;
		for (int s = 0; s < 2; s++) {
			if (swinging[s]) swayT += Math.sin(Math.PI * Math.min(1.0, swingT[s])) * (s == 0 ? -1.0 : 1.0);
		}
		swayT = swayT * 0.03 + (idle ? 0.006 * Math.sin(time * 0.9) : 0.0);
		swayAmt += (swayT - swayAmt) * (1.0 - Math.exp(-10.0 * h));

		double kxz = 40.0 * m;
		double tx = P.x + vel.x / Math.max(kxz, 1.0) + rgt.x * swayAmt;
		double tz = P.z + vel.z / Math.max(kxz, 1.0) + rgt.z * swayAmt;
		double ty = P.y + LEG * (ground ? 0.999 : 0.95);
		if (ground) {
			for (int s = 0; s < 2; s++) {
				if (swinging[s]) continue;
				double dx = tx + hipOff[s].x - plant[s].x, dz = tz + hipOff[s].z - plant[s].z;
				double reachY = Math.sqrt(Math.max(1e-4, LEG * LEG - dx * dx - dz * dz));
				ty = Math.min(ty, plant[s].y + reachY - hipOff[s].y);
			}
		}
		pull(PELVIS, tx, ty, tz, kxz, 60.0 * m, h);

		// --- torso: mostly upright, leans into speed/acceleration, sways over the stance foot, breathes
		double lean = clamp(speed * 0.03, 0.0, 0.18) + (sprinting ? 0.08 : 0.0)
			+ clamp(accF * 0.012, -0.08, 0.12) + (sneak ? 0.3 : 0.0);
		c.set(0, 1, 0).fma(lean, fwd).fma(swayAmt * 1.2, rgt).normalize();
		a.set(x[PELVIS]).fma(TORSO, c);
		if (idle) a.y += 0.003 * Math.sin(time * 1.7);
		pull(CHEST, a, 30.0 * m, h);

		// --- head: partly stabilised (stays more upright than the torso)
		c.set(x[CHEST]).sub(x[PELVIS]);
		if (c.lengthSquared() > 1e-8) c.normalize(); else c.set(0, 1, 0);
		c.mul(0.5).add(0, 0.5, 0).normalize();
		a.set(x[CHEST]).fma(HEAD, c);
		pull(HEADTOP, a, 24.0 * m, h);

		for (int s = 0; s < 2; s++) {
			double sg = sgn(s);

			// --- leg: one rigid stick. Swinging/airborne feet are projected onto the leg-length sphere.
			hipPos(s, a);
			if (ground && !swinging[s]) {
				pull(FOOT_R + s, footTarget[s], footK[s], h);
			} else {
				pull(FOOT_R + s, ground ? swingTip(a, footTarget[s], swingBaseY[s]) : reach(a, footTarget[s], LEG), footK[s], h);
			}

			// --- arm: one rigid stick, counter-swings against the opposite leg, loose and slightly lagging
			shoulderPos(s, a);
			if (ground) {
				double armFwd = (footTarget[1 - s].x - P.x) * fwd.x + (footTarget[1 - s].z - P.z) * fwd.z;
				double amp = Math.min(1.0, 0.4 + 0.08 * speed);
				double ang = clamp(armFwd * (1.2 + 0.15 * Math.min(speed, 6.0)), -amp, amp);
				if (idle) ang += 0.04 * Math.sin(time * 1.6 + s * 0.7);
				b.set(a).add(0, -Math.cos(ang) * ARM, 0).fma(Math.sin(ang) * ARM, fwd).fma(sg * 0.07, rgt);
				b.set(reach2(a, b));
			} else {
				double up = vel.y > 0 ? 0.15 : -0.3;
				b.set(a).fma(sg * 0.8, rgt).add(0, up + 0.15 * Math.sin(time * 9.0 + s * Math.PI), 0);
				b.set(reach2(a, b));
			}
			pull(HAND_R + s, b, 20.0 * m, h);
		}
	}

	// ------------------------------------------------------------------ constraints & collision

	private void link(int i, int j, double len, double wi, double wj) {
		a.set(x[j]).sub(x[i]);
		double d = a.length();
		if (d < 1e-9) return;
		double cc = (d - len) / d, tot = wi + wj;
		x[i].fma(cc * wi / tot, a);
		x[j].fma(-cc * wj / tot, a);
	}

	/** Anchor point is x[i] + off (a hip/shoulder rigidly attached to its parent particle). */
	private void linkOff(int i, Vector3d off, int j, double len, double wi, double wj) {
		a.set(x[j]).sub(x[i]).sub(off);
		double d = a.length();
		if (d < 1e-9) return;
		double cc = (d - len) / d, tot = wi + wj;
		x[i].fma(cc * wi / tot, a);
		x[j].fma(-cc * wj / tot, a);
	}

	private void constraints() {
		frame();
		link(PELVIS, CHEST, TORSO, 1.0, 1.0);
		link(CHEST, HEADTOP, HEAD, 0.5, 1.0);
		for (int s = 0; s < 2; s++) {
			double wf = (ground && !swinging[s] && muscle > 0.2) ? 0.1 : 1.0;
			linkOff(PELVIS, hipOff[s], FOOT_R + s, LEG, 0.35, wf);
			linkOff(CHEST, shoulderOff[s], HAND_R + s, ARM, 0.12, 1.0);
		}
	}

	private void collide(ClientWorld w, int i, double r) {
		Vector3d p = x[i];
		Box bx = new Box(p.x - r, p.y - r, p.z - r, p.x + r, p.y + r, p.z + r);
		for (VoxelShape vs : w.getBlockCollisions(null, bx)) {
			for (Box cb : vs.getBoundingBoxes()) {
				double ox1 = cb.maxX - (p.x - r), ox2 = (p.x + r) - cb.minX;
				double oy1 = cb.maxY - (p.y - r), oy2 = (p.y + r) - cb.minY;
				double oz1 = cb.maxZ - (p.z - r), oz2 = (p.z + r) - cb.minZ;
				if (ox1 <= 0 || ox2 <= 0 || oy1 <= 0 || oy2 <= 0 || oz1 <= 0 || oz2 <= 0) continue;
				double mx = Math.min(ox1, ox2), my = Math.min(oy1, oy2) * 0.7, mz = Math.min(oz1, oz2);
				if (my <= mx && my <= mz) {
					p.y += oy1 < oy2 ? oy1 : -oy2;
					contact[i] = true;
				} else if (mx <= mz) {
					p.x += ox1 < ox2 ? ox1 : -ox2;
				} else {
					p.z += oz1 < oz2 ? oz1 : -oz2;
				}
			}
		}
	}
}
