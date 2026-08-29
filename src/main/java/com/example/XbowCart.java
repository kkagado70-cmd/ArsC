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

import java.util.Random;

public class XbowCart {
    public static boolean enabled = false;
    private static final Random random = new Random();

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

    private static float targetYawCache = 0.0f;
    private static float targetPitchCache = 0.0f;

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
            } else {
                updateAimDirectly(client);
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
                    delayTicks = 2;
                    phase = CartPhase.RAIL_DEPLOY;
                } else {
                    resetSequence();
                }
                break;

            case RAIL_DEPLOY:
                if (targetBlockPos != null) {
                    calculateInstantAim(client, Vec3.atCenterOf(getRailPos(targetBlockPos, targetFace)));
                    updateAimDirectly(client);
                    client.options.keyUse.setDown(true);
                }
                delayTicks = 2;
                phase = CartPhase.CART_SELECT;
                break;

            case CART_SELECT:
                int cartSlot = ClientBase.InventoryManager.findItem(client, Items.TNT_MINECART);
                if (cartSlot != -1) {
                    selectHotbarSlot(client, cartSlot);
                    delayTicks = 2;
                    phase = CartPhase.CART_DEPLOY;
                } else {
                    resetSequence();
                }
                break;

            case CART_DEPLOY:
                if (targetBlockPos != null) {
                    calculateInstantAim(client, Vec3.atCenterOf(getRailPos(targetBlockPos, targetFace)));
                    updateAimDirectly(client);
                    client.options.keyUse.setDown(true);
                }
                delayTicks = 2;
                phase = CartPhase.FIRE_SELECT;
                break;

            case FIRE_SELECT:
                int fireSlot = ClientBase.InventoryManager.findItem(client, Items.FLINT_AND_STEEL);
                if (fireSlot == -1) {
                    fireSlot = ClientBase.InventoryManager.findItem(client, Items.FIRE_CHARGE);
                }
                if (fireSlot != -1) {
                    selectHotbarSlot(client, fireSlot);
                    delayTicks = 2;
                    phase = CartPhase.FIRE_DEPLOY;
                } else {
                    resetSequence();
                }
                break;

            case FIRE_DEPLOY:
                if (targetBlockPos != null) {
                    calculateInstantAim(client, Vec3.atCenterOf(getFirePos(client, targetBlockPos, targetFace)));
                    updateAimDirectly(client);
                    client.options.keyUse.setDown(true);
                }
                delayTicks = 2;
                phase = CartPhase.CROSSBOW_SELECT;
                break;

            case CROSSBOW_SELECT:
                int crossbowSlot = ClientBase.InventoryManager.findChargedCrossbow(client);
                if (crossbowSlot != -1) {
                    selectHotbarSlot(client, crossbowSlot);
                    delayTicks = 2;
                    phase = CartPhase.CROSSBOW_DEPLOY;
                } else {
                    resetSequence();
                }
                break;

            case CROSSBOW_DEPLOY:
                if (targetBlockPos != null && client.player.getAttackStrengthScale(0.0F) >= 0.9F) {
                    calculateInstantAim(client, Vec3.atCenterOf(getRailPos(targetBlockPos, targetFace)).add(0.0D, 0.1D, 0.0D));
                    updateAimDirectly(client);
                    client.options.keyUse.setDown(true);
                }
                delayTicks = 2;
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

    private static void calculateInstantAim(Minecraft client, Vec3 target) {
        if (client.player == null) return;
        double dx = target.x - client.player.getX();
        double dy = target.y - client.player.getEyeY();
        double dz = target.z - client.player.getZ();
        double hDist = Math.sqrt(dx * dx + dz * dz);

        targetYawCache = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI) - 90.0D);
        targetPitchCache = (float) (-(Mth.atan2(dy, hDist) * (180.0 / Math.PI)));
        targetPitchCache = Mth.clamp(targetPitchCache, -90.0F, 90.0F);
    }

    private static void updateAimDirectly(Minecraft client) {
        if (client.player == null) return;
        
        float currentYaw = client.player.getYRot();
        float currentPitch = client.player.getXRot();

        float yawDiff = targetYawCache - currentYaw;
        while (yawDiff < -180.0f) yawDiff += 360.0f;
        while (yawDiff > 180.0f) yawDiff -= 360.0f;

        float newYaw = currentYaw + yawDiff;
        float newPitch = targetPitchCache;

        client.player.setYRot(newYaw);
        client.player.setXRot(newPitch);

        double sens = client.options.sensitivity().get() * 0.6D + 0.2D;
        double dYaw = yawDiff / (sens * 0.15D);
        double dPitch = (newPitch - currentPitch) / (sens * 0.15D);
        
        client.player.turn(dYaw, -dPitch);
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
