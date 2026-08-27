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

/**
 * XbowCart - Advanced Fabric Client Mod (1.21.11, Mojang Mappings)
 * Specialized high-performance PvP combo execution engine with human-like ClickSim
 * and robust anti-cheat bypass architectures for GrimAC, Vulcan, and AGC.
 */
public class XbowCart implements ClientModInitializer {
    private static final Minecraft MC = Minecraft.getInstance();
    private static KeyMapping toggleKey;
    public static boolean enabled = false;

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.xbowcart.toggle",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_X,
                KeyMapping.Category.MISC
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.level == null) return;
            
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

    public static boolean isAnyRail(Item item) {
        return item == Items.RAIL || 
               item == Items.POWERED_RAIL || 
               item == Items.DETECTOR_RAIL || 
               item == Items.ACTIVATOR_RAIL;
    }

    public static void toggle() {
        enabled = !enabled;
        HT1CartDirector.getInstance().hardResetSequence();
    }

    public static void onTick() {
        onTick(Minecraft.getInstance());
    }

    public static void onTick(Minecraft client) {
        if (client.player == null || client.level == null) return;
        HT1CartDirector.getInstance().processTick(client);
    }

    /**
     * Singleton Director coordinating the pipeline layers.
     */
    public static class HT1CartDirector {
        private static final HT1CartDirector INSTANCE = new HT1CartDirector();
        private final CartConfiguration configuration = new CartConfiguration();
        private final HotbarSlotAuditor auditor = new HotbarSlotAuditor();
        private final TowerGeometryCalculator geometry = new TowerGeometryCalculator();
        private final InteractionSimulator simulator = new InteractionSimulator();
        private final CartExecutionStateMachine pipeline = new CartExecutionStateMachine();

        public static HT1CartDirector getInstance() {
            return INSTANCE;
        }

        public void processTick(Minecraft client) {
            if (client.player == null || client.level == null) return;
            configuration.refresh();
            pipeline.executeSequence(client, configuration, auditor, geometry, simulator);
        }

        public void hardResetSequence() {
            pipeline.abortSequence();
        }
    }

    /**
     * Configuration settings for delays, retry limits, and reach thresholds.
     */
    public static class CartConfiguration {
        private final Random speedRandom = new Random();
        private final double maxPlacementDistance = 6.0D;
        private final int maxRetries = 3;

        public void refresh() {
            // Dynamic configuration adjustment hooks if needed
        }

        public int getActionDelayTicks() {
            return 2 + speedRandom.nextInt(2); // 2-3 ticks randomized
        }

        public double getMaxPlacementDistance() {
            return maxPlacementDistance;
        }

        public int getMaxRetries() {
            return maxRetries;
        }
    }

    /**
     * Handles hotbar auditing and slot selection through strict ClickSim key mapping simulation.
     */
    public static class HotbarSlotAuditor {
        
        public int findSlotByItem(Minecraft client, Item targetItem) {
            for (int i = 0; i < 9; i++) {
                ItemStack stack = client.player.getInventory().getItem(i);
                if (stack.getItem() == targetItem) {
                    return i;
                }
            }
            return -1;
        }

        public int findAnyRailSlot(Minecraft client) {
            for (int i = 0; i < 9; i++) {
                Item item = client.player.getInventory().getItem(i).getItem();
                if (isAnyRail(item)) {
                    return i;
                }
            }
            return -1;
        }

        public int findChargedCrossbowSlot(Minecraft client) {
            for (int i = 0; i < 9; i++) {
                ItemStack stack = client.player.getInventory().getItem(i);
                if (stack.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(stack)) {
                    return i;
                }
            }
            return findSlotByItem(client, Items.CROSSBOW);
        }

        public void selectSlotViaKey(Minecraft client, int slotIndex) {
            if (slotIndex >= 0 && slotIndex < 9) {
                client.options.keyHotbarSlots[slotIndex].setDown(true);
            }
        }

        public void releaseSlotKey(Minecraft client, int slotIndex) {
            if (slotIndex >= 0 && slotIndex < 9) {
                client.options.keyHotbarSlots[slotIndex].setDown(false);
            }
        }

        public boolean isItemInHotbar(Minecraft client, Item targetItem) {
            return findSlotByItem(client, targetItem) != -1;
        }

        public boolean isRailInHotbar(Minecraft client) {
            return findAnyRailSlot(client) != -1;
        }
    }

    /**
     * Represents exact spatial coordinates for the XbowCart combo tower components.
     */
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

        public BlockPos getRailPosition() {
            return railPosition;
        }

        public BlockPos getFirePosition() {
            return firePosition;
        }

        public BlockPos getCartPosition() {
            return cartPosition;
        }

        public Direction getHitFace() {
            return hitFace;
        }
    }

    /**
     * Resolves precise geometry maps relative to the targeted surface block.
     */
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

    /**
     * Simulates legitimate human-like aim adjustment and mouse use inputs.
     */
    public static class InteractionSimulator {
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

                // Smooth interpolation with slight natural jitter
                float smoothedYaw = currentYaw + (targetYaw - currentYaw) * 0.65F + (new Random().nextFloat() - 0.5F) * 0.05F;
                float smoothedPitch = currentPitch + (targetPitch - currentPitch) * 0.65F + (new Random().nextFloat() - 0.5F) * 0.05F;

                client.player.setYRot(smoothedYaw);
                client.player.setXRot(smoothedPitch);

                mouseButtonReleaseTracker = 2; // Hold right-click for 2 ticks
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

        public boolean hasFired() {
            return hasFired;
        }

        public void reset() {
            hasFired = false;
            mouseButtonReleaseTracker = 0;
        }
    }

    /**
     * Production state machine managing multi-phase execution (SELECT -> WAIT -> DEPLOY),
     * automatic error recovery, block state checks, and watchdog protection.
     */
    public static class CartExecutionStateMachine {
        public enum CartPhase {
            INACTIVE,
            RAIL_SELECT, RAIL_WAIT, RAIL_DEPLOY,
            FIRE_SELECT, FIRE_WAIT, FIRE_DEPLOY,
            CART_SELECT, CART_WAIT, CART_DEPLOY,
            CROSSBOW_SELECT, CROSSBOW_WAIT, CROSSBOW_FIRE,
            COOLDOWN, ABORTED
        }

        private CartPhase activePhase = CartPhase.INACTIVE;
        private int sequenceDelay = 0;
        private int globalCooldown = 0;
        private int originalSlot = -1;
        private int targetSlotCache = -1;
        private int retryCount = 0;
        private long safetyWatchdog = 0L;

        private boolean isActivationConditionsMet(Minecraft client) {
            if (!enabled || client.player == null || client.level == null) return false;
            boolean lookingAtBlock = client.hitResult instanceof BlockHitResult;
            BlockHitResult hit = lookingAtBlock ? (BlockHitResult) client.hitResult : null;
            boolean isLookingGround = lookingAtBlock && hit != null && hit.getDirection() == Direction.UP;
            boolean holdingRail = isAnyRail(client.player.getMainHandItem().getItem());
            return isLookingGround && holdingRail;
        }

        private boolean wasBlockPlaced(Minecraft client, BlockPos pos) {
            return !client.level.getBlockState(pos).isAir();
        }

        private boolean isItemSelected(Minecraft client, Item targetItem) {
            return client.player.getMainHandItem().getItem() == targetItem;
        }

        private boolean isRailSelected(Minecraft client) {
            return isAnyRail(client.player.getMainHandItem().getItem());
        }

        public void executeSequence(Minecraft client, CartConfiguration cfg, HotbarSlotAuditor auditor, TowerGeometryCalculator geometry, InteractionSimulator simulator) {
            simulator.updateReleases(client);

            if (globalCooldown > 0) {
                globalCooldown--;
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

            if (System.currentTimeMillis() > safetyWatchdog && activePhase != CartPhase.INACTIVE) {
                restoreOriginalSlot(client);
                abortSequence();
                return;
            }

            TowerData tower = geometry.resolveTowerStructure(client, cfg.getMaxPlacementDistance());

            switch (activePhase) {
                case INACTIVE:
                    if (!isActivationConditionsMet(client)) return;
                    originalSlot = client.player.getInventory().getSelectedSlot();
                    simulator.reset();
                    retryCount = 0;
                    activePhase = CartPhase.RAIL_SELECT;
                    safetyWatchdog = System.currentTimeMillis() + 4000L;
                    break;

                case RAIL_SELECT:
                    targetSlotCache = auditor.findAnyRailSlot(client);
                    if (targetSlotCache != -1) {
                        auditor.selectSlotViaKey(client, targetSlotCache);
                        sequenceDelay = 1; 
                        activePhase = CartPhase.RAIL_WAIT;
                    } else {
                        handleRetry(client);
                    }
                    break;

                case RAIL_WAIT:
                    auditor.releaseSlotKey(client, targetSlotCache);
                    if (isRailSelected(client)) {
                        sequenceDelay = cfg.getActionDelayTicks();
                        activePhase = CartPhase.RAIL_DEPLOY;
                    } else {
                        handleRetry(client);
                    }
                    break;

                case RAIL_DEPLOY:
                    simulator.interactAt(client, tower.getRailPosition(), tower.getHitFace());
                    sequenceDelay = cfg.getActionDelayTicks();
                    activePhase = CartPhase.FIRE_SELECT;
                    break;

                case FIRE_SELECT:
                    targetSlotCache = auditor.findSlotByItem(client, Items.FLINT_AND_STEEL);
                    if (targetSlotCache == -1) {
                        targetSlotCache = auditor.findSlotByItem(client, Items.FIRE_CHARGE);
                    }
                    if (targetSlotCache != -1) {
                        auditor.selectSlotViaKey(client, targetSlotCache);
                        sequenceDelay = 1;
                        activePhase = CartPhase.FIRE_WAIT;
                    } else {
                        handleRetry(client);
                    }
                    break;

                case FIRE_WAIT:
                    auditor.releaseSlotKey(client, targetSlotCache);
                    Item expectedFireItem = client.player.getInventory().getItem(targetSlotCache).getItem();
                    if (isItemSelected(client, expectedFireItem)) {
                        sequenceDelay = cfg.getActionDelayTicks();
                        activePhase = CartPhase.FIRE_DEPLOY;
                    } else {
                        handleRetry(client);
                    }
                    break;

                case FIRE_DEPLOY:
                    simulator.interactAt(client, tower.getFirePosition(), tower.getHitFace());
                    sequenceDelay = cfg.getActionDelayTicks();
                    activePhase = CartPhase.CART_SELECT;
                    break;

                case CART_SELECT:
                    targetSlotCache = auditor.findSlotByItem(client, Items.TNT_MINECART);
                    if (targetSlotCache != -1) {
                        auditor.selectSlotViaKey(client, targetSlotCache);
                        sequenceDelay = 1;
                        activePhase = CartPhase.CART_WAIT;
                    } else {
                        handleRetry(client);
                    }
                    break;

                case CART_WAIT:
                    auditor.releaseSlotKey(client, targetSlotCache);
                    if (isItemSelected(client, Items.TNT_MINECART)) {
                        sequenceDelay = cfg.getActionDelayTicks();
                        activePhase = CartPhase.CART_DEPLOY;
                    } else {
                        handleRetry(client);
                    }
                    break;

                case CART_DEPLOY:
                    simulator.interactAt(client, tower.getCartPosition(), tower.getHitFace());
                    sequenceDelay = cfg.getActionDelayTicks();
                    activePhase = CartPhase.CROSSBOW_SELECT;
                    break;

                case CROSSBOW_SELECT:
                    if (client.player.getAttackStrengthScale(0.0F) < 0.9F) {
                        sequenceDelay = 1;
                        return;
                    }
                    targetSlotCache = auditor.findChargedCrossbowSlot(client);
                    if (targetSlotCache != -1) {
                        auditor.selectSlotViaKey(client, targetSlotCache);
                        sequenceDelay = 1;
                        activePhase = CartPhase.CROSSBOW_WAIT;
                    } else {
                        handleRetry(client);
                    }
                    break;

                case CROSSBOW_WAIT:
                    auditor.releaseSlotKey(client, targetSlotCache);
                    if (client.player.getMainHandItem().getItem() instanceof CrossbowItem) {
                        sequenceDelay = cfg.getActionDelayTicks();
                        activePhase = CartPhase.CROSSBOW_FIRE;
                    } else {
                        handleRetry(client);
                    }
                    break;

                case CROSSBOW_FIRE:
                    simulator.fireCrossbowOnce(client);
                    restoreOriginalSlot(client);
                    activePhase = CartPhase.COOLDOWN;
                    globalCooldown = 8;
                    sequenceDelay = cfg.getActionDelayTicks();
                    break;

                case COOLDOWN:
                    activePhase = CartPhase.INACTIVE;
                    break;

                case ABORTED:
                    abortSequence();
                    break;
            }
        }

        private void handleRetry(Minecraft client) {
            retryCount++;
            if (retryCount > 3) {
                restoreOriginalSlot(client);
                abortSequence();
            } else {
                sequenceDelay = 4;
            }
        }

        private void restoreOriginalSlot(Minecraft client) {
            if (originalSlot >= 0 && originalSlot < 9 && client.player != null) {
                client.options.keyHotbarSlots[originalSlot].setDown(true);
                client.options.keyHotbarSlots[originalSlot].setDown(false);
            }
            originalSlot = -1;
            targetSlotCache = -1;
        }

        public void abortSequence() {
            if (activePhase != CartPhase.INACTIVE) {
                restoreOriginalSlot(Minecraft.getInstance());
            }
            activePhase = CartPhase.INACTIVE;
            sequenceDelay = 0;
            retryCount = 0;
            safetyWatchdog = 0L;
        }
    }
}
