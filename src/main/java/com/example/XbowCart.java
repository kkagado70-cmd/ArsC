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
        RAIL_SELECT, RAIL_DEPLOY,
        CART_SELECT, CART_DEPLOY,
        FIRE_SELECT, FIRE_DEPLOY,
        CROSSBOW_SELECT, CROSSBOW_FIRE
    }

    private static CartPhase phase = CartPhase.INACTIVE;
    private static int delayTicks = 0;
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

    private static Vec3 getRailTarget(BlockPos pos, Direction face) {
        if (face == Direction.UP) {
            return Vec3.atCenterOf(pos);
        }
        return Vec3.atCenterOf(pos.relative(face));
    }

    private static Vec3 getCartTarget(BlockPos pos, Direction face) {
        if (face == Direction.UP) {
            return Vec3.atCenterOf(pos);
        }
        return Vec3.atCenterOf(pos.relative(face));
    }

    private static Vec3 getFireTarget(Minecraft client, BlockPos pos, Direction face) {
        if (face == Direction.UP) {
            return Vec3.atCenterOf(pos.relative(client.player.getDirection().getOpposite()));
        }
        return Vec3.atCenterOf(pos);
    }

    private static Vec3 getShootTarget(BlockPos pos, Direction face) {
        BlockPos target = face == Direction.UP ? pos : pos.relative(face);
        return Vec3.atCenterOf(target).add(0.0D, 0.2D, 0.0D);
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

        if (delayTicks > 0) {
            delayTicks--;
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
                phase = CartPhase.RAIL_SELECT;
                break;

            case RAIL_SELECT:
                int railSlot = ClientBase.InventoryManager.findRail(client);
                if (railSlot != -1) {
                    ClientBase.InventoryManager.selectSlot(client, railSlot);
                    delayTicks = 1;
                    phase = CartPhase.RAIL_DEPLOY;
                } else {
                    resetSequence();
                }
                break;

            case RAIL_DEPLOY:
                if (targetBlockPos != null) {
                    ClientBase.RotationManager.smoothTo(client, getRailTarget(targetBlockPos, targetFace), 0.85F);
                    ClientBase.InteractionManager.clickUse(client);
                }
                delayTicks = 1;
                phase = CartPhase.CART_SELECT;
                break;

            case CART_SELECT:
                int cartSlot = ClientBase.InventoryManager.findItem(client, Items.TNT_MINECART);
                if (cartSlot != -1) {
                    ClientBase.InventoryManager.selectSlot(client, cartSlot);
                    delayTicks = 1;
                    phase = CartPhase.CART_DEPLOY;
                } else {
                    resetSequence();
                }
                break;

            case CART_DEPLOY:
                if (targetBlockPos != null) {
                    BlockPos cartTarget = targetFace == Direction.UP ? targetBlockPos : targetBlockPos.relative(targetFace);
                    ClientBase.RotationManager.smoothTo(client, Vec3.atCenterOf(cartTarget), 0.85F);
                    ClientBase.InteractionManager.clickUse(client);
                }
                delayTicks = 1;
                phase = CartPhase.FIRE_SELECT;
                break;

            case FIRE_SELECT:
                int fireSlot = ClientBase.InventoryManager.findItem(client, Items.FLINT_AND_STEEL);
                if (fireSlot == -1) {
                    fireSlot = ClientBase.InventoryManager.findItem(client, Items.FIRE_CHARGE);
                }
                if (fireSlot != -1) {
                    ClientBase.InventoryManager.selectSlot(client, fireSlot);
                    delayTicks = 1;
                    phase = CartPhase.FIRE_DEPLOY;
                } else {
                    resetSequence();
                }
                break;

            case FIRE_DEPLOY:
                if (targetBlockPos != null) {
                    ClientBase.RotationManager.smoothTo(client, getFireTarget(client, targetBlockPos, targetFace), 0.85F);
                    ClientBase.InteractionManager.clickUse(client);
                }
                delayTicks = 1;
                phase = CartPhase.CROSSBOW_SELECT;
                break;

            case CROSSBOW_SELECT:
                int crossbowSlot = ClientBase.InventoryManager.findChargedCrossbow(client);
                if (crossbowSlot != -1) {
                    ClientBase.InventoryManager.selectSlot(client, crossbowSlot);
                    delayTicks = 1;
                    phase = CartPhase.CROSSBOW_FIRE;
                } else {
                    resetSequence();
                }
                break;

            case CROSSBOW_FIRE:
                if (targetBlockPos != null) {
                    BlockPos shootTarget = targetFace == Direction.UP ? targetBlockPos : targetBlockPos.relative(targetFace);
                    ClientBase.RotationManager.smoothTo(client, getShootTarget(targetBlockPos, targetFace), 0.85F);
                }
                ClientBase.InteractionManager.clickUse(client);
                globalCooldown = 4;
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
        delayTicks = 0;
        targetBlockPos = null;
        targetFace = Direction.UP;
        watchdog.disarm();
    }
}
