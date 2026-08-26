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
            } else {
                HT1CartDirector.getInstance().hardResetSequence();
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
        private final PreciseLegitimateInteractionSimulator simulator = new PreciseLegitimateInteractionSimulator();
        private final CartExecutionStateMachine pipeline = new CartExecutionStateMachine();

        public static HT1CartDirector getInstance() { return INSTANCE; }

        public void processTick(Minecraft client) {
            if (client.player == null || client.level == null) return;
            configuration.refresh();
            pipeline.executeSequence(client, configuration, auditor, geometry, simulator);
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
        public boolean selectAndSyncSlot(Minecraft client, Item targetItem) {
            for (int i = 0; i < 9; i++) {
                ItemStack stack = client.player.getInventory().getItem(i);
                if (stack.getItem() == targetItem) {
                    client.options.keyHotbarSlots[i].setDown(true);
                    client.options.keyHotbarSlots[i].setDown(false);
                    return true;
                }
            }
            return false;
        }

        public boolean selectAnyRail(Minecraft client) {
            for (int i = 0; i < 9; i++) {
                Item item = client.player.getInventory().getItem(i).getItem();
                if (isAnyRail(item)) {
                    client.options.keyHotbarSlots[i].setDown(true);
                    client.options.keyHotbarSlots[i].setDown(false);
                    return true;
                }
            }
            return false;
        }

        public boolean selectChargedOrAnyCrossbow(Minecraft client) {
            for (int i = 0; i < 9; i++) {
                ItemStack stack = client.player.getInventory().getItem(i);
                if (stack.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(stack)) {
                    client.options.keyHotbarSlots[i].setDown(true);
                    client.options.keyHotbarSlots[i].setDown(false);
                    return true;
                }
            }
            return selectAndSyncSlot(client, Items.CROSSBOW);
        }
    }

    public static class TowerData {
        private final BlockPos railPosition;
        private final BlockPos firePosition;
        private final BlockPos cartPosition;
        private final Direction hitFace;

        public TowerData(BlockPos railPosition, BlockPos firePosition, BlockPos cartPosition, Direction hitFace) {
            this.railPosition = railPosition;
            this.firePosition = firePosition;
            this.cartPosition = cartPosition;
            this.hitFace = hitFace;
        }

        public BlockPos getRailPosition() { return railPosition; }
        public BlockPos getFirePosition() { return firePosition; }
        public BlockPos getCartPosition() { return cartPosition; }
        public Direction getHitFace() { return hitFace; }
    }

    public static class TowerGeometryCalculator {
        public TowerData resolveTowerStructure(Minecraft client, double maxRange) {
            if (client.hitResult instanceof BlockHitResult blockHit) {
                if (client.player.distanceToSqr(blockHit.getLocation()) <= maxRange * maxRange) {
                    BlockPos basePos = blockHit.getBlockPos();
                    BlockPos railPos = basePos;
                    BlockPos firePos = basePos.above();
                    BlockPos cartPos = basePos.above(2);
                    return new TowerData(railPos, firePos, cartPos, blockHit.getDirection());
                }
            }
            BlockPos fallback = client.player.blockPosition().below();
            return new TowerData(fallback, fallback.above(), fallback.above(2), Direction.UP);
        }
    }

    public static class PreciseLegitimateInteractionSimulator {
        private boolean hasFired = false;
        private int mouseButtonReleaseTracker = 0;

        public void updateReleases(Minecraft client) {
            if (mouseButtonReleaseTracker > 0) {
                mouseButtonReleaseTracker--;
                if (mouseButtonReleaseTracker == 0 && client.options != null) {
                    client.options.keyUse.setDown(false);
                }
            }
        }

        public void interactAt(Minecraft client, BlockPos pos, Direction face) {
            if (client.player != null) {
                Vec3 targetCenter = Vec3.atCenterOf(pos);
                double dx = targetCenter.x - client.player.getX();
                double dy = targetCenter.y - client.player.getEyeY();
                double dz = targetCenter.z - client.player.getZ();
                double hDist = Math.sqrt(dx * dx + dz * dz);
                
                float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
                float targetPitch = (float) (-Math.toDegrees(Math.atan2(dy, hDist)));
                targetPitch = Mth.clamp(targetPitch, -30.0F, 30.0F);

                float currentYaw = client.player.getYRot();
                float currentPitch = client.player.getXRot();

                float smoothedYaw = currentYaw + (targetYaw - currentYaw) * 0.65F + (new Random().nextFloat() - 0.5F) * 0.08F;
                float smoothedPitch = currentPitch + (targetPitch - currentPitch) * 0.65F + (new Random().nextFloat() - 0.5F) * 0.08F;

                client.player.setYRot(smoothedYaw);
                client.player.setXRot(smoothedPitch);

                mouseButtonReleaseTracker = 2;
                client.options.keyUse.setDown(true);
            }
        }

        public void fireCrossbowOnce(Minecraft client) {
            if (hasFired) return;
            ItemStack activeStack = client.player.getMainHandItem();
            if (activeStack.getItem() instanceof CrossbowItem) {
                mouseButtonReleaseTracker = 2;
                client.options.keyUse.setDown(true);
                hasFired = true;
            }
        }

        public boolean hasFired() { return hasFired; }

        public void reset() {
            hasFired = false;
            mouseButtonReleaseTracker = 0;
        }
    }

    public static class CartExecutionStateMachine {
        private enum CartPhase { 
            INACTIVE, 
            STAGE_RAIL_SELECT, STAGE_RAIL_DEPLOY, 
            STAGE_FIRE_SELECT, STAGE_FIRE_DEPLOY, 
            STAGE_CART_SELECT, STAGE_CART_DEPLOY, 
            STAGE_CROSSBOW_SELECT, STAGE_CROSSBOW_FIRE 
        }
        
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

        public void executeSequence(Minecraft client, CartConfiguration cfg, HotbarSlotAuditor auditor, TowerGeometryCalculator geometry, PreciseLegitimateInteractionSimulator simulator) {
            simulator.updateReleases(client);

            if (globalCooldownTicks > 0) {
                globalCooldownTicks--;
                return;
            }

            if (activePhase != CartPhase.INACTIVE && !isActivationConditionsMet(client)) {
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
                    originalSlot = client.player.getInventory().selected;
                    simulator.reset();
                    activePhase = CartPhase.STAGE_RAIL_SELECT;
                    safetyWatchdogEpoch = System.currentTimeMillis() + 2000L;
                    break;

                case STAGE_RAIL_SELECT:
                    if (auditor.selectAnyRail(client)) {
                        sequenceDelay = 1; // Wait 1 tick for slot switch confirmation
                        activePhase = CartPhase.STAGE_RAIL_DEPLOY;
                    } else {
                        abortSequence();
                    }
                    break;

                case STAGE_RAIL_DEPLOY:
                    simulator.interactAt(client, tower.getRailPosition(), tower.getHitFace());
                    sequenceDelay = cfg.getActionDelayTicks();
                    activePhase = CartPhase.STAGE_FIRE_SELECT;
                    break;

                case STAGE_FIRE_SELECT:
                    if (auditor.selectAndSyncSlot(client, Items.FLINT_AND_STEEL) || auditor.selectAndSyncSlot(client, Items.FIRE_CHARGE)) {
                        sequenceDelay = 1; // Wait 1 tick for slot switch confirmation
                        activePhase = CartPhase.STAGE_FIRE_DEPLOY;
                    } else {
                        abortSequence();
                    }
                    break;

                case STAGE_FIRE_DEPLOY:
                    simulator.interactAt(client, tower.getFirePosition(), tower.getHitFace());
                    sequenceDelay = cfg.getActionDelayTicks();
                    activePhase = CartPhase.STAGE_CART_SELECT;
                    break;

                case STAGE_CART_SELECT:
                    if (auditor.selectAndSyncSlot(client, Items.TNT_MINECART)) {
                        sequenceDelay = 1; // Wait 1 tick for slot switch confirmation
                        activePhase = CartPhase.STAGE_CART_DEPLOY;
                    } else {
                        abortSequence();
                    }
                    break;

                case STAGE_CART_DEPLOY:
                    simulator.interactAt(client, tower.getCartPosition(), tower.getHitFace());
                    sequenceDelay = cfg.getActionDelayTicks();
                    activePhase = CartPhase.STAGE_CROSSBOW_SELECT;
                    break;

                case STAGE_CROSSBOW_SELECT:
                    if (client.player.getAttackStrengthScale(0.0F) < 0.9F) {
                        sequenceDelay = 1;
                        return;
                    }
                    if (auditor.selectChargedOrAnyCrossbow(client)) {
                        sequenceDelay = 1; // Wait 1 tick for slot switch confirmation
                        activePhase = CartPhase.STAGE_CROSSBOW_FIRE;
                    } else {
                        abortSequence();
                    }
                    break;

                case STAGE_CROSSBOW_FIRE:
                    simulator.fireCrossbowOnce(client);
                    restoreOriginalSlot(client);
                    activePhase = CartPhase.INACTIVE;
                    globalCooldownTicks = 8;
                    sequenceDelay = cfg.getActionDelayTicks();
                    break;
            }
        }

        private void restoreOriginalSlot(Minecraft client) {
            if (originalSlot >= 0 && originalSlot < 9 && client.player != null) {
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
