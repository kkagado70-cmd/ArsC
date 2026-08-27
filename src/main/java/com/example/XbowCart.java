package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;

public class XbowCart {
    public static boolean enabled = false;

    private enum CartPhase {
        INACTIVE, 
        RAIL_SELECT, RAIL_WAIT, RAIL_DEPLOY,
        FIRE_SELECT, FIRE_WAIT, FIRE_DEPLOY,
        CART_SELECT, CART_WAIT, CART_DEPLOY,
        CROSSBOW_SELECT, CROSSBOW_WAIT, CROSSBOW_FIRE
    }

    private static CartPhase phase = CartPhase.INACTIVE;
    private static int delayTicks = 0;
    private static int globalCooldownTicks = 0;
    private static int originalSlot = -1;
    private static int targetSlot = -1;
    private static BlockPos basePos = null;
    private static BlockPos firePos = null;

    public static void toggle() {
        enabled = !enabled;
        if (!enabled) {
            resetSequence();
        }
    }

    public static boolean isAnyRail(Item item) {
        return item == Items.RAIL || 
               item == Items.POWERED_RAIL || 
               item == Items.DETECTOR_RAIL || 
               item == Items.ACTIVATOR_RAIL;
    }

    public static void onTick(Minecraft client) {
        if (!enabled || client.player == null || client.level == null) return;

        if (globalCooldownTicks > 0) {
            globalCooldownTicks--;
            return;
        }

        if (delayTicks > 0) {
            delayTicks--;
            return;
        }

        if (phase != CartPhase.INACTIVE && !isSequenceIntegrityValid(client)) {
            restoreSlot(client, originalSlot);
            resetSequence();
            return;
        }

        switch (phase) {
            case INACTIVE:
                if (!isInitialActivationValid(client)) return;
                originalSlot = client.player.getInventory().getSelectedSlot();
                if (client.hitResult instanceof BlockHitResult hit) {
                    basePos = hit.getBlockPos();
                    Direction facing = client.player.getDirection();
                    firePos = basePos.relative(facing.getOpposite());
                } else {
                    return;
                }
                phase = CartPhase.RAIL_SELECT;
                break;

            case RAIL_SELECT:
                targetSlot = findRailSlot(client);
                if (targetSlot != -1) {
                    client.player.getInventory().setSelectedSlot(targetSlot);
                    delayTicks = 1;
                    phase = CartPhase.RAIL_WAIT;
                } else {
                    resetSequence();
                }
                break;

            case RAIL_WAIT:
                delayTicks = 1;
                phase = CartPhase.RAIL_DEPLOY;
                break;

            case RAIL_DEPLOY:
                if (basePos != null) {
                    eyezingzSnapAim(client, basePos);
                    client.options.keyUse.setDown(false);
                    client.options.keyUse.setDown(true);
                }
                delayTicks = 1;
                phase = CartPhase.FIRE_SELECT;
                break;

            case FIRE_SELECT:
                targetSlot = findItemSlot(client, Items.FLINT_AND_STEEL);
                if (targetSlot == -1) {
                    targetSlot = findItemSlot(client, Items.FIRE_CHARGE);
                }
                if (targetSlot != -1) {
                    client.player.getInventory().setSelectedSlot(targetSlot);
                    delayTicks = 1;
                    phase = CartPhase.FIRE_WAIT;
                } else {
                    resetSequence();
                }
                break;

            case FIRE_WAIT:
                delayTicks = 1;
                phase = CartPhase.FIRE_DEPLOY;
                break;

            case FIRE_DEPLOY:
                if (firePos != null) {
                    eyezingzSnapAim(client, firePos);
                    client.options.keyUse.setDown(false);
                    client.options.keyUse.setDown(true);
                }
                delayTicks = 1;
                phase = CartPhase.CART_SELECT;
                break;

            case CART_SELECT:
                targetSlot = findItemSlot(client, Items.TNT_MINECART);
                if (targetSlot != -1) {
                    client.player.getInventory().setSelectedSlot(targetSlot);
                    delayTicks = 1;
                    phase = CartPhase.CART_WAIT;
                } else {
                    resetSequence();
                }
                break;

            case CART_WAIT:
                delayTicks = 1;
                phase = CartPhase.CART_DEPLOY;
                break;

            case CART_DEPLOY:
                if (basePos != null) {
                    eyezingzSnapAim(client, basePos);
                    client.options.keyUse.setDown(false);
                    client.options.keyUse.setDown(true);
                }
                delayTicks = 1;
                phase = CartPhase.CROSSBOW_SELECT;
                break;

            case CROSSBOW_SELECT:
                if (client.player.getAttackStrengthScale(0.0F) < 0.9F) {
                    delayTicks = 1;
                    return;
                }
                targetSlot = findChargedCrossbowSlot(client);
                if (targetSlot != -1) {
                    client.player.getInventory().setSelectedSlot(targetSlot);
                    delayTicks = 1;
                    phase = CartPhase.CROSSBOW_WAIT;
                } else {
                    resetSequence();
                }
                break;

            case CROSSBOW_WAIT:
                delayTicks = 1;
                phase = CartPhase.CROSSBOW_FIRE;
                break;

            case CROSSBOW_FIRE:
                if (basePos != null) {
                    eyezingzSnapAim(client, basePos);
                }
                client.options.keyUse.setDown(false);
                client.options.keyUse.setDown(true);
                restoreSlot(client, originalSlot);
                globalCooldownTicks = 3;
                resetSequence();
                break;

            default:
                resetSequence();
                break;
        }
    }

    private static boolean isInitialActivationValid(Minecraft client) {
        if (client.player == null || client.hitResult == null) return false;
        boolean lookingDown = client.hitResult instanceof BlockHitResult blockHit && blockHit.getDirection() == Direction.UP;
        boolean holdingRail = isHoldingRail(client);
        return lookingDown && holdingRail;
    }

    private static boolean isSequenceIntegrityValid(Minecraft client) {
        return enabled && client.player != null && client.level != null;
    }

    private static boolean isHoldingRail(Minecraft client) {
        if (client.player == null) return false;
        return isAnyRail(client.player.getMainHandItem().getItem());
    }

    private static void resetSequence() {
        phase = CartPhase.INACTIVE;
        delayTicks = 0;
        originalSlot = -1;
        targetSlot = -1;
        basePos = null;
        firePos = null;
    }

    private static void eyezingzSnapAim(Minecraft client, BlockPos pos) {
        if (client.player == null) return;
        Vec3 target = Vec3.atCenterOf(pos);
        double dx = target.x - client.player.getX();
        double dy = target.y - client.player.getEyeY();
        double dz = target.z - client.player.getZ();
        double hDist = Math.sqrt(dx * dx + dz * dz);

        float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        float targetPitch = (float) (-Math.toDegrees(Math.atan2(dy, hDist)));
        targetPitch = Mth.clamp(targetPitch, -85.0F, 85.0F);

        client.player.setYRot(targetYaw);
        client.player.setXRot(targetPitch);
    }

    private static void restoreSlot(Minecraft client, int slot) {
        if (slot >= 0 && slot < 9) {
            client.player.getInventory().setSelectedSlot(slot);
        }
    }

    private static int findRailSlot(Minecraft client) {
        for (int i = 0; i < 9; i++) {
            Item item = client.player.getInventory().getItem(i).getItem();
            if (isAnyRail(item)) {
                return i;
            }
        }
        return -1;
    }

    private static int findItemSlot(Minecraft client, Item targetItem) {
        for (int i = 0; i < 9; i++) {
            if (client.player.getInventory().getItem(i).getItem() == targetItem) {
                return i;
            }
        }
        return -1;
    }

    private static int findChargedCrossbowSlot(Minecraft client) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (stack.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(stack)) {
                return i;
            }
        }
        return findItemSlot(client, Items.CROSSBOW);
    }
}
