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
import net.minecraft.util.Mth;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

public class AutoMace implements ClientModInitializer {
    private static final Minecraft mc = Minecraft.getInstance();
    private static KeyMapping toggleKey;
    public static boolean enabled = false;
    private static double highestY = 0.0D;
    private static int delayTicks = 0;

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.automace.toggle",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_M,
            KeyMapping.Category.MISC
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (mc.player == null || mc.level == null) return;

            while (toggleKey.consumeClick()) {
                toggle();
            }

            if (enabled) {
                onTick(client);
            }
        });
    }

    public static void toggle() {
        enabled = !enabled;
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("§6[AutoMace] " + (enabled ? "§aON" : "§cOFF")), true);
        }
    }

    public static void onTick(Minecraft client) {
        if (mc.player == null || mc.level == null) return;

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

        applySmoothLookAt(target);

        boolean falling = mc.player.fallDistance >= 1.5D || fallDist >= 1.5D;
        if (!falling) return;

        boolean blocking = target.isUsingItem() && target.getUseItem().getItem() instanceof ShieldItem;
        if (blocking) {
            int axeSlot = findItem(AxeItem.class);
            if (axeSlot != -1 && maceSlot != -1) {
                int original = mc.player.getInventory().getSelectedSlot();
                mc.player.getInventory().setSelectedSlot(axeSlot);
                mc.gameMode.attack(mc.player, target);
                mc.player.swing(InteractionHand.MAIN_HAND);
                
                mc.player.getInventory().setSelectedSlot(maceSlot);
                mc.gameMode.attack(mc.player, target);
                mc.player.swing(InteractionHand.MAIN_HAND);
                
                mc.player.getInventory().setSelectedSlot(original);
                delayTicks = 10;
                return;
            }
        }

        if (maceSlot != -1) {
            int original = mc.player.getInventory().getSelectedSlot();
            mc.player.getInventory().setSelectedSlot(maceSlot);
            if (mc.player.distanceTo(target) <= 2.95D) {
                mc.gameMode.attack(mc.player, target);
                mc.player.swing(InteractionHand.MAIN_HAND);
                delayTicks = 8;
            }
            mc.player.getInventory().setSelectedSlot(original);
        }
    }

    private static void applySmoothLookAt(Player target) {
        double dx = target.getX() - mc.player.getX();
        double dy = target.getEyeY() - mc.player.getEyeY();
        double dz = target.getZ() - mc.player.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);

        float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        float targetPitch = (float) (-Math.toDegrees(Math.atan2(dy, dist)));

        float currentYaw = mc.player.getYRot();
        float currentPitch = mc.player.getXRot();

        float yawDiff = Mth.wrapDegrees(targetYaw - currentYaw);
        float pitchDiff = Mth.wrapDegrees(targetPitch - currentPitch);

        mc.player.setYRot(currentYaw + yawDiff * 0.35F);
        mc.player.setXRot(currentPitch + pitchDiff * 0.35F);
    }

    private static int findItem(Class<?> itemClass) {
        for (int i = 0; i < 9; i++) {
            if (itemClass.isInstance(mc.player.getInventory().getItem(i).getItem())) {
                return i;
            }
        }
        return -1;
    }

    private static Player findNearestTarget() {
        Player best = null;
        double bestDist = Double.MAX_VALUE;
        for (Player player : mc.level.players()) {
            if (player != mc.player && player.isAlive() && !player.isSpectator()) {
                double dist = mc.player.distanceToSqr(player);
                if (dist <= 49.0D && dist < bestDist) {
                    bestDist = dist;
                    best = player;
                }
            }
        }
        return best;
    }
}
