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
        RAIL_SELECT, RAIL_DEPLOY,
        CART_SELECT, CART_DEPLOY,
        FIRE_SELECT, FIRE_DEPLOY,
        CROSSBOW_SELECT, CROSSBOW_DEPLOY
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

    private static BlockPos getRailPos(BlockPos pos, Direction face) {
        if (face == Direction.UP) {
            return pos;
        }
        return pos.above();
    }

    private static BlockPos getFirePos(Minecraft client, BlockPos pos, Direction face) {
        if (face == Direction.UP) {
            return pos.relative(client.player.getDirection().getOpposite());
        }
        return pos;
    }

    public static void onTick(Minecraft client) {
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
            if (delayTicks == 0) {
                client.options.keyUse.setDown(false);
            }
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
                int railSlot = findRail(client);
                if (railSlot != -1) {
                    selectHotbarSlot(client, railSlot);
                    delayTicks = 1;
                    phase = CartPhase.RAIL_DEPLOY;
                } else {
                    resetSequence();
                }
                break;

            case RAIL_DEPLOY:
                if (targetBlockPos != null) {
                    snapAimToBlock(client, getRailPos(targetBlockPos, targetFace));
                    client.options.keyUse.setDown(true);
                }
                delayTicks = 1;
                phase = CartPhase.CART_SELECT;
                break;

            case CART_SELECT:
                int cartSlot = ClientBase.InventoryManager.findItem(client, Items.TNT_MINECART);
                if (cartSlot != -1) {
                    selectHotbarSlot(client, cartSlot);
                    delayTicks = 1;
                    phase = CartPhase.CART_DEPLOY;
                } else {
                    resetSequence();
                }
                break;

            case CART_DEPLOY:
                if (targetBlockPos != null) {
                    snapAimToBlock(client, getRailPos(targetBlockPos, targetFace));
                    client.options.keyUse.setDown(true);
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
                    selectHotbarSlot(client, fireSlot);
                    delayTicks = 1;
                    phase = CartPhase.FIRE_DEPLOY;
                } else {
                    resetSequence();
                }
                break;

            case FIRE_DEPLOY:
                if (targetBlockPos != null) {
                    snapAimToBlock(client, getFirePos(client, targetBlockPos, targetFace));
                    client.options.keyUse.setDown(true);
                }
                delayTicks = 1;
                phase = CartPhase.CROSSBOW_SELECT;
                break;

            case CROSSBOW_SELECT:
                int crossbowSlot = ClientBase.InventoryManager.findChargedCrossbow(client);
                if (crossbowSlot != -1) {
                    selectHotbarSlot(client, crossbowSlot);
                    delayTicks = 1;
                    phase = CartPhase.CROSSBOW_DEPLOY;
                } else {
                    resetSequence();
                }
                break;

            case CROSSBOW_DEPLOY:
                if (targetBlockPos != null) {
                    snapAimToBlock(client, getRailPos(targetBlockPos, targetFace));
                    client.options.keyUse.setDown(true);
                }
                delayTicks = 1;
                globalCooldown = 4;
                resetSequence();
                break;

            default:
                resetSequence();
                break;
        }
    }

    private static int findRail(Minecraft client) {
        if (client.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            Item item = client.player.getInventory().getItem(i).getItem();
            if (isAnyRail(item)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isHoldingRail(Minecraft client) {
        if (client.player == null) return false;
        return isAnyRail(client.player.getMainHandItem().getItem());
    }

    private static void selectHotbarSlot(Minecraft client, int slot) {
        if (slot < 0 || slot > 8) return;
        for (int i = 0; i < 9; i++) {
            client.options.keyHotbarSlots[i].setDown(i == slot);
        }
        client.player.getInventory().setSelectedSlot(slot);
    }

    private static void snapAimToBlock(Minecraft client, BlockPos targetPos) {
        if (client.player == null) return;
        
        double eyesX = client.player.getX();
        double eyesY = client.player.getEyeY();
        double eyesZ = client.player.getZ();

        double targetX = targetPos.getX() + 0.5D;
        double targetY = targetPos.getY() + 0.5D;
        double targetZ = targetPos.getZ() + 0.5D;

        double diffX = targetX - eyesX;
        double diffY = targetY - eyesY;
        double diffZ = targetZ - eyesZ;

        double diffXZ = Math.sqrt(diffX * diffX + diffZ * diffZ);

        float yaw = (float) (Mth.atan2(diffZ, diffX) * (180.0 / Math.PI)) - 90.0F;
        float pitch = (float) (-(Mth.atan2(diffY, diffXZ) * (180.0 / Math.PI)));

        client.player.setYRot(yaw);
        client.player.setXRot(pitch);
    }

    public static void resetSequence() {
        phase = CartPhase.INACTIVE;
        delayTicks = 0;
        targetBlockPos = null;
        targetFace = Direction.UP;
        if (Minecraft.getInstance().options != null) {
            Minecraft.getInstance().options.keyUse.setDown(false);
        }
        watchdog.disarm();
    }
    }
