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
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
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
            "key.xbowcart.toggle",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_X,
            KeyMapping.Category.MISC
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (mc.player == null || mc.level == null) return;
            while (toggleKey.consumeClick()) {
                enabled = !enabled;
                HT1TowerCartDirector.getInstance().hardResetSequence();
            }
            if (enabled) {
                onTick(client);
            }
        });
    }

    public static void toggle() {
        enabled = !enabled;
        HT1TowerCartDirector.getInstance().hardResetSequence();
    }

    public static void onTick() {
        onTick(Minecraft.getInstance());
    }

    public static void onTick(Minecraft client) {
        if (client.player == null || client.level == null) return;
        HT1TowerCartDirector.getInstance().processTick(client);
    }

    public static class HT1TowerCartDirector {
        private static final HT1TowerCartDirector INSTANCE = new HT1TowerCartDirector();
        private final CartConfiguration configuration = new CartConfiguration();
        private final HotbarSlotAuditor auditor = new HotbarSlotAuditor();
        private final TowerGeometryCalculator geometry = new TowerGeometryCalculator();
        private final EyezingzCrossbowSimulator shooter = new EyezingzCrossbowSimulator();
        private final TowerCartStateMachine pipeline = new TowerCartStateMachine();

        public static HT1TowerCartDirector getInstance() {
            return INSTANCE;
        }

        public void processTick(Minecraft client) {
            if (client.player == null || client.level == null) return;
            configuration.refresh();
            pipeline.executeSequence(client, configuration, auditor, geometry, shooter);
        }

        public void hardResetSequence() {
            pipeline.abortSequence();
        }
    }

    public static class CartConfiguration {
        private final int actionDelayTicks = 1;
        private final double maxTowerScanDistance = 6.5D;

        public void refresh() {}

        public int getActionDelayTicks() { return actionDelayTicks; }
        public double getMaxTowerScanDistance() { return maxTowerScanDistance; }
    }

    public static class HotbarSlotAuditor {
        public boolean selectAndSyncSlot(Minecraft client, Item targetItem) {
            for (int i = 0; i < 9; i++) {
                ItemStack stack = client.player.getInventory().getItem(i);
                if (stack.getItem() == targetItem) {
                    client.player.getInventory().setSelectedSlot(i);
                    if (client.getConnection() != null) {
                        client.getConnection().send(new ServerboundSetCarriedItemPacket(i));
                    }
                    return true;
                }
            }
            return false;
        }

        public boolean selectChargedOrAnyCrossbow(Minecraft client) {
            for (int i = 0; i < 9; i++) {
                ItemStack stack = client.player.getInventory().getItem(i);
                if (stack.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(stack)) {
                    client.player.getInventory().setSelectedSlot(i);
                    if (client.getConnection() != null) {
                        client.getConnection().send(new ServerboundSetCarriedItemPacket(i));
                    }
                    return true;
                }
            }
            return selectAndSyncSlot(client, Items.CROSSBOW);
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
            // Scans vertical columns and raycasts to locate enemy tower top and base pillar
            if (client.hitResult instanceof BlockHitResult blockHit) {
                if (client.player.distanceToSqr(blockHit.getLocation()) <= maxRange * maxRange) {
                    BlockPos basePos = blockHit.getBlockPos();
                    BlockPos topPos = basePos;
                    
                    // Trace vertically upwards to find the peak of the tower column
                    for (int yOffset = 1; yOffset <= 4; yOffset++) {
                        BlockPos upper = basePos.above(yOffset);
                        if (!client.level.getBlockState(upper).isAir()) {
                            topPos = upper;
                        } else {
                            break;
                        }
                    }

                    BlockPos cartPlacementTarget = topPos.above(); // Top of the tower
                    BlockPos firePlacementTarget = basePos;        // Wood pillar below/base
                    return new TowerData(cartPlacementTarget, firePlacementTarget, blockHit.getDirection());
                }
            }

            BlockPos fallback = client.player.blockPosition().below();
            return new TowerData(fallback.above(), fallback, Direction.UP);
        }
    }

    public static class EyezingzCrossbowSimulator {
        private final Random stochasticJitter = new Random();
        private int pressTickCounter = 0;

        public void simulateEyezingzBurst(Minecraft client) {
            ItemStack activeStack = client.player.getMainHandItem();
            if (activeStack.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(activeStack)) {
                client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
            } else {
                client.options.keyUse.setDown(true);
                pressTickCounter++;
                if (pressTickCounter > 4 + stochasticJitter.nextFloat() * 3) {
                    client.options.keyUse.setDown(false);
                    pressTickCounter = 0;
                }
            }
        }

        public void releaseTriggers() {
            if (mc.options != null) {
                mc.options.keyUse.setDown(false);
            }
            pressTickCounter = 0;
        }
    }

    public static class TowerCartStateMachine {
        private enum TowerPhase { INACTIVE, DEPLOY_TOP_RAIL, DEPLOY_TOP_CART, IGNITE_BASE_FIRE, FIRE_UPWARD_CROSSBOW, COMPLETE_RESET }
        private TowerPhase activePhase = TowerPhase.INACTIVE;
        private int sequenceDelay = 0;
        private long safetyWatchdogEpoch = 0L;

        public void executeSequence(Minecraft client, CartConfiguration cfg, HotbarSlotAuditor auditor, TowerGeometryCalculator geometry, EyezingzCrossbowSimulator shooter) {
            if (sequenceDelay > 0) {
                sequenceDelay--;
                return;
            }

            if (System.currentTimeMillis() > safetyWatchdogEpoch && activePhase != TowerPhase.INACTIVE) {
                abortSequence();
                return;
            }

            TowerData tower = geometry.resolveTowerStructure(client, cfg.getMaxTowerScanDistance());
            var networkConnection = client.getConnection();

            switch (activePhase) {
                case INACTIVE:
                    activePhase = TowerPhase.DEPLOY_TOP_RAIL;
                    safetyWatchdogEpoch = System.currentTimeMillis() + 1500L;
                    break;

                case DEPLOY_TOP_RAIL:
                    if (auditor.selectAndSyncSlot(client, Items.RAIL)) {
                        if (networkConnection != null) {
                            networkConnection.send(new ServerboundUseItemOnPacket(
                                InteractionHand.MAIN_HAND,
                                new BlockHitResult(Vec3.atCenterOf(tower.getCartPosition()), tower.getHitFace(), tower.getCartPosition(), false),
                                0
                            ));
                        }
                        sequenceDelay = cfg.getActionDelayTicks();
                        activePhase = TowerPhase.DEPLOY_TOP_CART;
                    }
                    break;

                case DEPLOY_TOP_CART:
                    if (auditor.selectAndSyncSlot(client, Items.TNT_MINECART)) {
                        if (networkConnection != null) {
                            networkConnection.send(new ServerboundUseItemOnPacket(
                                InteractionHand.MAIN_HAND,
                                new BlockHitResult(Vec3.atCenterOf(tower.getCartPosition()), tower.getHitFace(), tower.getCartPosition(), false),
                                0
                            ));
                        }
                        sequenceDelay = cfg.getActionDelayTicks();
                        activePhase = TowerPhase.IGNITE_BASE_FIRE;
                    }
                    break;

                case IGNITE_BASE_FIRE:
                    if (auditor.selectAndSyncSlot(client, Items.FLINT_AND_STEEL) || auditor.selectAndSyncSlot(client, Items.FIRE_CHARGE)) {
                        if (networkConnection != null) {
                            networkConnection.send(new ServerboundUseItemOnPacket(
                                InteractionHand.MAIN_HAND,
                                new BlockHitResult(Vec3.atCenterOf(tower.getFirePosition()), tower.getHitFace(), tower.getFirePosition(), false),
                                0
                            ));
                        }
                        sequenceDelay = cfg.getActionDelayTicks();
                        activePhase = TowerPhase.FIRE_UPWARD_CROSSBOW;
                    }
                    break;

                case FIRE_UPWARD_CROSSBOW:
                    if (auditor.selectChargedOrAnyCrossbow(client)) {
                        shooter.simulateEyezingzBurst(client);
                        sequenceDelay = cfg.getActionDelayTicks();
                    }
                    break;

                case COMPLETE_RESET:
                    abortSequence();
                    break;
            }
        }

        public void abortSequence() {
            activePhase = TowerPhase.INACTIVE;
            sequenceDelay = 0;
            if (mc.options != null) {
                mc.options.keyUse.setDown(false);
            }
            safetyWatchdogEpoch = 0L;
        }
    }
}
