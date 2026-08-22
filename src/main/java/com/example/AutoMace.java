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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionHand;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

public class AutoMace implements ClientModInitializer {
    private static final Minecraft mc = Minecraft.getInstance();
    private static KeyMapping toggleKey;
    public static boolean enabled = false;

    private static final double MAX_SWING_RANGE = 2.95D;
    private static final double MAX_AIM_RANGE = 7.0D;
    private static final double MIN_FALL_DIST = 1.5D;
    private static final float ROTATION_SMOOTHNESS = 0.35F;

    private static Player currentTarget = null;
    private static State state = State.IDLE;
    private static int delayTimer = 0;
    private static int preSequenceSlot = -1;
    private static double highestY = 0.0D;
    private static long lastAttackTime = 0L;

    public enum State {
        IDLE,
        PREPARING_SHIELD_BREAK,
        EXECUTING_AXE_STRIKE,
        SWAPPING_TO_MACE,
        EXECUTING_MACE_SMASH,
        RESETTING
    }

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
        resetState();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("§6[AutoMace] " + (enabled ? "§aON" : "§cOFF")), true);
        }
    }

    public static void onTick(Minecraft client) {
        if (mc.player == null || mc.level == null) return;

        if (delayTimer > 0) {
            delayTimer--;
            return;
        }

        updateFallDistanceTracker();

        double currentFallDistance = Math.max(0.0D, highestY - mc.player.getY());

        currentTarget = locateOptimalTarget(MAX_AIM_RANGE);
        if (currentTarget == null) {
            resetState();
            return;
        }

        applyBypassHumanRotation(currentTarget);

        boolean isFalling = mc.player.fallDistance >= MIN_FALL_DIST || currentFallDistance >= MIN_FALL_DIST;
        if (!isFalling) return;

        boolean isBlocking = isTargetBlockingWithShield(currentTarget);

        switch (state) {
            case IDLE:
                if (isBlocking) {
                    state = State.PREPARING_SHIELD_BREAK;
                } else {
                    state = State.EXECUTING_MACE_SMASH;
                }
                break;

            case PREPARING_SHIELD_BREAK:
                int axeSlot = findAxeSlot();
                int maceSlot = selectOptimalMaceSlot(currentTarget, currentFallDistance);
                
                if (axeSlot != -1 && maceSlot != -1) {
                    preSequenceSlot = mc.player.getInventory().getSelectedSlot();
                    mc.player.getInventory().setSelectedSlot(axeSlot);
                    state = State.EXECUTING_AXE_STRIKE;
                } else {
                    resetState();
                }
                break;

            case EXECUTING_AXE_STRIKE:
                if (mc.player.distanceTo(currentTarget) <= MAX_SWING_RANGE) {
                    mc.gameMode.attack(mc.player, currentTarget);
                    mc.player.swing(InteractionHand.MAIN_HAND);
                    delayTimer = 1;
                    state = State.SWAPPING_TO_MACE;
                }
                break;

            case SWAPPING_TO_MACE:
                int bestMaceSlot = selectOptimalMaceSlot(currentTarget, currentFallDistance);
                if (bestMaceSlot != -1) {
                    mc.player.getInventory().setSelectedSlot(bestMaceSlot);
                    delayTimer = 1;
                    state = State.EXECUTING_MACE_SMASH;
                } else {
                    restoreSlotAndReset();
                }
                break;

            case EXECUTING_MACE_SMASH:
                int maceSlotToUse = selectOptimalMaceSlot(currentTarget, currentFallDistance);
                if (maceSlotToUse != -1) {
                    if (preSequenceSlot == -1) {
                        preSequenceSlot = mc.player.getInventory().getSelectedSlot();
                    }
                    mc.player.getInventory().setSelectedSlot(maceSlotToUse);
                    
                    if (mc.player.distanceTo(currentTarget) <= MAX_SWING_RANGE) {
                        mc.gameMode.attack(mc.player, currentTarget);
                        mc.player.swing(InteractionHand.MAIN_HAND);
                        lastAttackTime = System.currentTimeMillis();
                        delayTimer = 4;
                        state = State.RESETTING;
                    }
                } else {
                    restoreSlotAndReset();
                }
                break;

            case RESETTING:
                restoreSlotAndReset();
                break;
        }
    }

    private static void updateFallDistanceTracker() {
        if (mc.player == null) return;
        if (mc.player.onGround()) {
            highestY = mc.player.getY();
        } else {
            highestY = Math.max(highestY, mc.player.getY());
        }
    }

    private static int selectOptimalMaceSlot(Player target, double fallDist) {
        if (mc.player == null || target == null) return -1;

        int bestSlot = -1;
        int maxDensityScore = -1;
        int maxBreachScore = -1;

        double distance = mc.player.distanceTo(target);

        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.getItem() instanceof MaceItem) {
                int densityLevel = getEnchantmentLevel(stack, "density");
                int breachLevel = getEnchantmentLevel(stack, "breach");

                if (distance < 7.0D && breachLevel > maxBreachScore) {
                    maxBreachScore = breachLevel;
                    bestSlot = i;
                } else if (fallDist >= 7.0D && densityLevel > maxDensityScore) {
                    maxDensityScore = densityLevel;
                    bestSlot = i;
                } else if (bestSlot == -1) {
                    bestSlot = i;
                }
            }
        }
        return bestSlot;
    }

    private static int getEnchantmentLevel(ItemStack stack, String enchantmentIdentifier) {
        if (stack.isEmpty()) return 0;
        ItemEnchantments enchantments = stack.get(DataComponents.ENCHANTMENTS);
        if (enchantments == null) return 0;

        for (var entry : enchantments.entrySet()) {
            String registeredName = entry.getKey().getRegisteredName();
            if (registeredName != null && registeredName.contains(enchantmentIdentifier)) {
                return entry.getIntValue();
            }
        }
        return 0;
    }

    private static void applyBypassHumanRotation(Player target) {
        if (mc.player == null || target == null) return;

        AABB box = target.getBoundingBox();
        Vec3 center = box.getCenter();
        double aimY = box.minY + (target.getHeight() * 0.5D);
        Vec3 targetEyePos = new Vec3(center.x, aimY, center.z);

        double dx = targetEyePos.x - mc.player.getX();
        double dy = targetEyePos.y - mc.player.getEyeY();
        double dz = targetEyePos.z - mc.player.getZ();
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);

        float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        float targetPitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontalDistance)));

        float yawDelta = Mth.wrapDegrees(targetYaw - mc.player.getYRot());
        float pitchDelta = Mth.wrapDegrees(targetPitch - mc.player.getXRot());

        double sensitivity = mc.options.sensitivity().getValue();
        double f = sensitivity * 0.6D + 0.2D;
        double gcd = f * f * f * 8.0D * 0.15D;

        float interpolatedYaw = mc.player.getYRot() + (yawDelta * ROTATION_SMOOTHNESS);
        float interpolatedPitch = mc.player.getXRot() + (pitchDelta * ROTATION_SMOOTHNESS);

        float finalYaw = (float) (mc.player.getYRot() + Math.round((interpolatedYaw - mc.player.getYRot()) / gcd) * gcd);
        float finalPitch = (float) (mc.player.getXRot() + Math.round((interpolatedPitch - mc.player.getXRot()) / gcd) * gcd);

        mc.player.setYRot(finalYaw);
        mc.player.setXRot(Mth.clamp(finalPitch, -90.0F, 90.0F));
    }

    private static boolean isTargetBlockingWithShield(Player target) {
        if (target == null) return false;
        return target.isUsingItem() && target.getUseItem().getItem() instanceof ShieldItem;
    }

    private static int findAxeSlot() {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).getItem() instanceof AxeItem) {
                return i;
            }
        }
        return -1;
    }

    private static Player locateOptimalTarget(double range) {
        if (mc.level == null || mc.player == null) return null;
        Player bestTarget = null;
        double bestDistSq = Double.MAX_VALUE;

        for (Player player : mc.level.players()) {
            if (player != mc.player && player.isAlive() && !player.isSpectator()) {
                double distSq = mc.player.distanceToSqr(player);
                if (distSq <= range * range && distSq < bestDistSq) {
                    bestDistSq = distSq;
                    bestTarget = player;
                }
            }
        }
        return bestTarget;
    }

    private static void restoreSlotAndReset() {
        if (mc.player != null && preSequenceSlot >= 0 && preSequenceSlot < 9) {
            mc.player.getInventory().setSelectedSlot(preSequenceSlot);
        }
        preSequenceSlot = -1;
        state = State.IDLE;
    }

    private static void resetState() {
        currentTarget = null;
        state = State.IDLE;
        delayTimer = 0;
        preSequenceSlot = -1;
    }
}
