package com.kovak.automace;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.item.ShieldItem;
import net.minecraft.item.MaceItem;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

public class AutoMace implements ClientModInitializer {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static KeyBinding toggleKey;
    private static boolean active = false;
    private double highestY = 0.0D;
    private int delayTicks = 0;

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.automace.toggle",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_M,
            "category.automace"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (mc.player == null || mc.world == null) return;

            while (toggleKey.wasPressed()) {
                active = !active;
                mc.player.sendMessage(Text.literal("§6[AutoMace] " + (active ? "§aEnabled" : "§cDisabled")), true);
            }

            if (!active) return;

            if (delayTicks > 0) {
                delayTicks--;
                return;
            }

            if (mc.player.isOnGround()) {
                highestY = mc.player.getY();
            } else {
                highestY = Math.max(highestY, mc.player.getY());
            }
            double fallDist = Math.max(0.0D, highestY - mc.player.getY());

            int maceSlot = findItem(MaceItem.class);
            if (maceSlot == -1 && !(mc.player.getMainHandStack().getItem() instanceof MaceItem)) return;

            PlayerEntity target = findNearestTarget();
            if (target == null) return;

            applySmoothRotation(target);

            boolean falling = mc.player.fallDistance >= 1.5D || fallDist >= 1.5D;
            if (!falling) return;

            boolean blocking = target.isUsingItem() && target.getActiveItem().getItem() instanceof ShieldItem;
            if (blocking) {
                int axeSlot = findItem(AxeItem.class);
                if (axeSlot != -1 && maceSlot != -1) {
                    int original = mc.player.getInventory().selectedSlot;
                    mc.player.getInventory().selectedSlot = axeSlot;
                    mc.interactionManager.attackEntity(mc.player, target);
                    mc.player.swingHand(Hand.MAIN_HAND);
                    
                    mc.player.getInventory().selectedSlot = maceSlot;
                    mc.interactionManager.attackEntity(mc.player, target);
                    mc.player.swingHand(Hand.MAIN_HAND);
                    
                    mc.player.getInventory().selectedSlot = original;
                    delayTicks = 10;
                    return;
                }
            }

            if (maceSlot != -1) {
                int original = mc.player.getInventory().selectedSlot;
                mc.player.getInventory().selectedSlot = maceSlot;
                if (mc.player.distanceTo(target) <= 2.95D) {
                    mc.interactionManager.attackEntity(mc.player, target);
                    mc.player.swingHand(Hand.MAIN_HAND);
                    delayTicks = 8;
                }
                mc.player.getInventory().selectedSlot = original;
            }
        });
    }

    private void applySmoothRotation(PlayerEntity target) {
        double dx = target.getX() - mc.player.getX();
        double dy = target.getEyeY() - mc.player.getEyeY();
        double dz = target.getZ() - mc.player.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        
        float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        float targetPitch = (float) (-Math.toDegrees(Math.atan2(dy, dist)));

        float currentYaw = mc.player.getYaw();
        float currentPitch = mc.player.getPitch();

        float yawDiff = MathHelper.wrapDegrees(targetYaw - currentYaw);
        float pitchDiff = MathHelper.wrapDegrees(targetPitch - currentPitch);

        mc.player.setYaw(currentYaw + yawDiff / 15.0F);
        mc.player.setPitch(currentPitch + pitchDiff / 15.0F);
    }

    private int findItem(Class<?> itemClass) {
        for (int i = 0; i < 9; i++) {
            if (itemClass.isInstance(mc.player.getInventory().getStack(i).getItem())) {
                return i;
            }
        }
        return -1;
    }

    private PlayerEntity findNearestTarget() {
        PlayerEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player != mc.player && player.isAlive() && !player.isSpectator()) {
                double dist = mc.player.squaredDistanceTo(player);
                if (dist <= 16.0D && dist < bestDist) {
                    bestDist = dist;
                    best = player;
                }
            }
        }
        return best;
    }
}
