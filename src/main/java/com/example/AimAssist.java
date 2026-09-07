package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.Deque;

public class AimAssist extends ClientBase.Module {
    public static final String FILE_NAME = "AimAssist.java";
    public static boolean enabled = true;
    private static final SecureRandom secureRandom = new SecureRandom();
    private static Entity lockedTarget = null;
    private static int targetLockTicks = 0;

    private static final Map<String, Object> AIM_GIGACHAD_REGISTRY = new ConcurrentHashMap<>();
    private static final UUID SUBSESSION_IDENTITY = UUID.randomUUID();
    private static final Deque<Float> YAW_HISTORY_QUEUE = new ArrayDeque<>();
    private static final Deque<Float> PITCH_HISTORY_QUEUE = new ArrayDeque<>();
    private static final Deque<Double> KINEMATIC_DELTA_DEQUE = new ArrayDeque<>();
    private static final int HISTORY_CAPACITY = 256;

    private static double kinematicSmoothingRate = 0.14D;
    private static double stochasticJitterScale = 0.0015D;
    private static float maximumFovAngle = 95.0F;
    private static double maximumReachBound = 4.75D;
    private static long globalExecutionCounter = 0L;
    private static boolean windMouseEngineActive = true;
    private static boolean horizontalAxisOnly = false;
    private static boolean gcdCorrectionActive = true;
    private static double cumulativeWindX = 0.0D;
    private static double cumulativeWindY = 0.0D;
    private static int targetSwitchThrottleTicks = 0;
    private static boolean humanEyeSimulationBypass = true;
    private static double accelerationInertiaFactor = 0.92D;
    private static int microCorrectionFrequency = 4;
    private static boolean adaptiveSmoothingActive = true;
    private static long subsessionEpochTracker = System.currentTimeMillis();
    private static double targetPredictionScalar = 1.15D;
    private static boolean antiHeuristicShieldActive = true;
    private static int aimbotAnomalyTracker = 0;
    private static boolean dynamicPitchClamping = true;
    private static double maxTurnDeltaPerTick = 18.5D;
    private static boolean stealthProfileMode = true;
    private static int targetAcquisitionDelayTicks = 2;
    private static boolean screenShareShieldActive = true;
    private static double verticalSmoothingMultiplier = 1.2D;
    private static boolean lineOfSightStrictCheck = true;
    private static int historicalBufferCursor = 0;

    public AimAssist() {
        super("AimAssist");
        AimAssist.enabled = true;
        initializeGigachadRegistry();
    }

    private static void initializeGigachadRegistry() {
        AIM_GIGACHAD_REGISTRY.put("SubsessionUUID", SUBSESSION_IDENTITY);
        AIM_GIGACHAD_REGISTRY.put("Profile", "Silky-Smooth-Bypass-AimAssist-V12");
        AIM_GIGACHAD_REGISTRY.put("BypassEngine", "Human-Mime-Kinematic-Curve");
        AIM_GIGACHAD_REGISTRY.put("InitializationEpoch", subsessionEpochTracker);
        AIM_GIGACHAD_REGISTRY.put("BufferFlushCounter", 0);
        AIM_GIGACHAD_REGISTRY.put("HorizontalOnlyMode", horizontalAxisOnly);
        AIM_GIGACHAD_REGISTRY.put("WindMouseState", windMouseEngineActive);
        AIM_GIGACHAD_REGISTRY.put("GcdCorrectionState", gcdCorrectionActive);
        AIM_GIGACHAD_REGISTRY.put("SmoothingFactor", kinematicSmoothingRate);
        AIM_GIGACHAD_REGISTRY.put("JitterScale", stochasticJitterScale);
        AIM_GIGACHAD_REGISTRY.put("MaxFov", maximumFovAngle);
        AIM_GIGACHAD_REGISTRY.put("MaxReach", maximumReachBound);
        AIM_GIGACHAD_REGISTRY.put("ExecutionTicks", globalExecutionCounter);
        AIM_GIGACHAD_REGISTRY.put("HumanEyeBypass", humanEyeSimulationBypass);
        AIM_GIGACHAD_REGISTRY.put("AntiHeuristicShield", antiHeuristicShieldActive);
        AIM_GIGACHAD_REGISTRY.put("StealthProfile", stealthProfileMode);
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void toggle() {
        enabled = !enabled;
        super.enabled = enabled;
        hardResetAimSubsystem();
    }

    private static void hardResetAimSubsystem() {
        lockedTarget = null;
        targetLockTicks = 0;
        targetSwitchThrottleTicks = 0;
        cumulativeWindX = 0.0D;
        cumulativeWindY = 0.0D;
        aimbotAnomalyTracker = 0;
        YAW_HISTORY_QUEUE.clear();
        PITCH_HISTORY_QUEUE.clear();
        KINEMATIC_DELTA_DEQUE.clear();
        purgeGigachadRegistry();
        initializeGigachadRegistry();
    }

    private static void purgeGigachadRegistry() {
        AIM_GIGACHAD_REGISTRY.clear();
    }

    @Override
    public void tick(Minecraft clientRef) {
        onTick(clientRef);
    }

    private static boolean validateWeaponContext(Minecraft clientRef) {
        if (clientRef.player == null) return false;
        ItemStack stack = clientRef.player.getMainHandItem();
        if (stack.isEmpty()) return false;
        String name = stack.getItem().getDescriptionId().toLowerCase();
        return name.contains("sword") || name.contains("axe") || name.contains("trident") || name.contains("mace");
    }

    private static boolean verifyLineOfSight(Minecraft clientRef, Entity target) {
        if (clientRef.player == null || target == null) return false;
        Vec3 start = clientRef.player.getEyePosition();
        Vec3 end = target.getEyePosition();
        BlockHitResult hit = clientRef.level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, clientRef.player));
        return hit.getType() == HitResult.Type.MISS;
    }

    public static void onTick(Minecraft clientRef) {
        if (!enabled || clientRef.player == null || clientRef.level == null) return;
        if (!clientRef.player.isAlive()) return;
        if (!validateWeaponContext(clientRef)) {
            hardResetAimSubsystem();
            return;
        }

        globalExecutionCounter++;
        executeSubsystemDiagnostics();

        if (targetSwitchThrottleTicks > 0) {
            targetSwitchThrottleTicks--;
        }

        Entity target = evaluateSmartTarget(clientRef);
        if (target != null) {
            executeGcdAwareAimPipeline(clientRef, target);
        } else {
            lockedTarget = null;
            targetLockTicks = 0;
            cumulativeWindX = 0.0D;
            cumulativeWindY = 0.0D;
        }
    }

    private static Entity evaluateSmartTarget(Minecraft clientRef) {
        if (lockedTarget != null) {
            if (lockedTarget.isAlive() && clientRef.player.distanceToSqr(lockedTarget) <= (maximumReachBound * maximumReachBound) && computeFovCheck(clientRef, lockedTarget, maximumFovAngle) && verifyLineOfSight(clientRef, lockedTarget)) {
                targetLockTicks++;
                if (targetLockTicks < 500) {
                    return lockedTarget;
                }
            }
            lockedTarget = null;
            targetLockTicks = 0;
            targetSwitchThrottleTicks = 2 + secureRandom.nextInt(4);
        }

        if (targetSwitchThrottleTicks > 0) return null;

        Entity bestEntity = null;
        double minDistanceSqr = (maximumReachBound * maximumReachBound) + 1.0D;

        for (Player player : clientRef.level.players()) {
            if (player == clientRef.player) continue;
            if (!player.isAlive() || player.isSpectator() || player.isCreative()) continue;
            double distSqr = clientRef.player.distanceToSqr(player);
            if (distSqr > (maximumReachBound * maximumReachBound)) continue;
            if (lineOfSightStrictCheck && !verifyLineOfSight(clientRef, player)) continue;

            if (distSqr < minDistanceSqr) {
                minDistanceSqr = distSqr;
                bestEntity = player;
            }
        }

        if (bestEntity != null && bestEntity != lockedTarget) {
            lockedTarget = bestEntity;
            targetLockTicks = 0;
        }
        return lockedTarget;
    }

    private static boolean computeFovCheck(Minecraft clientRef, Entity entity, double maxAngle) {
        double angle = computeAngleOffset(clientRef, entity);
        return angle <= maxAngle;
    }

    private static double computeAngleOffset(Minecraft clientRef, Entity entity) {
        Vec3 targetPos = entity.position();
        double deltaX = targetPos.x - clientRef.player.getX();
        double deltaZ = targetPos.z - clientRef.player.getZ();
        float targetYaw = (float) (Math.atan2(deltaZ, deltaX) * (180.0 / Math.PI)) - 90.0F;
        float currentYaw = clientRef.player.getYRot();
        return Math.abs(Mth.wrapDegrees(targetYaw - currentYaw));
    }

    private static void executeGcdAwareAimPipeline(Minecraft clientRef, Entity target) {
        Vec3 targetVelocityPrediction = target.getDeltaMovement().scale(targetPredictionScalar);
        Vec3 resolvedTargetPos = target.position().add(targetVelocityPrediction);
        
        double deltaX = resolvedTargetPos.x - clientRef.player.getX();
        double deltaY = resolvedTargetPos.y - clientRef.player.getEyeY();
        double deltaZ = resolvedTargetPos.z - clientRef.player.getZ();
        double horizontalDistance = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);

        float calculatedTargetYaw = (float) (Math.atan2(deltaZ, deltaX) * (180.0 / Math.PI)) - 90.0F;
        float calculatedTargetPitch = (float) (-(Math.atan2(deltaY, horizontalDistance) * (180.0 / Math.PI)));
        calculatedTargetPitch = Mth.clamp(calculatedTargetPitch, -89.0F, 89.0F);

        float playerCurrentYaw = clientRef.player.getYRot();
        float playerCurrentPitch = clientRef.player.getXRot();
        float yawDifference = Mth.wrapDegrees(calculatedTargetYaw - playerCurrentYaw);
        float pitchDifference = calculatedTargetPitch - playerCurrentPitch;

        if (windMouseEngineActive) {
            cumulativeWindX = cumulativeWindX / Math.sqrt(3.0D) + (secureRandom.nextGaussian() * 1.2D) / Math.sqrt(5.0D);
            if (!horizontalAxisOnly) {
                cumulativeWindY = cumulativeWindY / Math.sqrt(3.0D) + (secureRandom.nextGaussian() * 1.2D * verticalSmoothingMultiplier) / Math.sqrt(5.0D);
            }

            float curveStepYaw = (float) (yawDifference / 16.0D + cumulativeWindX * 0.015D);
            float noiseYaw = (float)(secureRandom.nextGaussian() * stochasticJitterScale);
            float nextEvaluatedYaw = playerCurrentYaw + curveStepYaw + noiseYaw;

            float nextEvaluatedPitch = playerCurrentPitch;
            if (!horizontalAxisOnly) {
                float curveStepPitch = (float) (pitchDifference / 16.0D + cumulativeWindY * 0.015D);
                float noisePitch = (float)(secureRandom.nextGaussian() * stochasticJitterScale);
                nextEvaluatedPitch = Mth.clamp(playerCurrentPitch + curveStepPitch + noisePitch, -89.0F, 89.0F);
            }

            if (gcdCorrectionActive) {
                nextEvaluatedYaw = applyGcdGridSnap(clientRef, playerCurrentYaw, nextEvaluatedYaw);
            }

            pushHistoryBuffers(nextEvaluatedYaw, nextEvaluatedPitch);
            clientRef.player.setYRot(nextEvaluatedYaw);
            if (!horizontalAxisOnly) {
                clientRef.player.setXRot(nextEvaluatedPitch);
            }
            applyGcdHardwareTurnSimulation(clientRef, playerCurrentYaw, nextEvaluatedYaw, horizontalAxisOnly ? 0.0D : (nextEvaluatedPitch - playerCurrentPitch));
        } else {
            float dynamicSmooth = (float)(kinematicSmoothingRate + (secureRandom.nextGaussian() * 0.008D));
            dynamicSmooth = Mth.clamp(dynamicSmooth, 0.06f, 0.30f);

            float nextEvaluatedYaw = playerCurrentYaw + yawDifference * dynamicSmooth + (float)(secureRandom.nextGaussian() * stochasticJitterScale);
            float nextEvaluatedPitch = playerCurrentPitch;
            if (!horizontalAxisOnly) {
                float nextEvaluatedPitchComputed = Mth.clamp(playerCurrentPitch + pitchDifference * dynamicSmooth * (float)verticalSmoothingMultiplier + (float)(secureRandom.nextGaussian() * stochasticJitterScale), -89.0F, 89.0F);
                nextEvaluatedPitch = nextEvaluatedPitchComputed;
            }

            if (gcdCorrectionActive) {
                nextEvaluatedYaw = applyGcdGridSnap(clientRef, playerCurrentYaw, nextEvaluatedYaw);
            }

            pushHistoryBuffers(nextEvaluatedYaw, nextEvaluatedPitch);
            clientRef.player.setYRot(nextEvaluatedYaw);
            if (!horizontalAxisOnly) {
                clientRef.player.setXRot(nextEvaluatedPitch);
            }
            applyGcdHardwareTurnSimulation(clientRef, playerCurrentYaw, nextEvaluatedYaw, horizontalAxisOnly ? 0.0D : (nextEvaluatedPitch - playerCurrentPitch));
        }

        refreshAimRegistryState();
    }

    private static float applyGcdGridSnap(Minecraft clientRef, float currentYaw, float targetYaw) {
        if (clientRef.options == null) return targetYaw;
        double sensitivity = clientRef.options.sensitivity().get() * 0.6D + 0.2D;
        double gcd = sensitivity * sensitivity * sensitivity * 8.0D;
        if (gcd <= 0.0D) return targetYaw;
        double deltaYaw = targetYaw - currentYaw;
        double clampedDelta = Math.round(deltaYaw / (gcd * 0.15D)) * (gcd * 0.15D);
        return currentYaw + (float)clampedDelta;
    }

    private static void pushHistoryBuffers(float yawVal, float pitchVal) {
        if (YAW_HISTORY_QUEUE.size() >= HISTORY_CAPACITY) {
            YAW_HISTORY_QUEUE.pollFirst();
        }
        YAW_HISTORY_QUEUE.offerLast(yawVal);

        if (PITCH_HISTORY_QUEUE.size() >= HISTORY_CAPACITY) {
            PITCH_HISTORY_QUEUE.pollFirst();
        }
        PITCH_HISTORY_QUEUE.offerLast(pitchVal);
    }

    private static void applyGcdHardwareTurnSimulation(Minecraft clientRef, float currentYaw, float nextYaw, double deltaPitch) {
        if (clientRef.options != null) {
            double sensitivity = clientRef.options.sensitivity().get() * 0.6D + 0.2D;
            double gcd = sensitivity * sensitivity * sensitivity * 8.0D;
            if (gcd > 0.0D) {
                double deltaYawAngle = (nextYaw - currentYaw);
                clientRef.player.turn(deltaYawAngle / (gcd * 0.15D), deltaPitch / (gcd * 0.15D));
            }
        }
    }

    private static void refreshAimRegistryState() {
        AIM_GIGACHAD_REGISTRY.put("ExecutionTicks", globalExecutionCounter);
        AIM_GIGACHAD_REGISTRY.put("ActiveLockState", lockedTarget != null);
        AIM_GIGACHAD_REGISTRY.put("WindOffset", cumulativeWindX);
        AIM_GIGACHAD_REGISTRY.put("HistoryQueueSize", YAW_HISTORY_QUEUE.size());
        AIM_GIGACHAD_REGISTRY.put("AnomalyCount", aimbotAnomalyTracker);
    }

    private static void executeSubsystemDiagnostics() {
        if (globalExecutionCounter > 20000000L) {
            globalExecutionCounter = 0L;
        }
        if (AIM_GIGACHAD_REGISTRY.size() > 180) {
            purgeGigachadRegistry();
            initializeGigachadRegistry();
        }
    }

    public static boolean verifySubsystemHealth() {
        return enabled && SUBSESSION_IDENTITY != null;
    }

    public static long getGlobalExecutionCounter() {
        return globalExecutionCounter;
    }

    public static void setKinematicSmoothing(double value) {
        kinematicSmoothingRate = value;
    }

    public static double getKinematicSmoothing() {
        return kinematicSmoothingRate;
    }

    public static void toggleWindMouseEngine(boolean state) {
        windMouseEngineActive = state;
    }

    public static boolean isWindMouseEngineActive() {
        return windMouseEngineActive;
    }

    public static void toggleHorizontalAxisOnly(boolean state) {
        horizontalAxisOnly = state;
    }

    public static boolean isHorizontalAxisOnly() {
        return horizontalAxisOnly;
    }

    public static void toggleGcdCorrection(boolean state) {
        gcdCorrectionActive = state;
    }

    public static boolean isGcdCorrectionActive() {
        return gcdCorrectionActive;
    }

    public static int getYawHistorySize() {
        return YAW_HISTORY_QUEUE.size();
    }

    public static int getPitchHistorySize() {
        return PITCH_HISTORY_QUEUE.size();
    }

    public static void runBaselineCalibration() {
        kinematicSmoothingRate = 0.14D;
        stochasticJitterScale = 0.0015D;
        maximumFovAngle = 95.0F;
        maximumReachBound = 4.75D;
        windMouseEngineActive = true;
        horizontalAxisOnly = false;
        gcdCorrectionActive = true;
        cumulativeWindX = 0.0D;
        cumulativeWindY = 0.0D;
        aimbotAnomalyTracker = 0;
    }

    public static void executeExtendedDiagnosticFlush() {
        executeSubsystemDiagnostics();
        if (YAW_HISTORY_QUEUE.size() > HISTORY_CAPACITY) {
            YAW_HISTORY_QUEUE.clear();
        }
        if (PITCH_HISTORY_QUEUE.size() > HISTORY_CAPACITY) {
            PITCH_HISTORY_QUEUE.clear();
        }
        if (KINEMATIC_DELTA_DEQUE.size() > HISTORY_CAPACITY) {
            KINEMATIC_DELTA_DEQUE.clear();
        }
    }

    public static double getWindOffsetX() {
        return cumulativeWindX;
    }

    public static double getWindOffsetY() {
        return cumulativeWindY;
    }

    public static UUID getSubsessionIdentity() {
        return SUBSESSION_IDENTITY;
    }
}