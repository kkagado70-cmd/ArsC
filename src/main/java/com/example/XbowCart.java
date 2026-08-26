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
                HT1CartDirector.getInstance().hardResetSequence();
            }

            if (enabled) {
                onTick(client);
            }
        });
    }

    private static boolean isAnyRail(Item item) {
        return item == Items.RAIL || item == Items.POWERED_RAIL || item == Items.DETECTOR_RAIL || item == Items.ACTIVATOR_RAIL;
    }

    public static void toggle() {
        enabled = !enabled;
        HT1CartDirector.getInstance().hardResetSequence();
    }

    public static void onTick() { onTick(Minecraft.getInstance()); }
    public static void onTick(Minecraft client) {
        if (client.player == null || client.level == null) return;
        HT1CartDirector.getInstance().processTick(client);
    }

    public static class HT1CartDirector {
        private static final HT1CartDirector INSTANCE = new HT1CartDirector();
        private final CartConfiguration configuration = new CartConfiguration();
        private final HotbarSlotAuditor auditor = new HotbarSlotAuditor();
        private final TowerGeometryCalculator geometry = new TowerGeometryCalculator();
        private final HumanAimSimulator aimSimulator = new HumanAimSimulator();
        private final CartExecutionStateMachine pipeline = new CartExecutionStateMachine();

        public static HT1CartDirector getInstance() { return INSTANCE; }

        public void processTick(Minecraft client) {
            if (client.player == null || client.level == null) return;
            configuration.refresh();
            pipeline.executeSequence(client, configuration, auditor, geometry, aimSimulator);
        }

        public void hardResetSequence() { pipeline.abortSequence(); }
    }

    public static class CartConfiguration {
        private final Random speedRandom = new Random();
        private final double maxPlacementDistance = 6.0D;

        public void refresh() {}

        public int getActionDelayTicks() { return 2 + speedRandom.nextInt(2); }
        public double getMaxPlacementDistance() { return maxPlacementDistance; }
    }

    public static class HotbarSlotAuditor {
        public boolean simulateNumberKeySlot(Minecraft client, Item targetItem) {
            for (int i = 0; i < 9; i++) {
                ItemStack stack = client.player.getInventory().getItem(i);
                if (stack.getItem() == targetItem) {
                    client.player.getInventory().setSelectedSlot(i);
                    client.options.keyHotbarSlots[i].setDown(true);
                    client.options.keyHotbarSlots[i].setDown(false);
                    return true;
                }
            }
            return false;
        }

        public boolean pressNumberKeyForSlot(Minecraft client, Item targetItem) {
            return simulateNumberKeySlot(client, targetItem);
        }

        public boolean simulateNumberKeyRail(Minecraft client) {
            for (int i = 0; i < 9; i++) {
                Item item = client.player.getInventory().getItem(i).getItem();
                if (isAnyRail(item)) {
                    client.player.getInventory().setSelectedSlot(i);
                    client.options.keyHotbarSlots[i].setDown(true);
                    client.options.keyHotbarSlots[i].setDown(false);
                    return true;
                }
            }
            return false;
        }

        public boolean pressNumberKeyForAnyRail(Minecraft client) {
            return simulateNumberKeyRail(client);
        }

        public boolean selectNumberKeyForAnyRail(Minecraft client) {
            return simulateNumberKeyRail(client);
        }

        public boolean simulateNumberKeyCrossbow(Minecraft client) {
            for (int i = 0; i < 9; i++) {
                ItemStack stack = client.player.getInventory().getItem(i);
                if (stack.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(stack)) {
                    client.player.getInventory().setSelectedSlot(i);
                    client.options.keyHotbarSlots[i].setDown(true);
                    client.options.keyHotbarSlots[i].setDown(false);
                    return true;
                }
            }
            return simulateNumberKeySlot(client, Items.CROSSBOW);
        }

        public boolean pressNumberKeyForChargedOrAnyCrossbow(Minecraft client) {
            return simulateNumberKeyCrossbow(client);
        }
    }

    public static class TowerData {
        private final BlockPos cartPosition;
        private final BlockPos firePosition;
        private final Direction hitFace;

        public TowerData(BlockPos cartPosition, BlockPos firePosition, Direction hitFace) {
            this.cartPosition = cartPosition;
            this.firePosition = firePosition;
            this.hitFace = hitFace;
        }

        public BlockPos getCartPosition() { return cartPosition; }
        public BlockPos getFirePosition() { return firePosition; }
        public Direction getHitFace() { return hitFace; }
    }

    public static class TowerGeometryCalculator {
        public TowerData resolveTowerStructure(Minecraft client, double maxRange) {
            if (client.hitResult instanceof BlockHitResult blockHit) {
                if (client.player.distanceToSqr(blockHit.getLocation()) <= maxRange * maxRange) {
                    BlockPos basePos = blockHit.getBlockPos();
                    BlockPos topPos = basePos;
                    for (int yOffset = 1; yOffset <= 4; yOffset++) {
                        BlockPos upper = basePos.above(yOffset);
                        if (!client.level.getBlockState(upper).isAir()) { topPos = upper; }
                        else { break; }
                    }
                    return new TowerData(topPos.above(), basePos, blockHit.getDirection());
                }
            }
            BlockPos fallback = client.player.blockPosition().below();
            return new TowerData(fallback.above(), fallback, Direction.UP);
        }
    }

    public static class HumanAimSimulator {
        private final Random random = new Random();
        private int useReleaseCounter = 0;

        public void updateReleases(Minecraft client) {
            if (useReleaseCounter > 0) {
                useReleaseCounter--;
                if (useReleaseCounter == 0 && client.options != null) {
                    client.options.keyUse.setDown(false);
                }
            }
        }

        public void aimHumanLike(Minecraft client, Vec3 targetPos) {
            if (client.player == null) return;

            double dx = targetPos.x - client.player.getX();
            double dy = targetPos.y - client.player.getEyeY();
            double dz = targetPos.z - client.player.getZ();
            double hDist = Math.sqrt(dx * dx + dz * dz);

            float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
            float targetPitch = (float) (-Math.toDegrees(Math.atan2(dy, hDist)));
            targetPitch = Mth.clamp(targetPitch, -60.0F, 30.0F);

            targetYaw += (float) (random.nextGaussian() * 0.08);
            targetPitch += (float) (random.nextGaussian() * 0.06);

            float yawError = Mth.wrapDegrees(targetYaw - client.player.getYRot());
            float pitchError = Mth.wrapDegrees(targetPitch - client.player.getXRot());

            float overshootFactor = 0.08f + random.nextFloat() * 0.04f;
            float overshootYaw = yawError * overshootFactor;
            float overshootPitch = pitchError * overshootFactor;

            float speed = 5.0f + random.nextFloat() * 2.0f;
            float stepYaw = Math.max(-speed, Math.min(speed, yawError * 0.65f + overshootYaw * 0.3f));
            float stepPitch = Math.max(-speed * 0.6f, Math.min(speed * 0.6f, pitchError * 0.65f + overshootPitch * 0.3f));

            float finalYaw = client.player.getYRot() + stepYaw;
            float finalPitch = Mth.clamp(client.player.getXRot() + stepPitch, -90.0F, 90.0F);

            client.player.setYRot(finalYaw);
            client.player.setXRot(finalPitch);
            client.player.yHeadRot = finalYaw;
            client.player.yHeadRotO = finalYaw;

            client.options.keyUse.setDown(true);
            useReleaseCounter = 2;
        }

        public void triggerCrossbow(Minecraft client) {
            ItemStack activeStack = client.player.getMainHandItem();
            if (activeStack.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(activeStack)) {
                client.options.keyUse.setDown(true);
                useReleaseCounter = 2;
            } else {
                client.options.keyUse.setDown(true);
                useReleaseCounter = 4;
            }
        }

        public void reset(Minecraft client) {
            useReleaseCounter = 0;
            if (client.options != null) client.options.keyUse.setDown(false);
        }
    }

    public static class CartExecutionStateMachine {
        private enum CartPhase { INACTIVE, STAGE_RAIL_DEPLOY, STAGE_CART_DEPLOY, STAGE_FIRE_IGNITE, STAGE_CROSSBOW_BURST }
        private CartPhase activePhase = CartPhase.INACTIVE;
        private int sequenceDelay = 0;
        private int globalCooldownTicks = 0;
        private int originalSlot = -1;
        private long safetyWatchdogEpoch = 0L;

        private boolean isActivationConditionsMet(Minecraft client) {
            if (!enabled || client.player == null || client.level == null) return false;
            boolean lookingAtBlock = client.hitResult instanceof BlockHitResult;
            BlockHitResult hit = lookingAtBlock ? (BlockHitResult) client.hitResult : null;
            boolean isLookingGround = lookingAtBlock && hit != null && hit.getDirection() == Direction.UP;
            boolean holdingRail = isAnyRail(client.player.getMainHandItem().getItem());
            return isLookingGround && holdingRail;
        }

        public void executeSequence(Minecraft client, CartConfiguration cfg, HotbarSlotAuditor auditor, TowerGeometryCalculator geometry, HumanAimSimulator aimSimulator) {
            aimSimulator.updateReleases(client);

            if (globalCooldownTicks > 0) {
                globalCooldownTicks--;
                return;
            }

            if (!isActivationConditionsMet(client) && activePhase != CartPhase.INACTIVE) {
                restoreOriginalSlot(client);
                abortSequence();
                return;
            }

            if (sequenceDelay > 0) {
                sequenceDelay--;
                return;
            }

            if (System.currentTimeMillis() > safetyWatchdogEpoch && activePhase != CartPhase.INACTIVE) {
                restoreOriginalSlot(client);
                abortSequence();
                return;
            }

            TowerData tower = geometry.resolveTowerStructure(client, cfg.getMaxPlacementDistance());

            switch (activePhase) {
                case INACTIVE:
                    if (!isActivationConditionsMet(client)) return;
                    originalSlot = client.player.getInventory().getSelectedSlot();
                    aimSimulator.reset(client);
                    activePhase = CartPhase.STAGE_RAIL_DEPLOY;
                    safetyWatchdogEpoch = System.currentTimeMillis() + 1500L;
                    break;

                case STAGE_RAIL_DEPLOY:
                    if (auditor.selectNumberKeyForAnyRail(client)) {
                        aimSimulator.aimHumanLike(client, Vec3.atCenterOf(tower.getCartPosition()));
                        sequenceDelay = cfg.getActionDelayTicks();
                        activePhase = CartPhase.STAGE_CART_DEPLOY;
                    }
                    break;

                case STAGE_CART_DEPLOY:
                    if (auditor.pressNumberKeyForSlot(client, Items.TNT_MINECART)) {
                        aimSimulator.aimHumanLike(client, Vec3.atCenterOf(tower.getCartPosition()));
                        sequenceDelay = cfg.getActionDelayTicks();
                        activePhase = CartPhase.STAGE_FIRE_IGNITE;
                    }
                    break;

                case STAGE_FIRE_IGNITE:
                    if (auditor.pressNumberKeyForSlot(client, Items.FLINT_AND_STEEL) || auditor.pressNumberKeyForSlot(client, Items.FIRE_CHARGE)) {
                        aimSimulator.aimHumanLike(client, Vec3.atCenterOf(tower.getFirePosition()));
                        sequenceDelay = cfg.getActionDelayTicks();
                        activePhase = CartPhase.STAGE_CROSSBOW_BURST;
                    }
                    break;

                case STAGE_CROSSBOW_BURST:
                    if (auditor.pressNumberKeyForChargedOrAnyCrossbow(client)) {
                        aimSimulator.triggerCrossbow(client);
                        restoreOriginalSlot(client);
                        activePhase = CartPhase.INACTIVE;
                        globalCooldownTicks = 8;
                        sequenceDelay = cfg.getActionDelayTicks();
                    }
                    break;
            }
        }

        private void restoreOriginalSlot(Minecraft client) {
            if (originalSlot >= 0 && originalSlot < 9 && client.player != null) {
                client.player.getInventory().setSelectedSlot(originalSlot);
                client.options.keyHotbarSlots[originalSlot].setDown(true);
                client.options.keyHotbarSlots[originalSlot].setDown(false);
            }
            originalSlot = -1;
        }

        public void abortSequence() {
            if (activePhase != CartPhase.INACTIVE) {
                restoreOriginalSlot(mc);
            }
            activePhase = CartPhase.INACTIVE;
            sequenceDelay = 0;
            safetyWatchdogEpoch = 0L;
        }
    }
            }
