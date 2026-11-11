package dev.activeragdoll;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ActiveRagdollClient implements ClientModInitializer {
	public static boolean enabled = true;
	public static boolean forceLimp = false;

	private static final Map<UUID, Ragdoll> STATES = new HashMap<>();
	private static KeyBinding toggleKey;
	private static KeyBinding limpKey;

	@Override
	public void onInitializeClient() {
		toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.activeragdoll.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, "key.categories.activeragdoll"));
		limpKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.activeragdoll.limp", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J, "key.categories.activeragdoll"));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (toggleKey.wasPressed()) {
				enabled = !enabled;
				if (client.player != null) {
					client.player.sendMessage(Text.literal("Active ragdoll: " + (enabled ? "ON" : "OFF")), true);
				}
			}
			forceLimp = limpKey.isPressed();
		});
	}

	/** Called from the renderer mixin. Returns true if vanilla rendering should be cancelled. */
	public static boolean render(AbstractClientPlayerEntity p, float td, MatrixStack m, VertexConsumerProvider vcp, int light) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (!enabled || mc.world == null || p != mc.player) return false;
		Ragdoll r = STATES.computeIfAbsent(p.getUuid(), k -> new Ragdoll());
		float bodyYaw = MathHelper.lerpAngleDegrees(td, p.prevBodyYaw, p.bodyYaw);
		r.update(p, mc.world, td, bodyYaw, forceLimp);
		RagdollRenderer.draw(r, p, td, m, vcp, light);
		return true;
	}
}
