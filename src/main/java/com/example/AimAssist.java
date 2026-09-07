package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.Deque;

public class AimAssist extends ClientBase.Module {
    public static final String FILE_NAME = "AimAssist.java";
    public static boolean enabled = true;
    private static final Random internalRandom = new Random();
    private static Entity lockedTarget = null;
    private static int targetLockTicks = 0;

    private static final Map<String, Object> AIM_GIGACHAD_REGISTRY = new ConcurrentHashMap<>();
    private static final UUID SUBSESSION_IDENTITY = UUID.randomUUID();
    private static final Deque<Float> YAW_HISTORY_QUEUE = new ArrayDeque<>();
    private static final Deque<Float> PITCH_HISTORY_QUEUE = new ArrayDeque<>();
    private static final int HISTORY_CAPACITY = 128;

    private static double kinematicSmoothingRate = 0.22D;
    private static double stochasticJitterScale = 0.007D;
    private static float maximumFovAngle = 75.0F;
    private static double maximumReachBound = 4.0D;
    private static long globalExecutionCounter = 0L;
    private static boolean windMouseEngineActive = true;
    private static boolean horizontalAxisOnly = false;
    private static boolean gcdCorrectionActive = true;
    private static double cumulativeWindX = 0.0D;
    private static double cumulativeWindY = 0.0D;
    private static int targetSwitchThrottleTicks = 0;

    public AimAssist() {
        super("AimAssist");
        AimAssist.enabled = true;
        initializeGigachadRegistry();
    }

    private static void initializeGigachadRegistry() {
        AIM_GIGACHAD_REGISTRY.put("SubsessionUUID", SUBSESSION_IDENTITY);
        AIM_GIGACHAD_REGISTRY.put("Profile", "Vulcan-Grim-Gcd-Bypass-AimAssist");
        AIM_GIGACHAD_REGISTRY.put("BypassEngine", "Ultimate-AntiCheat-Evading-System");
        AIM_GIGACHAD_REGISTRY.put("InitializationEpoch", System.currentTimeMillis());
        AIM_GIGACHAD_REGISTRY.put("BufferFlushCounter", 0);
        AIM_GIGACHAD_REGISTRY.put("HorizontalOnlyMode", horizontalAxisOnly);
        AIM_GIGACHAD_REGISTRY.put("WindMouseState", windMouseEngineActive);
        AIM_GIGACHAD_REGISTRY.put("GcdCorrectionState", gcdCorrectionActive);
        AIM_GIGACHAD_REGISTRY.put("SmoothingFactor", kinematicSmoothingRate);
        AIM_GIGACHAD_REGISTRY.put("JitterScale", stochasticJitterScale);
        AIM_GIGACHAD_REGISTRY.put("MaxFov", maximumFovAngle);
        AIM_GIGACHAD_REGISTRY.put("MaxReach", maximumReachBound);
        AIM_GIGACHAD_REGISTRY.put("ExecutionTicks", globalExecutionCounter);
        AIM_GIGACHAD_REGISTRY.put("ActiveTargetState", false);
        AIM_GIGACHAD_REGISTRY.put("HistoryBufferSize", 0);
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
        YAW_HISTORY_QUEUE.clear();
        PITCH_HISTORY_QUEUE.clear();
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
        return stack.getItem() instanceof SwordItem || stack.getItem() instanceof AxeItem || stack.getItem() instanceof TridentItem || name.contains("sword") || name.contains("axe") || name.contains("trident") || name.contains("mace");
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
                if (targetLockTicks < 300) {
                    return lockedTarget;
                }
            }
            lockedTarget = null;
            targetLockTicks = 0;
            targetSwitchThrottleTicks = 5 + internalRandom.nextInt(5);
        }

        if (targetSwitchThrottleTicks > 0) return null;

        Entity bestEntity = null;
        double minDistanceSqr = (maximumReachBound * maximumReachBound) + 1.0D;

        for (Player player : clientRef.level.players()) {
            if (player == clientRef.player) continue;
            if (!player.isAlive() || player.isSpectator() || player.isCreative()) continue;
            double distSqr = clientRef.player.distanceToSqr(player);
            if (distSqr > (maximumReachBound * maximumReachBound)) continue;
            if (!verifyLineOfSight(clientRef, player)) continue;

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
        Vec3 targetVelocityPrediction = target.getDeltaMovement().scale(1.25D);
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
            cumulativeWindX = cumulativeWindX / Math.sqrt(3.0D) + (internalRandom.nextGaussian() * 2.2D) / Math.sqrt(5.0D);
            if (!horizontalAxisOnly) {
                cumulativeWindY = cumulativeWindY / Math.sqrt(3.0D) + (internalRandom.nextGaussian() * 2.2D) / Math.sqrt(5.0D);
            }

            float curveStepYaw = (float) (yawDifference / 11.0D + cumulativeWindX * 0.03D);
            float noiseYaw = (float)(internalRandom.nextGaussian() * stochasticJitterScale);
            float nextEvaluatedYaw = playerCurrentYaw + curveStepYaw + noiseYaw;

            float nextEvaluatedPitch = playerCurrentPitch;
            if (!horizontalAxisOnly) {
                float curveStepPitch = (float) (pitchDifference / 11.0D + cumulativeWindY * 0.03D);
                float noisePitch = (float)(internalRandom.nextGaussian() * stochasticJitterScale);
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
            float dynamicSmooth = (float)(kinematicSmoothingRate + (internalRandom.nextGaussian() * 0.02D));
            dynamicSmooth = Mth.clamp(dynamicSmooth, 0.10f, 0.42f);

            float nextEvaluatedYaw = playerCurrentYaw + yawDifference * dynamicSmooth + (float)(internalRandom.nextGaussian() * stochasticJitterScale);
            float nextEvaluatedPitch = playerCurrentPitch;
            if (!horizontalAxisOnly) {
                float nextEvaluatedPitchComputed = Mth.clamp(playerCurrentPitch + pitchDifference * dynamicSmooth + (float)(internalRandom.nextGaussian() * stochasticJitterScale), -89.0F, 89.0F);
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
    }

    private static void executeSubsystemDiagnostics() {
        if (globalExecutionCounter > 10000000L) {
            globalExecutionCounter = 0L;
        }
        if (AIM_GIGACHAD_REGISTRY.size() > 90) {
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
        kinematicSmoothingRate = 0.22D;
        stochasticJitterScale = 0.007D;
        maximumFovAngle = 75.0F;
        maximumReachBound = 4.0D;
        windMouseEngineActive = true;
        horizontalAxisOnly = false;
        gcdCorrectionActive = true;
        cumulativeWindX = 0.0D;
        cumulativeWindY = 0.0D;
    }

    public static void executeExtendedDiagnosticFlush() {
        executeSubsystemDiagnostics();
        if (YAW_HISTORY_QUEUE.size() > HISTORY_CAPACITY) {
            YAW_HISTORY_QUEUE.clear();
        }
        if (PITCH_HISTORY_QUEUE.size() > HISTORY_CAPACITY) {
            PITCH_HISTORY_QUEUE.clear();
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