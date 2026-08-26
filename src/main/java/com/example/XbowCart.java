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

            boolean lookingAtBlock = mc.hitResult instanceof BlockHitResult;
            BlockHitResult hit = lookingAtBlock ? (BlockHitResult) mc.hitResult : null;
            boolean isLookingDown = lookingAtBlock && hit != null && hit.getDirection() == Direction.UP;
            boolean holdingRail = isAnyRail(mc.player.getMainHandItem().getItem());

            if (enabled && isLookingDown && holdingRail) {
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
        private final AimedLegitimateInteractionSimulator simulator = new AimedLegitimateInteractionSimulator();
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
        private final boolean towerMode = true;

        public void refresh() {}

        public int getActionDelayTicks() { return 2 + speedRandom.nextInt(2); }
        public double getMaxPlacementDistance() { return maxPlacementDistance; }
        public boolean isTowerMode() { return towerMode; }
    }

    public static class HotbarSlotAuditor {
        public boolean selectAndSyncSlot(Minecraft client, Item targetItem) {
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

        public boolean selectAnyRail(Minecraft client) {
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

        public boolean selectChargedOrAnyCrossbow(Minecraft client) {
            for (int i = 0; i < 9; i++) {
                ItemStack stack = client.player.getInventory().getItem(i);
                if (stack.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(stack)) {
                    client.player.getInventory().setSelectedSlot(i);
                    client.options.keyHotbarSlots[i].setDown(true);
                    client.options.keyHotbarSlots[i].setDown(false);
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

    public static class AimedLegitimateInteractionSimulator {
        private boolean hasFired = false;
        private boolean railPlaced = false;
        private boolean cartPlaced = false;
        private boolean firePlaced = false;
        private int releaseTracker = 0;

        public void updateReleases(Minecraft client) {
            if (releaseTracker > 0) {
                releaseTracker--;
                if (releaseTracker == 0 && client.options != null) {
                    client.options.keyUse.setDown(false);
                }
            }
        }

        public void placeRailAimed(Minecraft client, BlockPos pos, Direction face) {
            if (railPlaced) return;
            aimAndInteract(client, pos, face);
            railPlaced = true;
        }

        public void placeCartAimed(Minecraft client, BlockPos pos, Direction face) {
            if (cartPlaced) return;
            aimAndInteract(client, pos, face);
            cartPlaced = true;
        }

        public void placeFireAimed(Minecraft client, BlockPos pos, Direction face) {
            if (firePlaced) return;
            aimAndInteract(client, pos, face);
            firePlaced = true;
        }

        public void fireCrossbowOnce(Minecraft client) {
            if (hasFired) return;
            ItemStack activeStack = client.player.getMainHandItem();
            if (activeStack.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(activeStack)) {
                client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
                hasFired = true;
            }
        }

        private void aimAndInteract(Minecraft client, BlockPos pos, Direction face) {
            if (client.gameMode != null && client.player != null) {
                Vec3 targetCenter = Vec3.atCenterOf(pos);
                double dx = targetCenter.x - client.player.getX();
                double dy = targetCenter.y - client.player.getEyeY();
                double dz = targetCenter.z - client.player.getZ();
                float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
                float targetPitch = (float) (-Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
                targetPitch = Mth.clamp(targetPitch, 10.0F, 85.0F);

                client.player.setYRot(targetYaw);
                client.player.setXRot(targetPitch);

                client.options.keyUse.setDown(true);
                releaseTracker = 2;

                BlockHitResult hitResult = new BlockHitResult(targetCenter, face, pos, false);
                client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hitResult);
            }
        }

        public boolean hasFired() { return hasFired; }
        public boolean hasCompleted() { return railPlaced && cartPlaced && firePlaced && hasFired; }

        public void reset(Minecraft client) {
            railPlaced = false; cartPlaced = false; firePlaced = false; hasFired = false; releaseTracker = 0;
            if (client.options != null) client.options.keyUse.setDown(false);
        }
    }

    public static class CartExecutionStateMachine {
        private enum CartPhase { INACTIVE, STAGE_RAIL_DEPLOY, STAGE_CART_DEPLOY, STAGE_FIRE_IGNITE, STAGE_CROSSBOW_BURST, COMPLETE_LOCK }
        private CartPhase activePhase = CartPhase.INACTIVE;
        private int sequenceDelay = 0;
        private long safetyWatchdogEpoch = 0L;

        public void executeSequence(Minecraft client, CartConfiguration cfg, HotbarSlotAuditor auditor, TowerGeometryCalculator geometry, AimedLegitimateInteractionSimulator simulator) {
            simulator.updateReleases(client);
            if (activePhase == CartPhase.COMPLETE_LOCK) return;
            if (sequenceDelay > 0) { sequenceDelay--; return; }
            if (System.currentTimeMillis() > safetyWatchdogEpoch && activePhase != CartPhase.INACTIVE) { abortSequence(); return; }

            TowerData tower = geometry.resolveTowerStructure(client, cfg.getMaxPlacementDistance());

            switch (activePhase) {
                case INACTIVE:
                    simulator.reset(client);
                    activePhase = CartPhase.STAGE_RAIL_DEPLOY;
                    safetyWatchdogEpoch = System.currentTimeMillis() + 1500L;
                    break;
                case STAGE_RAIL_DEPLOY:
                    if (auditor.selectAnyRail(client)) {
                        simulator.placeRailAimed(client, tower.getCartPosition(), tower.getHitFace());
                        sequenceDelay = cfg.getActionDelayTicks();
                        activePhase = CartPhase.STAGE_CART_DEPLOY;
                    }
                    break;
                case STAGE_CART_DEPLOY:
                    if (auditor.selectAndSyncSlot(client, Items.TNT_MINECART)) {
                        simulator.placeCartAimed(client, tower.getCartPosition(), tower.getHitFace());
                        sequenceDelay = cfg.getActionDelayTicks();
                        activePhase = CartPhase.STAGE_FIRE_IGNITE;
                    }
                    break;
                case STAGE_FIRE_IGNITE:
                    if (auditor.selectAndSyncSlot(client, Items.FLINT_AND_STEEL) || auditor.selectAndSyncSlot(client, Items.FIRE_CHARGE)) {
                        simulator.placeFireAimed(client, tower.getFirePosition(), tower.getHitFace());
                        sequenceDelay = cfg.getActionDelayTicks();
                        activePhase = CartPhase.STAGE_CROSSBOW_BURST;
                    }
                    break;
                case STAGE_CROSSBOW_BURST:
                    if (auditor.selectChargedOrAnyCrossbow(client)) {
                        simulator.fireCrossbowOnce(client);
                        if (simulator.hasFired()) {
                            activePhase = CartPhase.COMPLETE_LOCK;
                            XbowCart.enabled = false;
                        }
                        sequenceDelay = cfg.getActionDelayTicks();
                    }
                    break;
                case COMPLETE_LOCK:
                    break;
            }
        }

        public void abortSequence() {
            activePhase = CartPhase.INACTIVE;
            sequenceDelay = 0;
            safetyWatchdogEpoch = 0L;
        }
    }
        }
