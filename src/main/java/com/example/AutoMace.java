package com.example;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.InteractionHand;
import net.minecraft.network.chat.Component;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

public class AutoMace implements ClientModInitializer {
    private static final Minecraft mc = Minecraft.getInstance();
    private static KeyMapping toggleKey;
    private static boolean active = false;
    private double highestY = 0.0D;
    private int delayTicks = 0;

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.automace.toggle",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_M,
            "category.automace"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (mc.player == null || mc.world == null) return;

            while (toggleKey.consumeClick()) {
                active = !active;
                mc.player.sendSystemMessage(Component.literal("§6[AutoMace] " + (active ? "§aEnabled" : "§cDisabled")));
            }

            if (!active) return;

            if (delayTicks > 0) {
                delayTicks--;
                return;
            }

            if (mc.player.onGround()) {
                highestY = mc.player.getY();
            } else {
                highestY = Math.max(highestY, mc.player.getY());
            }
            double fallDist = Math.max(0.0D, highestY - mc.player.getY());

            int maceSlot = findItem(MaceItem.class);
            if (maceSlot == -1 && !(mc.player.getMainHandItem().getItem() instanceof MaceItem)) return;

            Player target = findNearestTarget();
            if (target == null) return;

            boolean falling = mc.player.fallDistance >= 1.5D || fallDist >= 1.5D;
            if (!falling) return;

            boolean blocking = target.isUsingItem() && target.getUseItem().getItem() instanceof ShieldItem;
            if (blocking) {
                int axeSlot = findItem(AxeItem.class);
                if (axeSlot != -1 && maceSlot != -1) {
                    int original = mc.player.getInventory().selectedSlot;
                    mc.player.getInventory().selectedSlot = axeSlot;
                    mc.gameMode.attackEntity(mc.player, target);
                    mc.player.swing(InteractionHand.MAIN_HAND);
                    
                    mc.player.getInventory().selectedSlot = maceSlot;
                    mc.gameMode.attackEntity(mc.player, target);
                    mc.player.swing(InteractionHand.MAIN_HAND);
                    
                    mc.player.getInventory().selectedSlot = original;
                    delayTicks = 10;
                    return;
                }
            }

            if (maceSlot != -1) {
                int original = mc.player.getInventory().selectedSlot;
                mc.player.getInventory().selectedSlot = maceSlot;
                if (mc.player.distanceTo(target) <= 2.95D) {
                    mc.gameMode.attackEntity(mc.player, target);
                    mc.player.swing(InteractionHand.MAIN_HAND);
                    delayTicks = 8;
                }
                mc.player.getInventory().selectedSlot = original;
            }
        });
    }

    private int findItem(Class<?> itemClass) {
        for (int i = 0; i < 9; i++) {
            if (itemClass.isInstance(mc.player.getInventory().getItem(i).getItem())) {
                return i;
            }
        }
        return -1;
    }

    private Player findNearestTarget() {
        Player best = null;
        double bestDist = Double.MAX_VALUE;
        for (Player player : mc.world.players()) {
            if (player != mc.player && player.isAlive() && !player.isSpectator()) {
                double dist = mc.player.distanceToSqr(player);
                if (dist <= 16.0D && dist < bestDist) {
                    bestDist = dist;
                    best = player;
                }
            }
        }
        return best;
    }
}
