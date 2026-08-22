package com.example;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Item;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;

import java.lang.reflect.Field;

public class XbowCart implements ClientModInitializer {
    private static final Minecraft mc = Minecraft.getInstance();
    private static boolean active = false;
    private static int stage = 0;
    private static int tickCounter = 0;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (mc.player == null || mc.level == null) return;

            if (!active) {
                boolean holdingRail = mc.player.getMainHandItem().is(Items.RAIL);
                HitResult hit = mc.hitResult;
                
                if (holdingRail && hit != null && hit.getType() == HitResult.Type.BLOCK) {
                    BlockHitResult blockHit = (BlockHitResult) hit;
                    if (blockHit.getDirection() == Direction.UP && mc.player.getXRot() > 40.0F) {
                        active = true;
                        stage = 0;
                        tickCounter = 0;
                    }
                }
            }

            if (!active) return;

            if (tickCounter > 0) {
                tickCounter--;
                return;
            }

            HitResult hit = mc.hitResult;
            if (hit == null || hit.getType() != HitResult.Type.BLOCK) {
                active = false;
                return;
            }

            BlockHitResult blockHit = (BlockHitResult) hit;
            BlockPos targetPos = blockHit.getBlockPos().relative(Direction.UP);
            Vec3 targetVec = Vec3.atCenterOf(targetPos);

            if (mc.player.distanceToSqr(targetVec) > 8.7D) {
                active = false;
                return;
            }

            applySmoothLookAt(targetVec);

            switch (stage) {
                case 0:
                    if (selectItem(Items.RAIL)) {
                        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(targetVec, Direction.UP, blockHit.getBlockPos(), false));
                        mc.player.swing(InteractionHand.MAIN_HAND);
                        tickCounter = 2;
                        stage = 1;
                    } else {
                        active = false;
                    }
                    break;
                case 1:
                    if (selectItem(Items.TNT_MINECART)) {
                        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(targetVec, Direction.UP, blockHit.getBlockPos(), false));
                        mc.player.swing(InteractionHand.MAIN_HAND);
                        tickCounter = 2;
                        stage = 2;
                    } else {
                        active = false;
                    }
                    break;
                case 2:
                    if (selectItem(Items.FLINT_AND_STEEL)) {
                        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(targetVec, Direction.UP, blockHit.getBlockPos(), false));
                        mc.player.swing(InteractionHand.MAIN_HAND);
                        tickCounter = 2;
                        stage = 3;
                    } else {
                        active = false;
                    }
                    break;
                case 3:
                    if (selectItem(Items.CROSSBOW)) {
                        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                        tickCounter = 4;
                        stage = 4;
                    } else {
                        active = false;
                    }
                    break;
                case 4:
                    if (selectItem(Items.CROSSBOW)) {
                        mc.gameMode.releaseUsingItem(mc.player);
                        mc.player.swing(InteractionHand.MAIN_HAND);
                        active = false;
                    }
                    break;
            }
        });
    }

    private static void applySmoothLookAt(Vec3 target) {
        double dx = target.x - mc.player.getX();
        double dy = target.y - mc.player.getEyeY();
        double dz = target.z - mc.player.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);

        float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        float targetPitch = (float) (-Math.toDegrees(Math.atan2(dy, dist)));

        float currentYaw = mc.player.getYaw();
        float currentPitch = mc.player.getXRot();

        float yawDiff = Mth.wrapDegrees(targetYaw - currentYaw);
        float pitchDiff = Mth.wrapDegrees(targetPitch - currentPitch);

        mc.player.setYaw(currentYaw + yawDiff * 0.35F);
        mc.player.setXRot(currentPitch + pitchDiff * 0.35F);
    }

    private static boolean selectItem(Item item) {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).is(item)) {
                setHotbarSlot(i);
                return true;
            }
        }
        return false;
    }

    private static void setHotbarSlot(int slot) {
        if (mc.player == null || slot < 0 || slot > 8) return;
        try {
            mc.player.getInventory().selected = slot;
        } catch (Throwable t) {
            try {
                Field field = Inventory.class.getDeclaredField("selected");
                field.setAccessible(true);
                field.setInt(mc.player.getInventory(), slot);
            } catch (Exception ignored) {}
        }
    }
                            }
