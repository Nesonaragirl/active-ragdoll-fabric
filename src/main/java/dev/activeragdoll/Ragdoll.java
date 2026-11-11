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
 * Position-based-dynamics skeleton driven by "muscles" (soft position targets) plus a procedural
 * step controller. The root follows the real (server-authoritative) player; limbs are simulated.
 * Feet are planted on raycast ground positions and only lift when they drift too far from the hips.
 */
public final class Ragdoll {
	public static final double PX = 0.9375 / 16.0; // one model pixel in blocks (player model scale)
	public static final double THIGH = 6 * PX, SHIN = 6 * PX, TORSO = 12 * PX, HEAD = 8 * PX, UARM = 5 * PX, FARM = 5 * PX;
	static final double LEG = THIGH + SHIN, ARM = UARM + FARM;
	static final double HIP = 2 * PX, SHOULDER = 6 * PX, SHOULDER_DROP = 2 * PX;
	static final double GRAVITY = 26.0;

	public static final int PELVIS = 0, CHEST = 1, HEADTOP = 2, KNEE_R = 3, ANKLE_R = 4, KNEE_L = 5, ANKLE_L = 6,
		ELBOW_R = 7, HAND_R = 8, ELBOW_L = 9, HAND_L = 10, N = 11;

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
		x[PELVIS].set(P.x, P.y + LEG * 0.97, P.z);
		x[CHEST].set(x[PELVIS]).add(0, TORSO, 0);
		x[HEADTOP].set(x[CHEST]).add(0, HEAD, 0);
		for (int s = 0; s < 2; s++) {
			double sg = sgn(s);
			a.set(P).fma(sg * HIP, rgt);
			plant[s].set(a);
			x[ANKLE_R + 2 * s].set(a);
			x[KNEE_R + 2 * s].set(a.x, P.y + SHIN, a.z).fma(0.04, fwd);
			b.set(x[CHEST]).add(0, -SHOULDER_DROP, 0).fma(sg * SHOULDER, rgt);
			x[ELBOW_R + 2 * s].set(b).add(0, -UARM, 0);
			x[HAND_R + 2 * s].set(b).add(0, -ARM, 0);
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
			collide(w, KNEE_R + 2 * s, 0.07);
			collide(w, ANKLE_R + 2 * s, 0.02);
			collide(w, ELBOW_R + 2 * s, 0.05);
			collide(w, HAND_R + 2 * s, 0.05);
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
			desired[s].x += clamp(vel.x * 0.17, -0.5, 0.5);
			desired[s].z += clamp(vel.z * 0.17, -0.5, 0.5);
			double g = groundY(w, p, desired[s].x, P.y + 0.6, desired[s].z);
			desired[s].y = Double.isNaN(g) ? P.y : g;
		}

		if (!ground) {
			for (int s = 0; s < 2; s++) {
				swinging[s] = false;
				plant[s].set(x[ANKLE_R + 2 * s]);
				hipPos(s, a);
				footTarget[s].set(a).add(0, -LEG * 0.85, 0).fma(-0.04, vel).fma(sgn(s) * 0.08, rgt);
				footK[s] = 6.0 * muscle;
			}
			return;
		}

		int swingCount = (swinging[0] ? 1 : 0) + (swinging[1] ? 1 : 0);
		int maxSwing = speed > 4.6 ? 2 : 1;
		double thresh = 0.26 + 0.05 * Math.min(speed, 4.0);
		int best = -1;
		double bd = thresh;
		for (int s = 0; s < 2; s++) {
			if (swinging[s]) continue;
			boolean overreach = Math.hypot(plant[s].x - P.x, plant[s].z - P.z) > LEG * 1.25;
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
				footTarget[s].y += Math.sin(Math.PI * t) * (0.06 + 0.3 * Math.min(stride, 0.7));
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

	private void muscles(double speed, boolean sneak, double h) {
		double m = muscle;

		// pelvis: follows the real player; height limited by reach of planted legs
		double ceiling = P.y + LEG * 0.98, floor = -1e9, bob = 0;
		if (ground) {
			for (int s = 0; s < 2; s++) {
				if (!swinging[s]) {
					ceiling = Math.min(ceiling, plant[s].y + LEG * 0.98);
					floor = Math.max(floor, plant[s].y + 0.35);
				} else {
					bob = 0.03 * Math.sin(Math.PI * Math.min(1.0, swingT[s]));
				}
			}
		}
		double ty = P.y + LEG * (ground ? 0.97 : 0.9) - (sneak ? 0.17 : 0.0) + bob;
		if (ground) ty = Math.min(Math.max(ty, floor), ceiling);
		double kxz = 40.0 * m;
		pull(PELVIS, P.x + vel.x / Math.max(kxz, 1.0), ty, P.z + vel.z / Math.max(kxz, 1.0), kxz, 30.0 * m, h);

		// torso lean into motion, sway over the stance foot
		double lean = clamp(speed * 0.05, 0.0, 0.3) + (sneak ? 0.4 : 0.0);
		double sway = 0;
		for (int s = 0; s < 2; s++) if (swinging[s]) sway += (s == 0 ? -0.06 : 0.06);
		c.set(0, 1, 0).fma(lean, fwd).fma(sway, rgt).normalize();
		a.set(x[PELVIS]).fma(TORSO, c);
		pull(CHEST, a, 16.0 * m, h);

		c.set(x[CHEST]).sub(x[PELVIS]);
		if (c.lengthSquared() > 1e-8) c.normalize(); else c.set(0, 1, 0);
		a.set(x[CHEST]).fma(HEAD, c);
		pull(HEADTOP, a, 14.0 * m, h);

		for (int s = 0; s < 2; s++) {
			double sg = sgn(s);

			// --- leg: ankle follows foot target, knee uses analytic two-bone IK pole (forward)
			int an = ANKLE_R + 2 * s, kn = KNEE_R + 2 * s;
			pull(an, footTarget[s], footK[s], h);
			hipPos(s, a);
			b.set(x[an]).sub(a);
			double L = b.length();
			if (L > 1e-6) {
				b.div(L);
				double hk = Math.max(0.015, Math.sqrt(Math.max(0.0, THIGH * THIGH - 0.25 * L * L)));
				c.set(fwd).fma(-fwd.dot(b), b);
				if (c.lengthSquared() < 1e-6) c.set(rgt);
				c.normalize();
				c.mul(hk).fma(L * 0.5, b).add(a);
				pull(kn, c, 25.0 * m, h);
			}

			// --- arm: swings opposite the same-side leg; hangs loosely otherwise
			shoulderPos(s, a);
			double ang;
			if (ground) {
				double armFwd = (footTarget[1 - s].x - P.x) * fwd.x + (footTarget[1 - s].z - P.z) * fwd.z;
				ang = clamp(armFwd * 1.8, -0.8, 0.8);
				b.set(a).add(0, -Math.cos(ang) * ARM * 0.97, 0).fma(Math.sin(ang) * ARM * 0.97, fwd).fma(sg * 0.05, rgt);
			} else {
				ang = 0.6;
				b.set(a).fma(sg * 0.65, rgt).add(0, -0.2 + 0.25 * Math.sin(time * 9.0 + s * Math.PI), 0);
			}
			pull(HAND_R + 2 * s, b, 9.0 * m, h);
			c.set(a).add(b).mul(0.5).fma(0.045 + 0.13 * Math.max(0.0, Math.sin(ang)), fwd);
			pull(ELBOW_R + 2 * s, c, 6.0 * m, h);
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
			int kn = KNEE_R + 2 * s, an = ANKLE_R + 2 * s, el = ELBOW_R + 2 * s, hd = HAND_R + 2 * s;
			double wa = (ground && !swinging[s] && muscle > 0.2) ? 0.1 : 1.0;
			linkOff(PELVIS, hipOff[s], kn, THIGH, 0.35, 1.0);
			link(kn, an, SHIN, 1.0, wa);
			linkOff(CHEST, shoulderOff[s], el, UARM, 0.12, 1.0);
			link(el, hd, FARM, 1.0, 1.0);
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
