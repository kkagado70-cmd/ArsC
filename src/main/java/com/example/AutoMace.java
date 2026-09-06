package com.example;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

public class AutoMace implements ClientModInitializer {
    public static final String FILE_NAME = "AutoMace.java";
    private static KeyMapping toggleKey;
    public static boolean enabled = false;

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.automace.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_M, KeyMapping.Category.MISC
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.level == null) return;
            while (toggleKey.consumeClick()) {
                enabled = !enabled;
            }
            if (enabled) {
                HT1CombatController.onTick(client);
            }
        });
    }

    public static class HT1CombatController {
        private static final double MAX_SWING_RANGE = 4.5D;
        private static final double MAX_AIM_RANGE = 20.0D;
        private static final double MIN_FALL_DIST = 1.5D;
        private static final float HYPER_SNAP_SPEED = 0.99F;

        public static void onTick(Minecraft client) {
            if (client.player == null || client.level == null) return;
            
            Player target = null;
            double minDist = Double.MAX_VALUE;
            for (Player p : client.level.players()) {
                if (p == client.player || !p.isAlive() || p.isSpectator()) continue;
                double dist = client.player.distanceToSqr(p);
                if (dist > MAX_AIM_RANGE * MAX_AIM_RANGE) continue;
                if (dist < minDist) {
                    minDist = dist;
                    target = p;
                }
            }

            if (target == null) return;

            double fallDist = client.player.fallDistance;
            boolean isElytraFlying = client.player.isFallFlying();
            boolean diveTrigger = fallDist >= MIN_FALL_DIST || isElytraFlying || client.player.getDeltaMovement().y < -0.3D;

            if (diveTrigger) {
                int maceSlot = InventoryManager.findItem(client, Items.MACE);
                if (maceSlot != -1) {
                    InventoryManager.selectSlot(client, maceSlot);
                    RotationManager.smoothTo(client, target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D), HYPER_SNAP_SPEED);
                    if (client.player.distanceTo(target) <= MAX_SWING_RANGE && client.player.getAttackStrengthScale(0.0F) >= 0.7F) {
                        InteractionManager.simulateClickAttack(client);
                    }
                }
            }
        }
    }
}