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
    private static BlockPos targetBlockPos = null;
    private static Direction targetFace = Direction.UP;
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

                targetBlockPos = hit.getBlockPos();
                targetFace = hit.getDirection();

                watchdog.arm();
                phase = CartPhase.RAIL;
                break;

            case RAIL:
                int railSlot = findRail(client);
                if (railSlot != -1 && targetBlockPos != null) {
                    client.player.getInventory().setSelectedSlot(railSlot);
                    BlockPos railTarget = getRailPos(targetBlockPos, targetFace);
                    lightningAim(client, Vec3.atCenterOf(railTarget));
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
                if (cartSlot != -1 && targetBlockPos != null) {
                    client.player.getInventory().setSelectedSlot(cartSlot);
                    BlockPos cartTarget = getRailPos(targetBlockPos, targetFace);
                    lightningAim(client, Vec3.atCenterOf(cartTarget));
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
                if (fireSlot != -1 && targetBlockPos != null) {
                    client.player.getInventory().setSelectedSlot(fireSlot);
                    BlockPos fireTarget = getFirePos(client, targetBlockPos, targetFace);
                    lightningAim(client, Vec3.atCenterOf(fireTarget));
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
                if (crossbowSlot != -1 && targetBlockPos != null) {
                    ClientBase.InventoryManager.selectSlot(client, crossbowSlot);
                    BlockPos shootTarget = getRailPos(targetBlockPos, targetFace);
                    lightningAim(client, Vec3.atCenterOf(shootTarget).add(0.0D, 0.25D, 0.0D));
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

    private static void lightningAim(Minecraft client, Vec3 target) {
        if (client.player == null) return;
        double dx = target.x - client.player.getX();
        double dy = target.y - client.player.getEyeY();
        double dz = target.z - client.player.getZ();
        double hDist = Math.sqrt(dx * dx + dz * dz);

        float targetYaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI) - 90.0D);
        float targetPitch = (float) (-(Mth.atan2(dy, hDist) * (180.0 / Math.PI)));
        targetPitch = Mth.clamp(targetPitch, -85.0F, 85.0F);

        float currentYaw = client.player.getYRot();
        float currentPitch = client.player.getXRot();

        float yawDiff = Mth.wrapDegrees(targetYaw - currentYaw);
        float pitchDiff = targetPitch - currentPitch;

        client.player.setYRot(currentYaw + yawDiff * 0.95F);
        client.player.setXRot(currentPitch + pitchDiff * 0.95F);
    }

    public static void resetSequence() {
        phase = CartPhase.INACTIVE;
        delayTicks = 0;
        targetBlockPos = null;
        targetFace = Direction.UP;
        mouseButtonReleaseTracker = 0;
        watchdog.disarm();
    }
}
