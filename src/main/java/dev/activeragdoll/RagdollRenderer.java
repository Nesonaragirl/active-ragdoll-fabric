package dev.activeragdoll;

import net.minecraft.client.model.Dilation;
import net.minecraft.client.model.ModelData;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.model.ModelPartBuilder;
import net.minecraft.client.model.ModelPartData;
import net.minecraft.client.model.ModelTransform;
import net.minecraft.client.model.TexturedModelData;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.util.SkinTextures;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;
import org.joml.Vector3d;

/** Draws the vanilla player skin as oriented cuboids (head, torso, 2x2 arm segments, 2x2 leg segments). */
public final class RagdollRenderer {
	private RagdollRenderer() {}

	private static final class Parts {
		ModelPart head, hat, body, rUpArm, rLoArm, lUpArm, lLoArm, rUpLeg, rLoLeg, lUpLeg, lLoLeg;
	}

	private static Parts wide, slim;

	private static Parts build(boolean isSlim) {
		float aw = isSlim ? 3f : 4f;
		ModelData md = new ModelData();
		ModelPartData r = md.getRoot();
		r.addChild("head", ModelPartBuilder.create().uv(0, 0).cuboid(-4, -8, -4, 8, 8, 8), ModelTransform.NONE);
		r.addChild("hat", ModelPartBuilder.create().uv(32, 0).cuboid(-4, -8, -4, 8, 8, 8, new Dilation(0.5f)), ModelTransform.NONE);
		r.addChild("body", ModelPartBuilder.create().uv(16, 16).cuboid(-4, 0, -2, 8, 12, 4), ModelTransform.NONE);
		// arms: pivot at shoulder; upper = 7px (starts 2px above pivot), lower = 5px
		r.addChild("rua", ModelPartBuilder.create().uv(40, 16).cuboid(-aw / 2, -2, -2, aw, 7, 4), ModelTransform.NONE);
		r.addChild("rla", ModelPartBuilder.create().uv(40, 23).cuboid(-aw / 2, 0, -2, aw, 5, 4), ModelTransform.NONE);
		r.addChild("lua", ModelPartBuilder.create().uv(32, 48).cuboid(-aw / 2, -2, -2, aw, 7, 4), ModelTransform.NONE);
		r.addChild("lla", ModelPartBuilder.create().uv(32, 55).cuboid(-aw / 2, 0, -2, aw, 5, 4), ModelTransform.NONE);
		// legs: 6px thigh + 6px shin
		r.addChild("rul", ModelPartBuilder.create().uv(0, 16).cuboid(-2, 0, -2, 4, 6, 4), ModelTransform.NONE);
		r.addChild("rll", ModelPartBuilder.create().uv(0, 22).cuboid(-2, 0, -2, 4, 6, 4), ModelTransform.NONE);
		r.addChild("lul", ModelPartBuilder.create().uv(16, 48).cuboid(-2, 0, -2, 4, 6, 4), ModelTransform.NONE);
		r.addChild("lll", ModelPartBuilder.create().uv(16, 54).cuboid(-2, 0, -2, 4, 6, 4), ModelTransform.NONE);
		ModelPart root = TexturedModelData.of(md, 64, 64).createModel();
		Parts p = new Parts();
		p.head = root.getChild("head"); p.hat = root.getChild("hat"); p.body = root.getChild("body");
		p.rUpArm = root.getChild("rua"); p.rLoArm = root.getChild("rla");
		p.lUpArm = root.getChild("lua"); p.lLoArm = root.getChild("lla");
		p.rUpLeg = root.getChild("rul"); p.rLoLeg = root.getChild("rll");
		p.lUpLeg = root.getChild("lul"); p.lLoLeg = root.getChild("lll");
		return p;
	}

	public static void draw(Ragdoll r, AbstractClientPlayerEntity p, float td, MatrixStack m,
							VertexConsumerProvider vcp, int light) {
		SkinTextures sk = p.getSkinTextures();
		boolean isSlim = sk.model() == SkinTextures.Model.SLIM;
		if (isSlim) { if (slim == null) slim = build(true); } else { if (wide == null) wide = build(false); }
		Parts parts = isSlim ? slim : wide;

		VertexConsumer vc = vcp.getBuffer(RenderLayer.getEntityCutoutNoCull(sk.texture()));
		int ov = LivingEntityRenderer.getOverlay(p, 0f);
		Vec3d o = p.getLerpedPos(td);
		Quaternionf yawQ = new Quaternionf().rotationY((float) (Math.PI - Math.toRadians(r.bodyYawDeg)));

		float headYaw = MathHelper.lerpAngleDegrees(td, p.prevHeadYaw, p.headYaw);
		float netYaw = MathHelper.clamp(MathHelper.wrapDegrees(headYaw - (float) r.bodyYawDeg), -80f, 80f);
		parts.head.pitch = parts.hat.pitch = (float) Math.toRadians(p.getPitch(td));
		parts.head.yaw = parts.hat.yaw = (float) Math.toRadians(netYaw);

		Vector3d[] x = r.x;
		Vector3d a = new Vector3d();

		seg(m, parts.body, x[Ragdoll.CHEST], x[Ragdoll.PELVIS], false, yawQ, o, vc, light, ov);
		seg(m, parts.head, x[Ragdoll.CHEST], x[Ragdoll.HEADTOP], true, yawQ, o, vc, light, ov);
		seg(m, parts.hat, x[Ragdoll.CHEST], x[Ragdoll.HEADTOP], true, yawQ, o, vc, light, ov);

		for (int s = 0; s < 2; s++) {
			boolean right = s == 0;
			r.shoulderPos(s, a);
			seg(m, right ? parts.rUpArm : parts.lUpArm, a, x[Ragdoll.ELBOW_R + 2 * s], false, yawQ, o, vc, light, ov);
			seg(m, right ? parts.rLoArm : parts.lLoArm, x[Ragdoll.ELBOW_R + 2 * s], x[Ragdoll.HAND_R + 2 * s], false, yawQ, o, vc, light, ov);
			r.hipPos(s, a);
			seg(m, right ? parts.rUpLeg : parts.lUpLeg, a, x[Ragdoll.KNEE_R + 2 * s], false, yawQ, o, vc, light, ov);
			seg(m, right ? parts.rLoLeg : parts.lLoLeg, x[Ragdoll.KNEE_R + 2 * s], x[Ragdoll.ANKLE_R + 2 * s], false, yawQ, o, vc, light, ov);
		}
	}

	/** Model space has +Y down for limbs and is flipped by scale(-1,-1,1); heads extend toward +Y world. */
	private static void seg(MatrixStack m, ModelPart part, Vector3d from, Vector3d to, boolean up, Quaternionf yawQ,
							Vec3d o, VertexConsumer vc, int light, int ov) {
		double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
		double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (len < 1e-6) { dx = 0; dy = up ? 1 : -1; dz = 0; len = 1; }
		Quaternionf q = new Quaternionf().rotationTo(0f, up ? 1f : -1f, 0f, (float) (dx / len), (float) (dy / len), (float) (dz / len));
		q.mul(yawQ);
		m.push();
		m.translate(from.x - o.x, from.y - o.y, from.z - o.z);
		m.multiply(q);
		m.scale(0.9375f, 0.9375f, 0.9375f);
		m.scale(-1f, -1f, 1f);
		part.render(m, vc, light, ov);
		m.pop();
	}
}
