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
 * Position-based-dynamics particles driven by soft "muscle" targets plus a procedural step controller.
 * Feet plant on raycast ground; the pelvis height is solved so the stiff legs exactly reach the planted feet.
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

	private final Vector3d P = new Vector3d(), lastP = new Vector3d(), vel = new Vector3d();
	private final Vector3d fwd = new Vector3d(), rgt = new Vector3d(), tUp = new Vector3d(), tRgt = new Vector3d();
	private final Vector3d[] plant = vecs(2), swingFrom = vecs(2), swingTo = vecs(2), footTarget = vecs(2),
		desired = vecs(2), hipOff = vecs(2), shoulderOff = vecs(2);
	private final double[] footK = new double[2], swingT = new double[2], swingDur = {0.2, 0.2};
	private final boolean[] swinging = new boolean[2];
	private final Vector3d a = new Vector3d(), b = new Vector3d(), c = new Vector3d();

	private boolean init, ground;
	private long lastNanos;
	private double limp, muscle = 1.0, time;
	private int lastHurt;
	public double bodyYawDeg;

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

		vel.x += (clamp((P.x - lastP.x) / dt, -25, 25) - vel.x) * 0.3;
		vel.y += (clamp((P.y - lastP.y) / dt, -25, 25) - vel.y) * 0.3;
		vel.z += (clamp((P.z - lastP.z) / dt, -25, 25) - vel.z) * 0.3;
		lastP.set(P);

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
		}
		for (int i = 0; i < N; i++) { old[i].set(x[i]); v[i].zero(); }
		lastP.set(P);
		vel.zero();
		init = true;
	}

	private void frame() {
		tUp.set(x[CHEST]).sub(x[PELVIS]);
		if (tUp.lengthSquared() < 1e-8) tUp.set(0, 1, 0);
		tUp.normalize();
		tRgt.set(rgt).fma(-rgt.dot(tUp), tUp);
		if (tRgt.lengthSquared() < 1e-6) tRgt.set(rgt);
		tRgt.normalize();
		for (int s = 0; s < 2; s++) {
			double sg = sgn(s);
			hipOff[s].set(tRgt).mul(sg * HIP);
			shoulderOff[s].set(tUp).mul(-SHOULDER_DROP).fma(sg * SHOULDER, tRgt);
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

	// ------------------------------------------------------------------ step controller (foot placement)

	private double groundY(ClientWorld w, PlayerEntity p, double px, double top, double pz) {
		BlockHitResult hr = w.raycast(new RaycastContext(new Vec3d(px, top, pz), new Vec3d(px, top - 2.5, pz),
			RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, p));
		return hr.getType() == HitResult.Type.BLOCK ? hr.getPos().y : Double.NaN;
	}

	private void startSwing(int s, double speed) {
		swinging[s] = true;
		swingT[s] = 0;
		swingDur[s] = clamp(0.30 - 0.035 * speed, 0.13, 0.30);
		swingFrom[s].set(plant[s]);
		swingTo[s].set(desired[s]);
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
			for (int s = 0; s < 2; s++) {
				swinging[s] = false;
				plant[s].set(x[FOOT_R + s]);
				hipPos(s, a);
				footTarget[s].set(a).add(0, -LEG, 0).fma(-0.05, vel).fma(sgn(s) * 0.08, rgt);
				footK[s] = 6.0 * muscle;
			}
			return;
		}

		int swingCount = (swinging[0] ? 1 : 0) + (swinging[1] ? 1 : 0);
		int maxSwing = speed > 4.6 ? 2 : 1;
		double thresh = 0.22 + 0.04 * Math.min(speed, 4.0);
		int best = -1;
		double bd = thresh;
		for (int s = 0; s < 2; s++) {
			if (swinging[s]) continue;
			boolean overreach = Math.hypot(plant[s].x - P.x, plant[s].z - P.z) > LEG * 0.8;
			double d = Math.hypot(plant[s].x - desired[s].x, plant[s].z - desired[s].z)
				+ 0.8 * Math.abs(plant[s].y - desired[s].y);
			if (overreach) { best = s; bd = 1e9; }
			else if (d > bd && swingCount < maxSwing) { bd = d; best = s; }
		}
		if (best >= 0) startSwing(best, speed);

		for (int s = 0; s < 2; s++) {
			if (swinging[s]) {
				swingT[s] += h / swingDur[s];
				double rem = Math.max(0.0, 1.0 - swingT[s]) * swingDur[s];
				swingTo[s].set(desired[s]).add(vel.x * rem * 0.6, 0, vel.z * rem * 0.6);
				double g = groundY(w, p, swingTo[s].x, P.y + 0.6, swingTo[s].z);
				swingTo[s].y = Double.isNaN(g) ? P.y : g;

				double t = Math.min(1.0, swingT[s]);
				double e = t * t * (3 - 2 * t);
				footTarget[s].set(swingFrom[s]).lerp(swingTo[s], e);
				double stride = Math.hypot(swingTo[s].x - swingFrom[s].x, swingTo[s].z - swingFrom[s].z);
				footTarget[s].y += Math.sin(Math.PI * t) * (0.04 + 0.2 * Math.min(stride, 0.6));
				footK[s] = 45.0 * muscle;
				if (swingT[s] >= 1.0) { swinging[s] = false; plant[s].set(swingTo[s]); }
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

	private void muscles(double speed, boolean sneak, double h) {
		double m = muscle;

		// pelvis follows the real player; its height is solved so the stiff planted legs just reach the ground
		double kxz = 40.0 * m;
		double tx = P.x + vel.x / Math.max(kxz, 1.0), tz = P.z + vel.z / Math.max(kxz, 1.0);
		double ty = P.y + LEG * (ground ? 0.999 : 0.95);
		if (ground) {
			for (int s = 0; s < 2; s++) {
				if (swinging[s]) continue;
				double dx = tx + hipOff[s].x - plant[s].x, dz = tz + hipOff[s].z - plant[s].z;
				double reachY = Math.sqrt(Math.max(1e-4, LEG * LEG - dx * dx - dz * dz));
				ty = Math.min(ty, plant[s].y + reachY);
			}
		}
		pull(PELVIS, tx, ty, tz, kxz, 30.0 * m, h);

		// torso: stays almost upright (classic look), tiny lean into motion
		double lean = clamp(speed * 0.02, 0.0, 0.08) + (sneak ? 0.3 : 0.0);
		double sway = 0;
		for (int s = 0; s < 2; s++) if (swinging[s]) sway += (s == 0 ? -0.03 : 0.03);
		c.set(0, 1, 0).fma(lean, fwd).fma(sway, rgt).normalize();
		a.set(x[PELVIS]).fma(TORSO, c);
		pull(CHEST, a, 18.0 * m, h);

		c.set(x[CHEST]).sub(x[PELVIS]);
		if (c.lengthSquared() > 1e-8) c.normalize(); else c.set(0, 1, 0);
		a.set(x[CHEST]).fma(HEAD, c);
		pull(HEADTOP, a, 16.0 * m, h);

		for (int s = 0; s < 2; s++) {
			double sg = sgn(s);

			// --- leg: one rigid stick. Swinging/airborne feet are projected onto the leg-length sphere.
			hipPos(s, a);
			if (ground && !swinging[s]) {
				pull(FOOT_R + s, footTarget[s], footK[s], h);
			} else {
				pull(FOOT_R + s, reach(a, footTarget[s], LEG), footK[s], h);
			}

			// --- arm: one rigid stick, swings opposite the same-side leg
			shoulderPos(s, a);
			if (ground) {
				double armFwd = (footTarget[1 - s].x - P.x) * fwd.x + (footTarget[1 - s].z - P.z) * fwd.z;
				double ang = clamp(armFwd * 1.8, -0.8, 0.8);
				b.set(a).add(0, -Math.cos(ang) * ARM, 0).fma(Math.sin(ang) * ARM, fwd);
			} else {
				b.set(a).fma(sg * 0.8, rgt).add(0, -0.3 + 0.3 * Math.sin(time * 9.0 + s * Math.PI), 0);
				b = reach2(a, b);
			}
			pull(HAND_R + s, b, 14.0 * m, h);
		}
	}

	private Vector3d reach2(Vector3d origin, Vector3d target) {
		Vector3d d = new Vector3d(target).sub(origin);
		if (d.lengthSquared() < 1e-8) d.set(0, -1, 0);
		return d.normalize().mul(ARM).add(origin);
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
