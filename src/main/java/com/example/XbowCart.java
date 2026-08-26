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
        private final HotbarKeyAuditor auditor = new HotbarKeyAuditor();
        private final TowerGeometryResolver geometry = new TowerGeometryResolver();
        private final PureKeySimulationSimulator simulator = new PureKeySimulationSimulator();
        private final CartExecutionPipeline pipeline = new CartExecutionPipeline();

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

    public static class HotbarKeyAuditor {
        public boolean simulateSlotKeyPress(Minecraft client, Item targetItem) {
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

        public boolean simulateSlotKeyPressForRail(Minecraft client) {
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

        public boolean simulateSlotKeyPressForCrossbow(Minecraft client) {
            for (int i = 0; i < 9; i++) {
                ItemStack stack = client.player.getInventory().getItem(i);
                if (stack.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(stack)) {
                    client.player.getInventory().setSelectedSlot(i);
                    client.options.keyHotbarSlots[i].setDown(true);
                    client.options.keyHotbarSlots[i].setDown(false);
                    return true;
                }
            }
            return simulateSlotKeyPress(client, Items.CROSSBOW);
        }
    }

    public static class TowerDataStructure {
        private final BlockPos cartPos;
        private final BlockPos firePos;
        private final Direction hitDirection;

        public TowerDataStructure(BlockPos cartPos, BlockPos firePos, Direction hitDirection) {
            this.cartPos = cartPos;
            this.firePosition = firePos;
            this.hitDirection = hitDirection;
        }

        public BlockPos getCartPos() { return cartPos; }
        public BlockPos getFirePos() { return firePosition; }
        public Direction getHitDirection() { return hitDirection; }
    }

    public static class TowerGeometryResolver {
        public TowerDataStructure resolveStructure(Minecraft client, double searchRange) {
            if (client.hitResult instanceof BlockHitResult hit) {
                if (client.player.distanceToSqr(hit.getLocation()) <= searchRange * searchRange) {
                    BlockPos base = hit.getBlockPos();
                    BlockPos top = base;
                    for (int y = 1; y <= 4; y++) {
                        BlockPos upper = base.above(y);
                        if (!client.level.getBlockState(upper).isAir()) { top = upper; }
                        else { break; }
                    }
                    return new TowerDataStructure(top.above(), base, hit.getDirection());
                }
            }
            BlockPos fallback = client.player.blockPosition().below();
            return new TowerDataStructure(fallback.above(), fallback, Direction.UP);
        }
    }

    public static class PureKeySimulationSimulator {
        private boolean fired = false;
        private boolean railDone = false;
        private boolean cartDone = false;
        private boolean fireDone = false;
        private int useReleaseCounter = 0;

        public void updateReleases(Minecraft client) {
            if (useReleaseCounter > 0) {
                useReleaseCounter--;
                if (useReleaseCounter == 0 && client.options != null) {
                    client.options.keyUse.setDown(false);
                }
            }
        }

        public void executeRailPlacement(Minecraft client, BlockPos pos) {
            if (railDone) return;
            aimAndSimulateKeyUse(client, pos);
            railDone = true;
        }

        public void executeCartPlacement(Minecraft client, BlockPos pos) {
            if (cartDone) return;
            aimAndSimulateKeyUse(client, pos);
            cartDone = true;
        }

        public void executeFirePlacement(Minecraft client, BlockPos pos) {
            if (fireDone) return;
            aimAndSimulateKeyUse(client, pos);
            fireDone = true;
        }

        public void executeCrossbowAction(Minecraft client) {
            if (fired) return;
            ItemStack stack = client.player.getMainHandItem();
            if (stack.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(stack)) {
                client.options.keyUse.setDown(true);
                useReleaseCounter = 2;
                fired = true;
            } else {
                client.options.keyUse.setDown(true);
                useReleaseCounter = 4;
            }
        }

        private void aimAndSimulateKeyUse(Minecraft client, BlockPos pos) {
            if (client.player != null) {
                Vec3 center = Vec3.atCenterOf(pos);
                double dx = center.x - client.player.getX();
                double dy = center.y - client.player.getEyeY();
                double dz = center.z - client.player.getZ();
                float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
                float pitch = (float) (-Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
                pitch = Mth.clamp(pitch, 10.0F, 85.0F);

                Random rand = new Random();
                client.player.setYRot(yaw + (rand.nextFloat() - 0.5f) * 0.4f);
                client.player.setXRot(Mth.clamp(pitch + (rand.nextFloat() - 0.5f) * 0.3f, 10.0F, 85.0F));

                client.options.keyUse.setDown(true);
                useReleaseCounter = 2;
            }
        }

        public boolean hasFired() { return fired; }
        public boolean hasCompleted() { return railDone && cartDone && fireDone && fired; }

        public void reset(Minecraft client) {
            railDone = false; cartDone = false; fireDone = false; fired = false; useReleaseCounter = 0;
            if (client.options != null) client.options.keyUse.setDown(false);
        }
    }

    public static class CartExecutionPipeline {
        private enum PipelinePhase { DORMANT, RAIL_STAGE, CART_STAGE, FIRE_STAGE, CROSSBOW_STAGE, LOCKED }
        private PipelinePhase currentPhase = PipelinePhase.DORMANT;
        private int tickDelay = 0;
        private long watchdog = 0L;

        public void executeSequence(Minecraft client, CartConfiguration cfg, HotbarKeyAuditor auditor, TowerGeometryResolver geometry, PureKeySimulationSimulator simulator) {
            simulator.updateReleases(client);
            if (currentPhase == PipelinePhase.LOCKED) return;
            if (tickDelay > 0) { tickDelay--; return; }
            if (System.currentTimeMillis() > watchdog && currentPhase != PipelinePhase.DORMANT) { abortPipeline(); return; }

            TowerDataModel tower = geometry.resolveStructure(client, cfg.getMaxPlacementDistance());

            switch (currentPhase) {
                case DORMANT:
                    simulator.reset(client);
                    currentPhase = PipelinePhase.RAIL_STAGE;
                    watchdog = System.currentTimeMillis() + 1500L;
                    break;
                case RAIL_STAGE:
                    if (auditor.selectNumberKeyForAnyRail(client)) {
                        simulator.executeRailPlacement(client, tower.getCartTarget());
                        tickDelay = cfg.getActionDelayTicks();
                        currentPhase = PipelinePhase.CART_STAGE;
                    }
                    break;
                case CART_STAGE:
                    if (auditor.pressNumberKeyForSlot(client, Items.TNT_MINECART)) {
                        simulator.executeCartPlacement(client, tower.getCartTarget());
                        tickDelay = cfg.getActionDelayTicks();
                        currentPhase = PipelinePhase.FIRE_STAGE;
                    }
                    break;
                case FIRE_STAGE:
                    if (auditor.pressNumberKeyForSlot(client, Items.FLINT_AND_STEEL) || auditor.pressNumberKeyForSlot(client, Items.FIRE_CHARGE)) {
                        simulator.executeFirePlacement(client, tower.getFireTarget());
                        tickDelay = cfg.getActionDelayTicks();
                        currentPhase = PipelinePhase.CROSSBOW_STAGE;
                    }
                    break;
                case CROSSBOW_STAGE:
                    if (auditor.pressNumberKeyForChargedOrAnyCrossbow(client)) {
                        simulator.executeCrossbowAction(client);
                        if (simulator.hasFired()) {
                            currentPhase = PipelinePhase.LOCKED;
                            XbowCart.enabled = false;
                        }
                        tickDelay = cfg.getActionDelayTicks();
                    }
                    break;
                case LOCKED:
                    break;
            }
        }

        public void abortPipeline() {
            currentPhase = PipelinePhase.DORMANT;
            tickDelay = 0;
            watchdog = 0L;
        }
    }
}
