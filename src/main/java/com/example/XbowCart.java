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

public class XbowCart {
    public static boolean enabled = false;

    private enum CartPhase {
        INACTIVE, 
        RAIL, 
        CART, 
        FIRE, 
        SHOOT
    }

    private static CartPhase phase = CartPhase.INACTIVE;
    private static int globalCooldown = 0;
    private static BlockPos targetBlockPos = null;
    private static Direction targetFace = Direction.UP;
    private static final ClientBase.SafetyWatchdog watchdog = new ClientBase.SafetyWatchdog();

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

    private static BlockPos getPlacementPos(BlockPos pos, Direction face) {
        if (face == Direction.UP) {
            return pos;
        }
        return pos.above();
    }

    private static BlockPos getFirePosition(Minecraft client, BlockPos pos, Direction face) {
        if (face == Direction.UP) {
            return pos.relative(client.player.getDirection().getOpposite());
        }
        return pos;
    }

    private static Vec3 getTargetVec(BlockPos pos, Direction face, double yOffset) {
        BlockPos p = getPlacementPos(pos, face);
        return Vec3.atCenterOf(p).add(0.0D, yOffset, 0.0D);
    }

    private static Vec3 getFireVec(Minecraft client, BlockPos pos, Direction face) {
        BlockPos f = getFirePosition(client, pos, face);
        if (face == Direction.UP) {
            return Vec3.atCenterOf(f);
        }
        return Vec3.atCenterOf(f).add(0.0D, 0.5D, 0.0D);
    }

    public static void onTick(Minecraft client) {
        ClientBase.InteractionManager.update(client);

        if (!enabled || client.player == null || client.level == null) return;

        if (globalCooldown > 0) {
            globalCooldown--;
            return;
        }

        if (watchdog.isTimedOut()) {
            resetSequence();
            return;
        }

        if (phase != CartPhase.INACTIVE && (!enabled || client.player == null || client.level == null)) {
            resetSequence();
            return;
        }

        switch (phase) {
            case INACTIVE:
                BlockHitResult hit = ClientBase.RaycastManager.getValidHit(client);
                if (hit == null || !isHoldingRail(client) || ClientBase.InventoryManager.findChargedCrossbow(client) == -1) {
                    return;
                }

                targetBlockPos = hit.getBlockPos();
                targetFace = hit.getDirection();

                watchdog.arm();
                phase = CartPhase.RAIL;
                break;

            case RAIL:
                int railSlot = ClientBase.InventoryManager.findRail(client);
                if (railSlot != -1 && targetBlockPos != null) {
                    ClientBase.InventoryManager.selectSlot(client, railSlot);
                    ClientBase.RotationManager.smoothTo(client, getTargetVec(targetBlockPos, targetFace, 0.0D), 0.98F);
                    ClientBase.InteractionManager.clickUse(client);
                    phase = CartPhase.CART;
                } else {
                    resetSequence();
                }
                break;

            case CART:
                int cartSlot = ClientBase.InventoryManager.findItem(client, Items.TNT_MINECART);
                if (cartSlot != -1 && targetBlockPos != null) {
                    ClientBase.InventoryManager.selectSlot(client, cartSlot);
                    BlockPos cartTarget = targetFace == Direction.UP ? targetBlockPos : targetBlockPos.relative(targetFace);
                    ClientBase.RotationManager.smoothTo(client, Vec3.atCenterOf(cartTarget), 0.98F);
                    ClientBase.InteractionManager.clickUse(client);
                    phase = CartPhase.FIRE;
                } else {
                    resetSequence();
                }
                break;

            case FIRE:
                int fireSlot = ClientBase.InventoryManager.findItem(client, Items.FLINT_AND_STEEL);
                if (fireSlot == -1) {
                    fireSlot = ClientBase.InventoryManager.findItem(client, Items.FIRE_CHARGE);
                }
                if (fireSlot != -1 && targetBlockPos != null) {
                    ClientBase.InventoryManager.selectSlot(client, fireSlot);
                    ClientBase.RotationManager.smoothTo(client, getFireVec(client, targetBlockPos, targetFace), 0.98F);
                    ClientBase.InteractionManager.clickUse(client);
                    phase = CartPhase.SHOOT;
                } else {
                    resetSequence();
                }
                break;

            case SHOOT:
                int crossbowSlot = ClientBase.InventoryManager.findChargedCrossbow(client);
                if (crossbowSlot != -1 && targetBlockPos != null) {
                    ClientBase.InventoryManager.selectSlot(client, crossbowSlot);
                    ClientBase.RotationManager.smoothTo(client, getTargetVec(targetBlockPos, targetFace, 0.35D), 0.98F);
                    ClientBase.InteractionManager.clickUse(client);
                }
                globalCooldown = 3;
                resetSequence();
                break;

            default:
                resetSequence();
                break;
        }
    }

    private static boolean isHoldingRail(Minecraft client) {
        if (client.player == null) return false;
        return isAnyRail(client.player.getMainHandItem().getItem());
    }

    public static void resetSequence() {
        phase = CartPhase.INACTIVE;
        targetBlockPos = null;
        targetFace = Direction.UP;
        watchdog.disarm();
    }
}
