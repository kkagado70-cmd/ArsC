package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;

public class XbowCart {
    public static boolean enabled = false;

    private enum CartPhase {
        INACTIVE, 
        RAIL_SELECT, RAIL_WAIT, RAIL_DEPLOY,
        CART_SELECT, CART_WAIT, CART_DEPLOY,
        FIRE_SELECT, FIRE_WAIT, FIRE_DEPLOY,
        CROSSBOW_SELECT, CROSSBOW_WAIT, CROSSBOW_FIRE
    }

    private static CartPhase phase = CartPhase.INACTIVE;
    private static int delayTicks = 0;
    private static int globalCooldownTicks = 0;
    private static int originalSlot = -1;
    private static int targetSlot = -1;
    private static Vec3 targetVec = null;
    private static Vec3 fireVec = null;
    private static boolean hasTriggered = false;

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

        if (!isHoldingRail(client)) {
            hasTriggered = false;
        }

        if (globalCooldownTicks > 0) {
            globalCooldownTicks--;
            return;
        }

        if (delayTicks > 0) {
            delayTicks--;
            return;
        }

        if (phase != CartPhase.INACTIVE && !isSequenceIntegrityValid(client)) {
            resetSequence();
            return;
        }

        switch (phase) {
            case INACTIVE:
                if (!isInitialActivationValid(client)) return;
                hasTriggered = true;
                originalSlot = client.player.getInventory().getSelectedSlot();
                if (client.hitResult instanceof BlockHitResult hit) {
                    targetVec = hit.getLocation();
                    BlockPos basePos = hit.getBlockPos();
                    Direction facing = client.player.getDirection();
                    BlockPos fireBlockPos = basePos.relative(facing.getOpposite());
                    fireVec = Vec3.atCenterOf(fireBlockPos);
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
                if (targetVec != null) {
                    aimAtVector(client, targetVec);
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
                if (targetVec != null) {
                    aimAtVector(client, targetVec);
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
                if (fireVec != null) {
                    aimAtVector(client, fireVec);
                    client.options.keyUse.setDown(false);
                    client.options.keyUse.setDown(true);
                }
                delayTicks = 1;
                phase = CartPhase.CROSSBOW_SELECT;
                break;

            case CROSSBOW_SELECT:
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
                if (targetVec != null) {
                    aimAtVector(client, targetVec);
                }
                client.options.keyUse.setDown(false);
                client.options.keyUse.setDown(true);
                if (originalSlot >= 0 && originalSlot < 9) {
                    client.player.getInventory().setSelectedSlot(originalSlot);
                }
                globalCooldownTicks = 8;
                resetSequence();
                break;

            default:
                resetSequence();
                break;
        }
    }

    private static boolean isInitialActivationValid(Minecraft client) {
        if (client.player == null || client.hitResult == null || hasTriggered) return false;
        if (client.hitResult.getType() != HitResult.Type.BLOCK) return false;
        if (!(client.hitResult instanceof BlockHitResult blockHit)) return false;
        if (blockHit.getDirection() != Direction.UP) return false;
        if (!isHoldingRail(client)) return false;
        return findChargedCrossbowSlot(client) != -1;
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
        targetSlot = -1;
        targetVec = null;
        fireVec = null;
        originalSlot = -1;
    }

    private static void aimAtVector(Minecraft client, Vec3 target) {
        if (client.player == null) return;
        double dx = target.x - client.player.getX();
        double dy = target.y - client.player.getEyeY();
        double dz = target.z - client.player.getZ();
        double hDist = Math.sqrt(dx * dx + dz * dz);

        float targetYaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI) - 90.0D);
        float targetPitch = (float) (-(Mth.atan2(dy, hDist) * (180.0 / Math.PI)));
        targetPitch = Mth.clamp(targetPitch, -85.0F, 85.0F);

        client.player.setYRot(targetYaw);
        client.player.setXRot(targetPitch);
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
        return -1;
    }
                        }
