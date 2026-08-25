package com.example;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionHand;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AutoMace implements ClientModInitializer {
    private static final Minecraft mc = Minecraft.getInstance();
    private static KeyMapping toggleKey;
    public static boolean enabled = false;

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.automace.toggle",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_M,
            KeyMapping.Category.MISC
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (mc.player == null || mc.level == null) return;
            while (toggleKey.consumeClick()) {
                enabled = !enabled;
                EnterpriseCombatCore.getInstance().hardReset();
            }
            if (enabled) {
                onTick(client);
            }
        });
    }

    public static void toggle() {
        enabled = !enabled;
        EnterpriseCombatCore.getInstance().hardReset();
    }

    public static void onTick() {
        onTick(Minecraft.getInstance());
    }

    public static void onTick(Minecraft client) {
        if (client.player == null || client.level == null || !enabled) return;
        EnterpriseCombatCore.getInstance().onTick(client);
    }

    public static class EnterpriseCombatCore {
        private static final EnterpriseCombatCore INSTANCE = new EnterpriseCombatCore();
        private final ConfigurationRegistry config = new ConfigurationRegistry();
        private final TargetPredictor predictor = new TargetPredictor();
        private final RotationManager rotator = new RotationManager();
        private final InventoryManager inventory = new InventoryManager();
        private final StunSlamEngine stunEngine = new StunSlamEngine();
        private final CombatStateMachine pipeline = new CombatStateMachine();

        public static EnterpriseCombatCore getInstance() {
            return INSTANCE;
        }

        public void onTick(Minecraft client) {
            if (client.player == null || client.level == null) return;
            config.refreshParameters();
            pipeline.processTick(client, config, predictor, rotator, inventory, stunEngine);
        }

        public void hardReset() {
            pipeline.abortPipeline();
        }
    }

    public static class ConfigurationRegistry {
        private final double maxSwingRange = 3.0D;
        private final double maxAimRange = 4.5D;
        private final double minFallDistance = 2.0D;
        private final float baseSnapSpeed = 0.85F;
        private final int tickInterval = 1;
        private final boolean strictCrosshairLock = true;

        public void refreshParameters() {}

        public double getMaxSwingRange() { return maxSwingRange; }
        public double getMaxAimRange() { return maxAimRange; }
        public double getMinFallDist() { return minFallDistance; }
        public float getBaseSnapSpeed() { return baseSnapSpeed; }
        public int getTickInterval() { return tickInterval; }
        public boolean isStrictCrosshairLock() { return strictCrosshairLock; }
    }

    public static class TargetPredictor {
        private final ConcurrentHashMap<UUID, Vec3> positionBuffer = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<UUID, Vec3> velocityBuffer = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<UUID, Long> timestampBuffer = new ConcurrentHashMap<>();

        public Player acquireStrictCrosshairTarget(Minecraft client, double searchRadius) {
            if (client.level == null || client.player == null) return null;
            Player selectedTarget = null;
            double lowestAngle = Double.MAX_VALUE;

            if (client.hitResult != null && client.hitResult.getType() == HitResult.Type.ENTITY) {
                EntityHitResult entityHit = (EntityHitResult) client.hitResult;
                if (entityHit.getEntity() instanceof Player playerEntity) {
                    if (playerEntity.isAlive() && !playerEntity.isSpectator() && client.player.distanceTo(playerEntity) <= searchRadius) {
                        return playerEntity;
                    }
                }
            }

            Vec3 eyePosition = client.player.getEyePosition(1.0F);
            Vec3 lookVector = client.player.getViewVector(1.0F);

            for (Player candidate : client.level.players()) {
                if (candidate == client.player || !candidate.isAlive() || candidate.isSpectator()) continue;
                if (client.player.distanceTo(candidate) > searchRadius) continue;

                calculateTargetDynamics(candidate);

                Optional<Vec3> rayBoxIntersection = candidate.getBoundingBox().clip(eyePosition, eyePosition.add(lookVector.scale(searchRadius)));
                if (rayBoxIntersection.isPresent() || client.player.distanceTo(candidate) <= 3.0D) {
                    double score = client.player.distanceToSqr(candidate);
                    if (score < lowestAngle) {
                        lowestAngle = score;
                        selectedTarget = candidate;
                    }
                }
            }
            return selectedTarget;
        }

        private void calculateTargetDynamics(Player player) {
            long now = System.currentTimeMillis();
            Vec3 currentPos = player.position();
            Vec3 oldPos = positionBuffer.getOrDefault(player.getUUID(), currentPos);
            long oldTime = timestampBuffer.getOrDefault(player.getUUID(), now);

            long elapsedMillis = Math.max(1L, now - oldTime);
            Vec3 displacement = currentPos.subtract(oldPos);
            Vec3 calculatedVelocity = new Vec3(
                displacement.x / (elapsedMillis / 50.0D),
                displacement.y / (elapsedMillis / 50.0D),
                displacement.z / (elapsedMillis / 50.0D)
            );

            velocityBuffer.put(player.getUUID(), calculatedVelocity);
            positionBuffer.put(player.getUUID(), currentPos);
            timestampBuffer.put(player.getUUID(), now);
        }

        public Vec3 extrapolateFuturePosition(Player player, double scaleFactor) {
            Vec3 velocity = velocityBuffer.getOrDefault(player.getUUID(), Vec3.ZERO);
            return player.position().add(velocity.scale(scaleFactor));
        }
    }

    public static class RotationManager {
        private final Random stochasticRandom = new Random();

        public void executeSmoothSnap(Vec3 destination, float velocityModifier) {
            if (mc.player == null) return;

            double diffX = destination.x - mc.player.getX();
            double diffY = destination.y - mc.player.getEyeY();
            double diffZ = destination.z - mc.player.getZ();
            double distancePlane = Math.sqrt(diffX * diffX + diffZ * diffZ);

            float targetYaw = (float) (Math.toDegrees(Math.atan2(diffZ, diffX)) - 90.0D);
            float targetPitch = (float) (-Math.toDegrees(Math.atan2(diffY, distancePlane)));

            float yawError = Mth.wrapDegrees(targetYaw - mc.player.getYRot());
            float pitchError = Mth.wrapDegrees(targetPitch - mc.player.getXRot());

            float stepYaw = yawError * (velocityModifier + (stochasticRandom.nextFloat() * 0.04F));
            float stepPitch = pitchError * (velocityModifier + (stochasticRandom.nextFloat() * 0.04F));

            float rawYaw = mc.player.getYRot() + stepYaw;
            float rawPitch = mc.player.getXRot() + stepPitch;

            double sensitivityValue = mc.options.sensitivity().get();
            double baseMultiplier = sensitivityValue * 0.6D + 0.2D;
            double greatestCommonDivisor = baseMultiplier * baseMultiplier * baseMultiplier * 8.0D * 0.15D;

            float quantizedYaw = (float) (mc.player.getYRot() + Math.round((rawYaw - mc.player.getYRot()) / greatestCommonDivisor) * greatestCommonDivisor);
            float quantizedPitch = (float) (mc.player.getXRot() + Math.round((rawPitch - mc.player.getXRot()) / greatestCommonDivisor) * greatestCommonDivisor);

            mc.player.setYRot(quantizedYaw);
            mc.player.setXRot(Mth.clamp(quantizedPitch, -90.0F, 90.0F));
        }
    }

    public static class InventoryManager {
        private int cachedAxeSlot = -1;
        private int cachedMaceSlot = -1;

        public void scanHotbarSlots(Player userPlayer, double fallAltitude) {
            cachedAxeSlot = -1;
            cachedMaceSlot = -1;
            int maxDensityScore = -1;
            int maxBreachScore = -1;

            for (int slotIndex = 0; slotIndex < 9; slotIndex++) {
                ItemStack slotStack = userPlayer.getInventory().getItem(slotIndex);
                if (slotStack.isEmpty()) continue;

                if (slotStack.getItem() instanceof AxeItem && cachedAxeSlot == -1) {
                    if (slotStack.getDamageValue() < slotStack.getMaxDamage() - 3) {
                        cachedAxeSlot = slotIndex;
                    }
                } else if (slotStack.getItem() instanceof MaceItem) {
                    int densityVal = parseEnchantmentScore(slotStack, "density");
                    int breachVal = parseEnchantmentScore(slotStack, "breach");

                    if (fallAltitude >= 5.0D) {
                        if (densityVal > maxDensityScore) {
                            maxDensityScore = densityVal;
                            cachedMaceSlot = slotIndex;
                        }
                    } else {
                        if (breachVal > maxBreachScore) {
                            maxBreachScore = breachVal;
                            cachedMaceSlot = slotIndex;
                        }
                    }
                    if (cachedMaceSlot == -1) cachedMaceSlot = slotIndex;
                }
            }
        }

        private int parseEnchantmentScore(ItemStack itemStack, String queryKey) {
            if (itemStack.isEmpty()) return 0;
            ItemEnchantments registryMap = itemStack.get(DataComponents.ENCHANTMENTS);
            if (registryMap == null) return 0;
            for (var entry : registryMap.entrySet()) {
                if (entry.getKey().toString().contains(queryKey)) return entry.getIntValue();
            }
            return 0;
        }

        public int getAxeSlot() { return cachedAxeSlot; }
        public int getMaceSlot() { return cachedMaceSlot; }

        public void sendSlotPacket(int slotNumber) {
            if (mc.player == null) return;
            mc.player.getInventory().setSelectedSlot(slotNumber);
            if (mc.getConnection() != null) {
                mc.getConnection().send(new ServerboundSetCarriedItemPacket(slotNumber));
            }
        }
    }

    public static class StunSlamEngine {
        private double baselinePeakY = 0.0D;
        private int hitStunTimer = 0;

        public void evaluatePlayerPhysics(Player localPlayer) {
            if (localPlayer == null) return;
            if (localPlayer.onGround()) {
                baselinePeakY = localPlayer.getY();
                hitStunTimer = 0;
            } else {
                if (localPlayer.getY() > baselinePeakY || baselinePeakY - localPlayer.getY() > 600.0D) {
                    baselinePeakY = localPlayer.getY();
                }
                hitStunTimer++;
            }
        }

        public double calculateCurrentFall(Player localPlayer) {
            if (localPlayer == null) return 0.0D;
            return Math.max(0.0D, baselinePeakY - localPlayer.getY());
        }

        public boolean checkStunOpportunity(Player targetEntity) {
            return targetEntity != null && (targetEntity.hurtTime > 0 || hitStunTimer > 4);
        }
    }

    public static class CombatStateMachine {
        private enum PipelineState { DORMANT, PREPARE_AXE_PHASE, EXECUTE_AXE_PHASE, PREPARE_MACE_PHASE, EXECUTE_MACE_PHASE, FLUSH_RESET }
        private PipelineState stage = PipelineState.DORMANT;
        private int internalTickClock = 0;
        private int originalSelectedSlot = -1;
        private long watchdogTimeout = 0L;

        public void processTick(Minecraft client, ConfigurationRegistry cfg, TargetPredictor pred, RotationManager rot, InventoryManager inv, StunSlamEngine diveEngine) {
            if (internalTickClock > 0) {
                internalTickClock--;
                return;
            }

            if (System.currentTimeMillis() > watchdogTimeout && stage != PipelineState.DORMANT) {
                abortPipeline();
                return;
            }

            diveEngine.evaluatePlayerPhysics(client.player);
            double verticalFall = diveEngine.calculateCurrentFall(client.player);
            boolean isActuallyFalling = verticalFall >= cfg.getMinFallDist() && client.player.getDeltaMovement().y < -0.1D;

            Player target = pred.acquireStrictCrosshairTarget(client, cfg.getMaxAimRange());
            if (target == null) {
                if (stage != PipelineState.DORMANT) abortPipeline();
                return;
            }

            Vec3 chestTarget = target.getBoundingBox().getCenter();
            rot.executeSmoothSnap(chestTarget, cfg.getBaseSnapSpeed());

            inv.scanHotbarSlots(client.player, verticalFall);
            boolean shieldUp = target.isUsingItem() && target.getUseItem().getItem() instanceof ShieldItem;

            switch (stage) {
                case DORMANT:
                    originalSelectedSlot = client.player.getInventory().getSelectedSlot();
                    if (shieldUp) {
                        stage = PipelineState.PREPARE_AXE_PHASE;
                        watchdogTimeout = System.currentTimeMillis() + 1500L;
                    } else if (isActuallyFalling) {
                        stage = PipelineState.PREPARE_MACE_PHASE;
                        watchdogTimeout = System.currentTimeMillis() + 1500L;
                    }
                    break;

                case PREPARE_AXE_PHASE:
                    int axeSol = inv.getAxeSlot();
                    if (axeSol != -1) {
                        inv.sendSlotPacket(axeSol);
                        internalTickClock = cfg.getTickInterval();
                        stage = PipelineState.EXECUTE_AXE_PHASE;
                    } else {
                        stage = PipelineState.PREPARE_MACE_PHASE;
                    }
                    break;

                case EXECUTE_AXE_PHASE:
                    if (client.player.distanceTo(target) <= cfg.getMaxSwingRange()) {
                        client.player.swing(InteractionHand.MAIN_HAND);
                        client.gameMode.attack(client.player, target);
                        internalTickClock = cfg.getTickInterval();
                        stage = PipelineState.FLUSH_RESET;
                    }
                    break;

                case PREPARE_MACE_PHASE:
                    int maceSol = inv.getMaceSlot();
                    if (maceSol != -1 && isActuallyFalling) {
                        inv.sendSlotPacket(maceSol);
                        internalTickClock = cfg.getTickInterval();
                        stage = PipelineState.EXECUTE_MACE_PHASE;
                    } else {
                        stage = PipelineState.FLUSH_RESET;
                    }
                    break;

                case EXECUTE_MACE_PHASE:
                    if (client.player.distanceTo(target) <= cfg.getMaxSwingRange() && isActuallyFalling) {
                        client.player.swing(InteractionHand.MAIN_HAND);
                        client.gameMode.attack(client.player, target);
                        internalTickClock = cfg.getTickInterval();
                        stage = PipelineState.FLUSH_RESET;
                    }
                    break;

                case FLUSH_RESET:
                    if (originalSelectedSlot >= 0 && originalSelectedSlot < 9) {
                        inv.sendSlotPacket(originalSelectedSlot);
                    }
                    abortPipeline();
                    break;
            }
        }

        public void abortPipeline() {
            stage = PipelineState.DORMANT;
            internalTickClock = 0;
            if (originalSelectedSlot >= 0 && originalSelectedSlot < 9 && mc.player != null) {
                mc.player.getInventory().setSelectedSlot(originalSelectedSlot);
            }
            originalSelectedSlot = -1;
            watchdogTimeout = 0L;
        }
    }
        }
