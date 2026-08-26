package com.example;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

import java.util.Random;

public class XbowCart implements ClientModInitializer {
    private static final Minecraft mc = Minecraft.getInstance();
    private static KeyMapping toggleKey;
    public static boolean enabled = false;

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.xbowcart.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_X, KeyMapping.Category.MISC
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (mc.player == null || mc.level == null) return;
            while (toggleKey.consumeClick()) {
                enabled = !enabled;
                XbowCartMasterOrchestrator.getInstance().hardReset();
            }
            if (enabled) {
                onTick(client);
            }
        });
    }

    public static void toggle() {
        enabled = !enabled;
        XbowCartMasterOrchestrator.getInstance().hardReset();
    }

    public static void onTick() { onTick(Minecraft.getInstance()); }
    public static void onTick(Minecraft client) {
        if (client.player == null || client.level == null || !enabled) return;
        XbowCartMasterOrchestrator.getInstance().onTick(client);
    }

    public static class XbowCartMasterOrchestrator {
        private static final XbowCartMasterOrchestrator INSTANCE = new XbowCartMasterOrchestrator();
        private final CartSecurityConfiguration config = new CartSecurityConfiguration();
        private final HumanizedEntropyEngine entropy = new HumanizedEntropyEngine();
        private final HotbarKeySimulator keySimulator = new HotbarKeySimulator();
        private final TowerGeometryResolver geometry = new TowerGeometryResolver();
        private final CartStatePipeline pipeline = new CartStatePipeline();

        public static XbowCartMasterOrchestrator getInstance() { return INSTANCE; }

        public void onTick(Minecraft client) {
            if (client.player == null || client.level == null) return;
            config.audit();
            pipeline.executePipeline(client, config, entropy, keySimulator, geometry);
        }

        public void hardReset() { pipeline.abortPipeline(); }
    }

    public static class CartSecurityConfiguration {
        private final double maxPlacementReach = 6.0D;
        private final int baseActionDelay = 2;
        private final boolean antiCheatShield = true;
        private final double raycastTolerance = 0.5D;

        public void audit() {}

        public double getMaxPlacementReach() { return maxPlacementReach; }
        public int getBaseActionDelay() { return baseActionDelay; }
        public boolean isAntiCheatShield() { return antiCheatShield; }
        public double getRaycastTolerance() { return raycastTolerance; }
    }

    public static class HumanizedEntropyEngine {
        private final Random gaussianRandom = new Random();

        public int getStochasticDelay(int base) {
            return base + gaussianRandom.nextInt(2);
        }

        public float getJitterOffset(float scale) {
            return (float) (gaussianRandom.nextGaussian() * scale);
        }

        public double getGaussianNoise(double variance) {
            return gaussianRandom.nextGaussian() * variance;
        }
    }

    public static class HotbarKeySimulator {
        public boolean pressNumberKeyForSlot(Minecraft client, Item targetItem) {
            for (int slotIdx = 0; slotIdx < 9; slotIdx++) {
                ItemStack stack = client.player.getInventory().getItem(slotIdx);
                if (stack.getItem() == targetItem) {
                    client.player.getInventory().setSelectedSlot(slotIdx);
                    client.options.keyHotbarSlots[slotIdx].setDown(true);
                    client.options.keyHotbarSlots[slotIdx].setDown(false);
                    return true;
                }
            }
            return false;
        }

        public boolean pressNumberKeyForAnyRail(Minecraft client) {
            for (int slotIdx = 0; slotIdx < 9; slotIdx++) {
                Item item = client.player.getInventory().getItem(slotIdx).getItem();
                if (isSupportedRail(item)) {
                    client.player.getInventory().setSelectedSlot(slotIdx);
                    client.options.keyHotbarSlots[slotIdx].setDown(true);
                    client.options.keyHotbarSlots[slotIdx].setDown(false);
                    return true;
                }
            }
            return false;
        }

        public boolean pressNumberKeyForChargedOrAnyCrossbow(Minecraft client) {
            for (int slotIdx = 0; slotIdx < 9; slotIdx++) {
                ItemStack stack = client.player.getInventory().getItem(slotIdx);
                if (stack.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(stack)) {
                    client.player.getInventory().setSelectedSlot(slotIdx);
                    client.options.keyHotbarSlots[slotIdx].setDown(true);
                    client.options.keyHotbarSlots[slotIdx].setDown(false);
                    return true;
                }
            }
            return pressNumberKeyForSlot(client, Items.CROSSBOW);
        }

        private boolean isSupportedRail(Item item) {
            return item == Items.RAIL || item == Items.POWERED_RAIL || item == Items.DETECTOR_RAIL || item == Items.ACTIVATOR_RAIL;
        }
    }

    public static class TowerDataModel {
        private final BlockPos cartTarget;
        private final BlockPos fireTarget;
        private final Direction hitFaceDirection;

        public TowerDataModel(BlockPos cartTarget, BlockPos fireTarget, Direction hitFaceDirection) {
            this.cartTarget = cartTarget;
            this.fireTarget = fireTarget;
            this.hitFaceDirection = hitFaceDirection;
        }

        public BlockPos getCartTarget() { return cartTarget; }
        public BlockPos getFireTarget() { return fireTarget; }
        public Direction getHitFaceDirection() { return hitFaceDirection; }
    }

    public static class TowerGeometryResolver {
        public TowerDataModel resolveStructure(Minecraft client, double searchDistance) {
            if (client.hitResult instanceof BlockHitResult hitResult) {
                if (client.player.distanceToSqr(hitResult.getLocation()) <= searchDistance * searchDistance) {
                    BlockPos origin = hitResult.getBlockPos();
                    BlockPos peak = origin;
                    for (int y = 1; y <= 4; y++) {
                        BlockPos check = origin.above(y);
                        if (!client.level.getBlockState(check).isAir()) { peak = check; }
                        else { break; }
                    }
                    return new TowerDataModel(peak.above(), origin, hitResult.getDirection());
                }
            }
            BlockPos defaultPos = client.player.blockPosition().below();
            return new TowerDataModel(defaultPos.above(), defaultPos, Direction.UP);
        }
    }

    public static class CartStatePipeline {
        private enum PipelinePhase { IDLE, RAIL_STEP, CART_STEP, FIRE_STEP, CROSSBOW_STEP, COOLDOWN }
        private PipelinePhase phase = PipelinePhase.IDLE;
        private int tickBudget = 0;
        private int mouseButtonReleaseTracker = 0;
        private long safetyEpoch = 0L;
        private int globalCooldownTimer = 0;
        private boolean actionDischarged = false;

        public void executePipeline(Minecraft client, CartSecurityConfiguration cfg, HumanizedEntropyEngine entropy, HotbarKeySimulator keys, TowerGeometryResolver geometry) {
            if (mouseButtonReleaseTracker > 0) {
                mouseButtonReleaseTracker--;
                if (mouseButtonReleaseTracker == 0 && mc.options != null) {
                    mc.options.keyUse.setDown(false);
                }
            }

            if (globalCooldownTimer > 0) {
                globalCooldownTimer--;
                return;
            }

            if (phase == PipelinePhase.COOLDOWN) return;

            if (tickBudget > 0) {
                tickBudget--;
                return;
            }

            if (System.currentTimeMillis() > safetyEpoch && phase != PipelinePhase.IDLE) {
                abortPipeline();
                return;
            }

            boolean lookingGround = client.hitResult instanceof BlockHitResult bh && bh.getDirection() == Direction.UP;
            boolean holdingRail = keys.pressNumberKeyForAnyRail(client) || isAnyRailItem(client.player.getMainHandItem().getItem());

            if (!lookingGround || !holdingRail) {
                if (phase != PipelinePhase.IDLE) abortPipeline();
                return;
            }

            TowerDataModel tower = geometry.resolveStructure(client, cfg.getMaxPlacementReach());

            switch (phase) {
                case IDLE:
                    actionDischarged = false;
                    phase = PipelinePhase.RAIL_STEP;
                    safetyEpoch = System.currentTimeMillis() + 2000L;
                    break;

                case RAIL_STEP:
                    if (keys.pressNumberKeyForAnyRail(client)) {
                        aimAndSimulateRightClick(client, tower.getCartTarget(), tower.getHitFaceDirection(), entropy);
                        tickBudget = entropy.getStochasticDelay(cfg.getBaseActionDelay());
                        phase = PipelinePhase.CART_STEP;
                    }
                    break;

                case CART_STEP:
                    if (keys.pressNumberKeyForSlot(client, Items.TNT_MINECART)) {
                        aimAndSimulateRightClick(client, tower.getCartTarget(), tower.getHitFaceDirection(), entropy);
                        tickBudget = entropy.getStochasticDelay(cfg.getBaseActionDelay());
                        phase = PipelinePhase.FIRE_STEP;
                    }
                    break;

                case FIRE_STEP:
                    if (keys.pressNumberKeyForSlot(client, Items.FLINT_AND_STEEL) || keys.pressNumberKeyForSlot(client, Items.FIRE_CHARGE)) {
                        aimAndSimulateRightClick(client, tower.getFireTarget(), tower.getHitFaceDirection(), entropy);
                        tickBudget = entropy.getStochasticDelay(cfg.getBaseActionDelay());
                        phase = PipelinePhase.CROSSBOW_STEP;
                    }
                    break;

                case CROSSBOW_STEP:
                    if (keys.pressNumberKeyForChargedOrAnyCrossbow(client)) {
                        ItemStack active = client.player.getMainHandItem();
                        if (active.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(active)) {
                            client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
                            actionDischarged = true;
                        } else {
                            client.options.keyUse.setDown(true);
                            mouseButtonReleaseTracker = 4;
                        }

                        if (actionDischarged) {
                            phase = PipelinePhase.COOLDOWN;
                            globalCooldownTimer = 10;
                            phase = PipelinePhase.IDLE;
                        }
                        tickBudget = entropy.getStochasticDelay(cfg.getBaseActionDelay());
                    }
                    break;

                case COOLDOWN:
                    break;
            }
        }

        private void aimAndSimulateRightClick(Minecraft client, BlockPos pos, Direction face, HumanizedEntropyEngine entropy) {
            Vec3 center = Vec3.atCenterOf(pos);
            double dx = center.x - client.player.getX();
            double dy = center.y - client.player.getEyeY();
            double dz = center.z - client.player.getZ();

            float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
            float targetPitch = (float) (-Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
            targetPitch = Mth.clamp(targetPitch, 10.0F, 85.0F);

            targetYaw += entropy.getJitterOffset(0.08F);
            targetPitch += entropy.getJitterOffset(0.06F);

            client.player.setYRot(targetYaw);
            client.player.setXRot(targetPitch);

            mc.options.keyUse.setDown(true);
            mouseButtonReleaseTracker = 2;

            if (client.gameMode != null && client.player != null) {
                BlockHitResult hit = new BlockHitResult(center, face, pos, false);
                client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit);
            }
        }

        private boolean isAnyRailItem(Item item) {
            return item == Items.RAIL || item == Items.POWERED_RAIL || item == Items.DETECTOR_RAIL || item == Items.ACTIVATOR_RAIL;
        }

        public void abortPipeline() {
            phase = PipelinePhase.IDLE;
            tickBudget = 0;
            actionDischarged = false;
            if (mc.options != null) {
                mc.options.keyUse.setDown(false);
            }
            safetyEpoch = 0L;
        }
    }
        }
