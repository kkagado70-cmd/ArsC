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

    private enum CartPhase {
        INACTIVE,
        RAIL_SELECT, RAIL_WAIT, RAIL_DEPLOY, RAIL_CHECK,
        FIRE_SELECT, FIRE_WAIT, FIRE_DEPLOY, FIRE_CHECK,
        CART_SELECT, CART_WAIT, CART_DEPLOY, CART_CHECK,
        CROSSBOW_SELECT, CROSSBOW_WAIT, CROSSBOW_FIRE
    }

    private static CartPhase phase = CartPhase.INACTIVE;
    private static int delayTicks = 0;
    private static int globalCooldown = 0;
    private static int originalSlot = -1;
    private static int targetSlot = -1;
    private static int retryCount = 0;
    private static int mouseRelease = 0;
    private static BlockPos basePos = null;
    private static final Random random = new Random();
    private static final Minecraft mc = Minecraft.getInstance();

    public static void toggle() {
        enabled = !enabled;
        if (!enabled) reset();
    }

    public static boolean isAnyRail(Item item) {
        return item == Items.RAIL || item == Items.POWERED_RAIL ||
               item == Items.DETECTOR_RAIL || item == Items.ACTIVATOR_RAIL;
    }

    public static void onTick(Minecraft client) {
        if (!enabled || client.player == null || client.level == null) return;

        if (mouseRelease > 0) {
            mouseRelease--;
            if (mouseRelease == 0) client.options.keyUse.setDown(false);
        }

        if (globalCooldown > 0) {
            globalCooldown--;
            return;
        }

        if (delayTicks > 0) {
            delayTicks--;
            return;
        }

        if (phase != CartPhase.INACTIVE && !isValid(client)) {
            restoreSlot(client, originalSlot);
            reset();
            return;
        }

        switch (phase) {
            case INACTIVE:
                if (!canActivate(client)) return;
                originalSlot = client.player.getInventory().getSelectedSlot();
                if (client.hitResult instanceof BlockHitResult hit) {
                    basePos = hit.getBlockPos();
                } else {
                    basePos = client.player.blockPosition().below();
                }
                retryCount = 0;
                phase = CartPhase.RAIL_SELECT;
                break;

            case RAIL_SELECT:
                targetSlot = findRailSlot(client);
                if (targetSlot != -1 && basePos != null) {
                    client.options.keyHotbarSlots[targetSlot].setDown(true);
                    aimHT1(client, basePos);
                    delayTicks = 2;
                    phase = CartPhase.RAIL_WAIT;
                } else {
                    retry(client, CartPhase.RAIL_SELECT);
                }
                break;

            case RAIL_WAIT:
                if (targetSlot != -1) {
                    client.options.keyHotbarSlots[targetSlot].setDown(false);
                }
                delayTicks = 2;
                phase = CartPhase.RAIL_DEPLOY;
                break;

            case RAIL_DEPLOY:
                if (basePos != null) {
                    aimHT1(client, basePos);
                    mouseRelease = 2;
                    client.options.keyUse.setDown(true);
                }
                delayTicks = 3;
                phase = CartPhase.RAIL_CHECK;
                break;

            case RAIL_CHECK:
                if (wasPlaced(client, basePos)) {
                    retryCount = 0;
                    phase = CartPhase.FIRE_SELECT;
                } else {
                    retry(client, CartPhase.RAIL_SELECT);
                }
                break;

            case FIRE_SELECT:
                targetSlot = findItemSlot(client, Items.FLINT_AND_STEEL);
                if (targetSlot == -1) {
                    targetSlot = findItemSlot(client, Items.FIRE_CHARGE);
                }
                if (targetSlot != -1 && basePos != null) {
                    client.options.keyHotbarSlots[targetSlot].setDown(true);
                    aimHT1(client, basePos.above());
                    delayTicks = 2;
                    phase = CartPhase.FIRE_WAIT;
                } else {
                    retry(client, CartPhase.FIRE_SELECT);
                }
                break;

            case FIRE_WAIT:
                if (targetSlot != -1) {
                    client.options.keyHotbarSlots[targetSlot].setDown(false);
                }
                delayTicks = 2;
                phase = CartPhase.FIRE_DEPLOY;
                break;

            case FIRE_DEPLOY:
                if (basePos != null) {
                    aimHT1(client, basePos.above());
                    mouseRelease = 2;
                    client.options.keyUse.setDown(true);
                }
                delayTicks = 3;
                phase = CartPhase.FIRE_CHECK;
                break;

            case FIRE_CHECK:
                if (basePos != null && wasPlaced(client, basePos.above())) {
                    retryCount = 0;
                    phase = CartPhase.CART_SELECT;
                } else {
                    retry(client, CartPhase.FIRE_SELECT);
                }
                break;

            case CART_SELECT:
                targetSlot = findItemSlot(client, Items.TNT_MINECART);
                if (targetSlot != -1 && basePos != null) {
                    client.options.keyHotbarSlots[targetSlot].setDown(true);
                    aimHT1(client, basePos.above(2));
                    delayTicks = 2;
                    phase = CartPhase.CART_WAIT;
                } else {
                    retry(client, CartPhase.CART_SELECT);
                }
                break;

            case CART_WAIT:
                if (targetSlot != -1) {
                    client.options.keyHotbarSlots[targetSlot].setDown(false);
                }
                delayTicks = 2;
                phase = CartPhase.CART_DEPLOY;
                break;

            case CART_DEPLOY:
                if (basePos != null) {
                    aimHT1(client, basePos.above(2));
                    mouseRelease = 2;
                    client.options.keyUse.setDown(true);
                }
                delayTicks = 3;
                phase = CartPhase.CART_CHECK;
                break;

            case CART_CHECK:
                if (basePos != null && wasPlaced(client, basePos.above(2))) {
                    retryCount = 0;
                    phase = CartPhase.CROSSBOW_SELECT;
                } else {
                    retry(client, CartPhase.CART_SELECT);
                }
                break;

            case CROSSBOW_SELECT:
                if (client.player.getAttackStrengthScale(0.0F) < 0.9F) {
                    delayTicks = 1;
                    return;
                }
                targetSlot = findChargedCrossbowSlot(client);
                if (targetSlot != -1) {
                    client.options.keyHotbarSlots[targetSlot].setDown(true);
                    delayTicks = 2;
                    phase = CartPhase.CROSSBOW_WAIT;
                } else {
                    retry(client, CartPhase.CROSSBOW_SELECT);
                }
                break;

            case CROSSBOW_WAIT:
                if (targetSlot != -1) {
                    client.options.keyHotbarSlots[targetSlot].setDown(false);
                }
                delayTicks = 2;
                phase = CartPhase.CROSSBOW_FIRE;
                break;

            case CROSSBOW_FIRE:
                mouseRelease = 2;
                client.options.keyUse.setDown(true);
                restoreSlot(client, originalSlot);
                globalCooldown = 8;
                reset();
                break;

            default:
                reset();
                break;
        }
    }

    private static boolean canActivate(Minecraft client) {
        if (client.player == null || client.hitResult == null) return false;
        boolean lookingDown = client.hitResult instanceof BlockHitResult blockHit && blockHit.getDirection() == Direction.UP;
        boolean holdingRail = isHoldingRail(client);
        return lookingDown && holdingRail;
    }

    private static boolean isValid(Minecraft client) {
        return enabled && client.player != null && client.level != null;
    }

    private static boolean isHoldingRail(Minecraft client) {
        if (client.player == null) return false;
        return isAnyRail(client.player.getMainHandItem().getItem());
    }

    private static boolean wasPlaced(Minecraft client, BlockPos pos) {
        return client.level != null && !client.level.getBlockState(pos).isAir();
    }

    private static void retry(Minecraft client, CartPhase fallbackPhase) {
        retryCount++;
        if (retryCount > 3) {
            restoreSlot(client, originalSlot);
            reset();
        } else {
            phase = fallbackPhase;
            delayTicks = 4;
        }
    }

    private static void reset() {
        phase = CartPhase.INACTIVE;
        delayTicks = 0;
        originalSlot = -1;
        targetSlot = -1;
        retryCount = 0;
        basePos = null;
        mouseRelease = 0;
    }

    private static void aimHT1(Minecraft client, BlockPos pos) {
        if (client.player == null) return;
        Vec3 target = Vec3.atCenterOf(pos);
        Vec3 eye = client.player.getEyePosition();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double hDist = Math.sqrt(dx * dx + dz * dz);

        float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        float targetPitch = (float) (-Math.toDegrees(Math.atan2(dy, hDist)));
        targetPitch = Mth.clamp(targetPitch, -30.0F, 30.0F);

        float currentYaw = client.player.getYRot();
        float currentPitch = client.player.getXRot();

        float yawError = Mth.wrapDegrees(targetYaw - currentYaw);
        float pitchError = targetPitch - currentPitch;

        float overshootYaw = yawError * (0.10f + random.nextFloat() * 0.05f);
        float overshootPitch = pitchError * (0.10f + random.nextFloat() * 0.05f);

        float speed = 8.0f + random.nextFloat() * 4.0f;

        float jitterYaw = (float)(random.nextGaussian() * 0.03);
        float jitterPitch = (float)(random.nextGaussian() * 0.02);

        float stepYaw = Math.max(-speed, Math.min(speed, yawError * 0.70f + overshootYaw * 0.30f)) + jitterYaw;
        float stepPitch = Math.max(-speed * 0.6f, Math.min(speed * 0.6f, pitchError * 0.70f + overshootPitch * 0.30f)) + jitterPitch;

        client.player.setYRot(currentYaw + stepYaw);
        client.player.setXRot(currentPitch + stepPitch);
    }

    private static void selectSlot(Minecraft client, int slot) {
        if (client.player == null || slot < 0 || slot > 8) return;
        client.options.keyHotbarSlots[slot].setDown(true);
        client.options.keyHotbarSlots[slot].setDown(false);
    }

    private static void restoreSlot(Minecraft client, int slot) {
        if (slot >= 0 && slot < 9) {
            selectSlot(client, slot);
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
