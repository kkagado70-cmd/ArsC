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
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.enchantment.ItemEnchantments;
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
                "key.automace.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_M, KeyMapping.Category.MISC
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (mc.player == null || mc.level == null) return;
            while (toggleKey.consumeClick()) {
                enabled = !enabled;
                BypassOrchestrator.getInstance().hardResetBypass();
            }
            if (enabled) {
                onTick(client);
            }
        });
    }

    public static void toggle() {
        enabled = !enabled;
        BypassOrchestrator.getInstance().hardResetBypass();
    }

    public static void onTick() {
        onTick(Minecraft.getInstance());
    }

    public static void onTick(Minecraft client) {
        if (client.player == null || client.level == null || !enabled) return;
        BypassOrchestrator.getInstance().processBypassTick(client);
    }

    public static class BypassOrchestrator {
        private static final BypassOrchestrator INSTANCE = new BypassOrchestrator();
        private final SecurityConfigurationRegistry securityConfig = new SecurityConfigurationRegistry();
        private final BehavioralEntropyEngine entropyEngine = new BehavioralEntropyEngine();
        private final LatencyCompensator latencyCompensator = new LatencyCompensator();
        private final PacketRateLimiter rateLimiter = new PacketRateLimiter();
        private final BypassedRotationManager rotationManager = new BypassedRotationManager();
        private final BypassedInventoryAuditor inventoryAuditor = new BypassedInventoryAuditor();
        private final BypassedCombatPipeline combatPipeline = new BypassedCombatPipeline();

        public static BypassOrchestrator getInstance() {
            return INSTANCE;
        }

        public void processBypassTick(Minecraft client) {
            if (client.player == null || client.level == null) return;
            securityConfig.auditRuntime();
            rateLimiter.enforceTickBudget();
            combatPipeline.executeBypassedCycle(client, securityConfig, entropyEngine, latencyCompensator, rateLimiter, rotationManager, inventoryAuditor);
        }

        public void hardResetBypass() {
            combatPipeline.abortPipeline();
            rateLimiter.flush();
        }
    }

    public static class SecurityConfigurationRegistry {
        private final double strictAimRadius = 7.0D;
        private final double maxEngagementDistance = 3.0D;
        private final double minimumDropAltitude = 1.3D;
        private final float baseInterpolationFactor = 0.38F;
        private final int antiCheatBucketLimit = 3;

        public void auditRuntime() {}

        public double getStrictAimRadius() { return strictAimRadius; }
        public double getMaxEngagementDistance() { return maxEngagementDistance; }
        public double getMinimumDropAltitude() { return minimumDropAltitude; }
        public float getBaseInterpolationFactor() { return baseInterpolationFactor; }
        public int getAntiCheatBucketLimit() { return antiCheatBucketLimit; }
    }

    public static class BehavioralEntropyEngine {
        private final Random gaussianRandom = new Random();

        public double getGaussianJitter(double scale) {
            return gaussianRandom.nextGaussian() * scale;
        }

        public int getVariableDelay(int baseTicks, int variance) {
            return baseTicks + gaussianRandom.nextInt(variance + 1);
        }

        public float applyEntropyToSmoothness(float targetSmoothness) {
            float noise = (float) (gaussianRandom.nextGaussian() * 0.012D);
            return Mth.clamp(targetSmoothness + noise, 0.25F, 0.65F);
        }
    }

    public static class LatencyCompensator {
        public double estimatePingCompensation(Player target) {
            if (target == null || mc.getConnection() == null) return 0.0D;
            var playerInfo = mc.getConnection().getPlayerInfo(target.getUUID());
            int latency = playerInfo != null ? playerInfo.getLatency() : 50;
            return Math.min(1.5D, Math.max(0.2D, latency / 100.0D));
        }
    }

    public static class PacketRateLimiter {
        private int packetCounter = 0;
        private long lastResetTime = System.currentTimeMillis();

        public void enforceTickBudget() {
            long now = System.currentTimeMillis();
            if (now - lastResetTime > 1000L) {
                packetCounter = 0;
                lastResetTime = now;
            }
        }

        public boolean canDispatchPacket(int limitPerSecond) {
            if (packetCounter < limitPerSecond) {
                packetCounter++;
                return true;
            }
            return false;
        }

        public void flush() {
            packetCounter = 0;
            lastResetTime = System.currentTimeMillis();
        }
    }

    public static class BypassedRotationManager {
        public void executeBypassedSnap(Vec3 targetCoordinates, float velocityModifier, BehavioralEntropyEngine entropy) {
            if (mc.player == null) return;

            double deltaX = targetCoordinates.x - mc.player.getX();
            double deltaY = targetCoordinates.y - mc.player.getEyeY();
            double deltaZ = targetCoordinates.z - mc.player.getZ();
            double planeDistance = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);

            float computedYaw = (float) (Math.toDegrees(Math.atan2(deltaZ, deltaX)) - 90.0D);
            float computedPitch = (float) (-Math.toDegrees(Math.atan2(deltaY, planeDistance)));

            float yawDiff = Mth.wrapDegrees(computedYaw - mc.player.getYRot());
            float pitchDiff = Mth.wrapDegrees(computedPitch - mc.player.getXRot());

            float appliedSmoothness = entropy.applyEntropyToSmoothness(velocityModifier);
            float stepYaw = yawDiff * appliedSmoothness;
            float stepPitch = pitchDiff * appliedSmoothness;

            float rawYaw = mc.player.getYRot() + stepYaw;
            float rawPitch = mc.player.getXRot() + stepPitch;

            double mouseSensitivity = mc.options.sensitivity().get();
            double multiplier = mouseSensitivity * 0.6D + 0.2D;
            double greatestCommonDivisor = multiplier * multiplier * multiplier * 8.0D * 0.15D;

            float gcdQuantizedYaw = (float) (mc.player.getYRot() + Math.round((rawYaw - mc.player.getYRot()) / greatestCommonDivisor) * greatestCommonDivisor);
            float gcdQuantizedPitch = (float) (mc.player.getXRot() + Math.round((rawPitch - mc.player.getXRot()) / greatestCommonDivisor) * greatestCommonDivisor);

            mc.player.setYRot(gcdQuantizedYaw);
            mc.player.setXRot(Mth.clamp(gcdQuantizedPitch, -90.0F, 90.0F));
        }
    }

    public static class BypassedInventoryAuditor {
        private int activeAxeIndex = -1;
        private int activeMaceIndex = -1;
        private int activeSpearIndex = -1;

        public void auditPlayerInventory(Player playerEntity, double verticalFall) {
            activeAxeIndex = -1;
            activeMaceIndex = -1;
            activeSpearIndex = -1;
            int bestDensityRating = -1;
            int bestBreachRating = -1;

            for (int slot = 0; slot < 9; slot++) {
                ItemStack stack = playerEntity.getInventory().getItem(slot);
                if (stack.isEmpty()) continue;

                String nameId = stack.getItem().getDescriptionId().toLowerCase();
                if ((nameId.contains("spear") || stack.getHoverName().getString().toLowerCase().contains("spear")) && activeSpearIndex == -1) {
                    activeSpearIndex = slot;
                } else if (stack.getItem() instanceof AxeItem && activeAxeIndex == -1) {
                    if (stack.getDamageValue() < stack.getMaxDamage() - 3) {
                        activeAxeIndex = slot;
                    }
                } else if (stack.getItem() instanceof MaceItem) {
                    int densityVal = extractEnchantmentWeight(stack, "density");
                    int breachVal = extractEnchantmentWeight(stack, "breach");

                    if (verticalFall >= 5.0D) {
                        if (densityVal > bestDensityRating) {
                            bestDensityRating = densityVal;
                            activeMaceIndex = slot;
                        }
                    } else {
                        if (breachVal > bestBreachRating) {
                            bestBreachRating = breachVal;
                            activeMaceIndex = slot;
                        }
                    }
                    if (activeMaceIndex == -1) activeMaceIndex = slot;
                }
            }
        }

        private int extractEnchantmentWeight(ItemStack itemStack, String keyName) {
            if (itemStack.isEmpty()) return 0;
            ItemEnchantments map = itemStack.get(DataComponents.ENCHANTMENTS);
            if (map == null) return 0;
            for (var entry : map.entrySet()) {
                if (entry.getKey().toString().contains(keyName)) return entry.getIntValue();
            }
            return 0;
        }

        public int getAxeIndex() { return activeAxeIndex; }
        public int getMaceIndex() { return activeMaceIndex; }
        public int getSpearIndex() { return activeSpearIndex; }

        public void simulateLegitimateSlotChange(int slotNumber, PacketRateLimiter limiter) {
            if (mc.player == null) return;
            if (mc.player.getInventory().getSelectedSlot() != slotNumber && limiter.canDispatchPacket(15)) {
                mc.player.getInventory().setSelectedSlot(slotNumber);
                if (slotNumber >= 0 && slotNumber < 9) {
                    mc.options.keyHotbarSlots[slotNumber].setDown(true);
                    mc.options.keyHotbarSlots[slotNumber].setDown(false);
                }
            }
        }
    }

    public static class BypassedCombatPipeline {
        private enum PipelineState { STANDBY, PREPARE_SLOT_CHANGE, AWAIT_SERVER_SYNC, DISPATCH_SWING, AWAIT_ATTACK_TICK, EXECUTE_ATTACK, CLEANUP_FLUSH }
        private PipelineState currentStage = PipelineState.STANDBY;
        private int tickBudgetTimer = 0;
        private int targetedSlotIndex = -1;
        private int startingSlotIndex = -1;
        private long watchdogExpiry = 0L;
        private int mouseButtonReleaseTimer = 0;

        public void executeBypassedCycle(Minecraft client, SecurityConfigurationRegistry cfg, BehavioralEntropyEngine entropy, LatencyCompensator latency, PacketRateLimiter rateLimiter, BypassedRotationManager rotator, BypassedInventoryAuditor auditor) {
            if (mouseButtonReleaseTimer > 0) {
                mouseButtonReleaseTimer--;
                if (mouseButtonReleaseTimer == 0 && mc.options != null) {
                    mc.options.keyAttack.setDown(false);
                }
            }

            if (tickBudgetTimer > 0) {
                tickBudgetTimer--;
                return;
            }

            if (System.currentTimeMillis() > watchdogExpiry && currentStage != PipelineState.STANDBY) {
                abortPipeline();
                return;
            }

            TargetPredictor globalPredictor = new TargetPredictor();
            Player activeTarget = globalPredictor.acquireStrictCrosshairTarget(client, cfg.getStrictAimRadius());
            if (activeTarget == null) {
                if (currentStage != PipelineState.STANDBY) abortPipeline();
                return;
            }

            Vec3 predictedChest = activeTarget.getBoundingBox().getCenter();
            rotator.executeBypassedSnap(predictedChest, cfg.getBaseInterpolationFactor());

            double playerFall = client.player.fallDistance;
            boolean fallingCondition = playerFall >= cfg.getMinimumDropAltitude() && client.player.getDeltaMovement().y < -0.1D;
            boolean weaponReady = client.player.getAttackStrengthScale(0.0F) >= 0.9F;

            auditor.playerInventory = client.player.getInventory(); // reference sync
            auditor.auditPlayerInventory(client.player, playerFall);
            boolean opponentShielding = activeTarget.isUsingItem() && activeTarget.getUseItem().getItem() instanceof ShieldItem;
            double distanceToEntity = client.player.distanceTo(activeTarget);

            int spearSlotIdx = auditor.getSpearIndex();
            boolean utilizeSpear = spearSlotIdx != -1 && distanceToEntity > cfg.getMaxEngagementDistance() && distanceToEntity <= 4.5D;

            switch (currentStage) {
                case STANDBY:
                    startingSlotIndex = client.player.getInventory().getSelectedSlot();
                    if (utilizeSpear && weaponReady) {
                        targetedSlotIndex = spearSlotIdx;
                        currentStage = PipelineState.PREPARE_SLOT_CHANGE;
                        watchdogTimeoutSetup();
                    } else if (opponentShielding && weaponReady) {
                        targetedSlotIndex = auditor.getAxeIndex();
                        if (targetedSlotIndex != -1) {
                            currentStage = PipelineState.PREPARE_SLOT_CHANGE;
                            watchdogTimeoutSetup();
                        }
                    } else if (fallingCondition && weaponReady) {
                        targetedSlotIndex = auditor.getMaceIndex();
                        if (targetedSlotIndex != -1) {
                            currentStage = PipelineState.PREPARE_SLOT_CHANGE;
                            watchdogTimeoutSetup();
                        }
                    }
                    break;

                case PREPARE_SLOT_CHANGE:
                    if (targetedSlotIndex != -1) {
                        auditor.simulateLegitimateSlotChange(targetedSlotIndex, rateLimiter);
                        tickBudgetTimer = entropy.getVariableDelay(cfg.getAntiCheatBucketLimit(), 1);
                        currentStage = PipelineState.AWAIT_SERVER_SYNC;
                    } else {
                        currentStage = PipelineState.CLEANUP_FLUSH;
                    }
                    break;

                case AWAIT_SERVER_SYNC:
                    currentStage = PipelineState.DISPATCH_SWING;
                    break;

                case DISPATCH_SWING:
                    double activeLimit = utilizeSpear ? 4.5D : cfg.getMaxEngagementDistance();
                    if (distanceToEntity <= activeLimit && weaponReady) {
                        client.player.swing(InteractionHand.MAIN_HAND);
                        tickBudgetTimer = 1;
                        currentStage = PipelineState.AWAIT_ATTACK_TICK;
                    } else {
                        currentStage = PipelineState.CLEANUP_FLUSH;
                    }
                    break;

                case AWAIT_ATTACK_TICK:
                    currentStage = PipelineState.EXECUTE_ATTACK;
                    break;

                case EXECUTE_ATTACK:
                    double finalReach = utilizeSpear ? 4.5D : cfg.getMaxEngagementDistance();
                    if (distanceToEntity <= finalReach) {
                        Vec3 viewEye = client.player.getEyePosition(1.0F);
                        Vec3 reachVector = viewEye.add(client.player.getViewVector(1.0F).scale(finalReach));
                        Optional<Vec3> rayHit = activeTarget.getBoundingBox().clip(viewEye, reachVector);

                        if (rayHit.isPresent() || distanceToEntity <= 3.0D) {
                            if (mc.options != null && rateLimiter.canDispatchPacket(20)) {
                                mc.options.keyAttack.setDown(true);
                                mouseButtonReleaseTimer = 2;
                            }
                        }
                    }

                    if (utilizeSpear && fallingCondition && auditor.getMaceIndex() != -1) {
                        targetedSlotIndex = auditor.getMaceIndex();
                        currentStage = PipelineState.PREPARE_SLOT_CHANGE;
                    } else {
                        currentStage = PipelineState.CLEANUP_FLUSH;
                    }
                    break;

                case CLEANUP_FLUSH:
                    if (originalSlotIndex >= 0 && originalSlotIndex < 9) {
                        auditor.simulateLegitimateSlotChange(originalSlotIndex, rateLimiter);
                    }
                    abortPipeline();
                    break;
            }
        }

        private void watchdogTimeoutSetup() {
            watchdogExpiry = System.currentTimeMillis() + 1500L;
        }

        public void abortPipeline() {
            currentStage = PipelineState.STANDBY;
            tickBudgetTimer = 0;
            if (originalSlotIndex >= 0 && originalSelectedSlot < 9 && mc.player != null) {
                mc.player.getInventory().setSelectedSlot(originalSlotIndex);
                mc.options.keyHotbarSlots[originalSlotIndex].setDown(true);
                mc.options.keyHotbarSlots[originalSlotIndex].setDown(false);
            }
            originalSlotIndex = -1;
            watchdogExpiry = 0L;
        }
    }
        }
