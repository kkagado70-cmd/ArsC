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
        RAIL, 
        CART, 
        FIRE, 
        SHOOT
    }

    private static CartPhase phase = CartPhase.INACTIVE;
    private static int delayTicks = 0;
    private static int globalCooldown = 0;
    private static Vec3 targetHitVec = null;
    private static Vec3 fireVec = null;
    private static int mouseButtonReleaseTracker = 0;
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

    public static void onTick(Minecraft client) {
        if (mouseButtonReleaseTracker > 0) {
            mouseButtonReleaseTracker--;
            if (mouseButtonReleaseTracker == 0 && client.options != null) {
                client.options.keyUse.setDown(false);
            }
        }

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

                targetHitVec = hit.getLocation();
                BlockPos basePos = hit.getBlockPos();
                Direction face = hit.getDirection();

                if (face == Direction.UP) {
                    fireVec = Vec3.atCenterOf(basePos.relative(client.player.getDirection().getOpposite()));
                } else {
                    fireVec = Vec3.atCenterOf(basePos.relative(face));
                }

                watchdog.arm();
                phase = CartPhase.RAIL;
                break;

            case RAIL:
                int railSlot = findRail(client);
                if (railSlot != -1 && targetHitVec != null) {
                    client.player.getInventory().setSelectedSlot(railSlot);
                    snapTo(client, targetHitVec);
                    mouseButtonReleaseTracker = 2;
                    client.options.keyUse.setDown(true);
                    delayTicks = 1;
                    phase = CartPhase.CART;
                } else {
                    resetSequence();
                }
                break;

            case CART:
                int cartSlot = ClientBase.InventoryManager.findItem(client, Items.TNT_MINECART);
                if (cartSlot != -1 && targetHitVec != null) {
                    client.player.getInventory().setSelectedSlot(cartSlot);
                    snapTo(client, targetHitVec);
                    mouseButtonReleaseTracker = 2;
                    client.options.keyUse.setDown(true);
                    delayTicks = 1;
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
                if (fireSlot != -1 && fireVec != null) {
                    client.player.getInventory().setSelectedSlot(fireSlot);
                    snapTo(client, fireVec);
                    mouseButtonReleaseTracker = 2;
                    client.options.keyUse.setDown(true);
                    delayTicks = 1;
                    phase = CartPhase.SHOOT;
                } else {
                    resetSequence();
                }
                break;

            case SHOOT:
                int crossbowSlot = ClientBase.InventoryManager.findChargedCrossbow(client);
                if (crossbowSlot != -1 && targetHitVec != null) {
                    client.player.getInventory().setSelectedSlot(crossbowSlot);
                    snapTo(client, targetHitVec.add(0.0D, 0.25D, 0.0D));
                    mouseButtonReleaseTracker = 2;
                    client.options.keyUse.setDown(true);
                }
                globalCooldown = 2;
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

    private static void snapTo(Minecraft client, Vec3 target) {
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

    public static void resetSequence() {
        phase = CartPhase.INACTIVE;
        delayTicks = 0;
        targetHitVec = null;
        fireVec = null;
        mouseButtonReleaseTracker = 0;
        watchdog.disarm();
    }
}
