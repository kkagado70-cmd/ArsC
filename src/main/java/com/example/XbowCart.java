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
                    client.player.getInventory().setSelectedSlot(railSlot);
                    delayTicks = 1;
                    phase = CartPhase.RAIL_DEPLOY;
                } else {
                    resetSequence();
                }
                break;

            case RAIL_DEPLOY:
                if (targetBlockPos != null) {
                    snapAim(client, Vec3.atCenterOf(getRailPos(targetBlockPos, targetFace)));
                    client.options.keyUse.setDown(true);
                }
                delayTicks = 1;
                phase = CartPhase.CART_SELECT;
                break;

            case CART_SELECT:
                int cartSlot = ClientBase.InventoryManager.findItem(client, Items.TNT_MINECART);
                if (cartSlot != -1) {
                    client.player.getInventory().setSelectedSlot(cartSlot);
                    delayTicks = 1;
                    phase = CartPhase.CART_DEPLOY;
                } else {
                    resetSequence();
                }
                break;

            case CART_DEPLOY:
                if (targetBlockPos != null) {
                    BlockPos cartTarget = getRailPos(targetBlockPos, targetFace);
                    snapAim(client, Vec3.atCenterOf(cartTarget));
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
                    client.player.getInventory().setSelectedSlot(fireSlot);
                    delayTicks = 1;
                    phase = CartPhase.FIRE_DEPLOY;
                } else {
                    resetSequence();
                }
                break;

            case FIRE_DEPLOY:
                if (targetBlockPos != null) {
                    snapAim(client, Vec3.atCenterOf(getFirePos(client, targetBlockPos, targetFace)));
                    client.options.keyUse.setDown(true);
                }
                delayTicks = 1;
                phase = CartPhase.CROSSBOW_SELECT;
                break;

            case CROSSBOW_SELECT:
                int crossbowSlot = ClientBase.InventoryManager.findChargedCrossbow(client);
                if (crossbowSlot != -1) {
                    ClientBase.InventoryManager.selectSlot(client, crossbowSlot);
                    delayTicks = 1;
                    phase = CartPhase.CROSSBOW_DEPLOY;
                } else {
                    resetSequence();
                }
                break;

            case CROSSBOW_DEPLOY:
                if (targetBlockPos != null) {
                    BlockPos shootTarget = getRailPos(targetBlockPos, targetFace);
                    snapAim(client, Vec3.atCenterOf(shootTarget).add(0.0D, 0.25D, 0.0D));
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

    private static void snapAim(Minecraft client, Vec3 target) {
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
        targetBlockPos = null;
        targetFace = Direction.UP;
        if (Minecraft.getInstance().options != null) {
            Minecraft.getInstance().options.keyUse.setDown(false);
        }
        watchdog.disarm();
    }
}
