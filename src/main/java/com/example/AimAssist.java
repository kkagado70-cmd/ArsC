package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.BowItem;
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
    private static int targetLostTicks = 0;

    private static final Map<String, Object> SWIGHT_ELASTIC_REGISTRY = new ConcurrentHashMap<>();
    private static final UUID SUBSESSION_IDENTITY = UUID.randomUUID();
    private static final Deque<Float> YAW_HISTORY_QUEUE = new ArrayDeque<>();
    private static final Deque<Float> PITCH_HISTORY_QUEUE = new ArrayDeque<>();
    private static final Deque<Vec3> VELOCITY_VECTOR_DEQUE = new ArrayDeque<>();
    private static final Deque<Long> TIMING_LATENCY_QUEUE = new ArrayDeque<>();
    private static final Deque<Double> ACCELERATION_SAMPLE_DEQUE = new ArrayDeque<>();
    private static final Deque<Double> JERK_SAMPLE_DEQUE = new ArrayDeque<>();
    private static final Deque<Float> OVERSHOOT_ERROR_DEQUE = new ArrayDeque<>();
    private static final Deque<Long> SESSION_TIMESTAMP_DEQUE = new ArrayDeque<>();
    private static final Deque<Double> STRAFE_VECTOR_DEQUE = new ArrayDeque<>();
    private static final Deque<Float> SACCADE_HISTORY_DEQUE = new ArrayDeque<>();
    private static final Deque<Double> RECOIL_BUFFER_DEQUE = new ArrayDeque<>();
    private static final int HISTORY_MAX_CAPACITY = 8192;

    private static double kinematicSmoothingRate = 0.35D;
    private static double stochasticJitterScale = 0.00002D;
    private static float maximumFovAngle = 100.0F;
    private static double maximumReachBound = 4.5D;
    private static long globalExecutionCounter = 0L;
    private static boolean windMouseEngineActive = true;
    private static boolean horizontalAxisOnly = false;
    private static boolean gcdCorrectionActive = true;
    private static double cumulativeWindX = 0.0D;
    private static double cumulativeWindY = 0.0D;
    private static int targetSwitchThrottleTicks = 0;

    private static Vec3 previousTargetVelocity = Vec3.ZERO;
    private static Vec3 previousTargetAcceleration = Vec3.ZERO;
    private static final float PREDICTION_TICKS = 2.0f;
    private static float containmentStrength = 0.22f;
    private static float containmentRadius = 0.9f;
    private static float overshootYawOffset = 0.0f;
    private static float overshootPitchOffset = 0.0f;
    private static int saccadeTimer = 0;
    private static double targetPredictionScalar = 1.35D;
    private static long averagePing = 50L;
    private static double verticalSmoothingMultiplier = 1.1D;
    private static boolean errorInjectionActive = true;
    private static double randomMissProbability = 0.02D;

    private static Vec3 lastKnownTargetPos = null;
    private static int memoryTicks = 0;
    private static float recoilYaw = 0.0f;
    private static float recoilPitch = 0.0f;
    private static int recoilTicks = 0;
    private static int hitCount = 0;
    private static int totalAttacks = 0;

    private static double sessionMetricAlpha = 0.5D;
    private static double sessionMetricBeta = 0.5D;
    private static double sessionMetricGamma = 0.5D;
    private static double sessionMetricDelta = 0.5D;
    private static double sessionMetricEpsilon = 0.5D;
    private static double sessionMetricZeta = 0.5D;
    private static double sessionMetricEta = 0.5D;
    private static double sessionMetricTheta = 0.5D;
    private static double sessionMetricIota = 0.5D;
    private static double sessionMetricKappa = 0.5D;
    private static boolean deepTelemetryAuditActive = true;
    private static int telemetryFlushIntervalTicks = 300;
    private static long lastTelemetryFlushEpoch = 0L;
    private static boolean adaptiveFovScalingActive = true;
    private static double fovExpansionRate = 0.05D;
    private static boolean strictRaycastVerification = true;
    private static double raycastStepPrecision = 0.1D;
    private static boolean kineticInertiaModelActive = true;
    private static double massSimulatedDrag = 0.02D;
    private static boolean rotationalFrictionActive = true;
    private static double frictionCoefficient = 0.04D;

    private static final Map<String, Double> WEAPON_SMOOTHING_PROFILES = new ConcurrentHashMap<>();
    private static final Map<String, Double> WEAPON_JITTER_PROFILES = new ConcurrentHashMap<>();
    private static final Map<String, Double> WEAPON_REACH_PROFILES = new ConcurrentHashMap<>();

    static {
        initializeElasticRegistry();
        initializeWeaponProfiles();
    }

    private static void initializeElasticRegistry() {
        SWIGHT_ELASTIC_REGISTRY.put("SubsessionUUID", SUBSESSION_IDENTITY);
        SWIGHT_ELASTIC_REGISTRY.put("Profile", "Swight-Elastic-AimAssist-700Lines");
        SWIGHT_ELASTIC_REGISTRY.put("BypassEngine", "Human-Mime-ElasticShift-Enterprise");
        SWIGHT_ELASTIC_REGISTRY.put("InitializationEpoch", System.currentTimeMillis());
        SWIGHT_ELASTIC_REGISTRY.put("BufferFlushCounter", 0);
        SWIGHT_ELASTIC_REGISTRY.put("HorizontalOnlyMode", horizontalAxisOnly);
        SWIGHT_ELASTIC_REGISTRY.put("WindMouseState", windMouseEngineActive);
        SWIGHT_ELASTIC_REGISTRY.put("GcdCorrectionState", gcdCorrectionActive);
        SWIGHT_ELASTIC_REGISTRY.put("SmoothingFactor", kinematicSmoothingRate);
        SWIGHT_ELASTIC_REGISTRY.put("JitterScale", stochasticJitterScale);
        SWIGHT_ELASTIC_REGISTRY.put("MaxFov", maximumFovAngle);
        SWIGHT_ELASTIC_REGISTRY.put("MaxReach", maximumReachBound);
        SWIGHT_ELASTIC_REGISTRY.put("ExecutionTicks", globalExecutionCounter);
        SWIGHT_ELASTIC_REGISTRY.put("AlphaMetric", sessionMetricAlpha);
        SWIGHT_ELASTIC_REGISTRY.put("BetaMetric", sessionMetricBeta);
        SWIGHT_ELASTIC_REGISTRY.put("GammaMetric", sessionMetricGamma);
        SWIGHT_ELASTIC_REGISTRY.put("DeltaMetric", sessionMetricDelta);
    }

    private static void initializeWeaponProfiles() {
        WEAPON_SMOOTHING_PROFILES.put("sword", 0.35D);
        WEAPON_SMOOTHING_PROFILES.put("axe", 0.38D);
        WEAPON_SMOOTHING_PROFILES.put("bow", 0.50D);
        WEAPON_SMOOTHING_PROFILES.put("crossbow", 0.50D);
        WEAPON_SMOOTHING_PROFILES.put("mace", 0.30D);
        WEAPON_SMOOTHING_PROFILES.put("trident", 0.32D);

        WEAPON_JITTER_PROFILES.put("sword", 0.00002D);
        WEAPON_JITTER_PROFILES.put("axe", 0.00003D);
        WEAPON_JITTER_PROFILES.put("bow", 0.00001D);
        WEAPON_JITTER_PROFILES.put("crossbow", 0.00001D);
        WEAPON_JITTER_PROFILES.put("mace", 0.00004D);
        WEAPON_JITTER_PROFILES.put("trident", 0.00002D);

        WEAPON_REACH_PROFILES.put("sword", 4.5D);
        WEAPON_REACH_PROFILES.put("axe", 4.5D);
        WEAPON_REACH_PROFILES.put("bow", 7.0D);
        WEAPON_REACH_PROFILES.put("crossbow", 7.0D);
        WEAPON_REACH_PROFILES.put("mace", 7.0D);
        WEAPON_REACH_PROFILES.put("trident", 4.5D);
    }

    public AimAssist() {
        super("AimAssist");
        AimAssist.enabled = true;
        initializeElasticRegistry();
        initializeWeaponProfiles();
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
        targetLostTicks = 0;
        targetSwitchThrottleTicks = 0;
        cumulativeWindX = 0.0D;
        cumulativeWindY = 0.0D;
        overshootYawOffset = 0.0f;
        overshootPitchOffset = 0.0f;
        saccadeTimer = 0;
        previousTargetVelocity = Vec3.ZERO;
        previousTargetAcceleration = Vec3.ZERO;
        lastKnownTargetPos = null;
        memoryTicks = 0;
        recoilYaw = 0.0f;
        recoilPitch = 0.0f;
        recoilTicks = 0;
        hitCount = 0;
        totalAttacks = 0;
        YAW_HISTORY_QUEUE.clear();
        PITCH_HISTORY_QUEUE.clear();
        VELOCITY_VECTOR_DEQUE.clear();
        TIMING_LATENCY_QUEUE.clear();
        ACCELERATION_SAMPLE_DEQUE.clear();
        JERK_SAMPLE_DEQUE.clear();
        OVERSHOOT_ERROR_DEQUE.clear();
        SESSION_TIMESTAMP_DEQUE.clear();
        STRAFE_VECTOR_DEQUE.clear();
        SACCADE_HISTORY_DEQUE.clear();
        RECOIL_BUFFER_DEQUE.clear();
        purgeRegistry();
        initializeElasticRegistry();
    }

    private static void purgeRegistry() {
        SWIGHT_ELASTIC_REGISTRY.clear();
    }

    @Override
    public void tick(Minecraft clientRef) {
        onTick(clientRef);
    }

    private static boolean isHoldingWeapon(Minecraft clientRef) {
        if (clientRef.player == null) return false;
        ItemStack stack = clientRef.player.getMainHandItem();
        if (stack.isEmpty()) return false;
        String name = stack.getItem().getDescriptionId().toLowerCase();
        return name.contains("sword") || name.contains("axe") || name.contains("trident") || name.contains("mace") || name.contains("bow") || name.contains("crossbow");
    }

    private static String resolveWeaponKey(Minecraft clientRef) {
        if (clientRef.player == null) return "sword";
        ItemStack stack = clientRef.player.getMainHandItem();
        if (stack.isEmpty()) return "sword";
        String name = stack.getItem().getDescriptionId().toLowerCase();
        if (name.contains("axe")) return "axe";
        if (name.contains("bow")) return "bow";
        if (name.contains("crossbow")) return "crossbow";
        if (name.contains("mace")) return "mace";
        if (name.contains("trident")) return "trident";
        return "sword";
    }

    private static boolean verifyLineOfSight(Minecraft clientRef, Entity target) {
        if (clientRef.player == null || target == null) return false;
        Vec3 start = clientRef.player.getEyePosition();
        Vec3 end = target.getEyePosition();
        BlockHitResult hit = clientRef.level.clip(
            new ClipContext(
                start, 
                end, 
                ClipContext.Block.COLLIDER, 
                ClipContext.Fluid.NONE, 
                clientRef.player
            )
        );
        return hit.getType() == HitResult.Type.MISS;
    }

    public static void onTick(Minecraft clientRef) {
        if (!enabled || clientRef.player == null || clientRef.level == null) return;
        if (!clientRef.player.isAlive()) return;
        if (!isHoldingWeapon(clientRef)) {
            lockedTarget = null;
            targetLockTicks = 0;
            return;
        }
        if (ShieldBreaker.isShieldStunActive()) {
            return;
        }

        if (globalExecutionCounter % 6L == 0L) {
            return;
        }

        globalExecutionCounter++;
        if (targetSwitchThrottleTicks > 0) {
            targetSwitchThrottleTicks--;
        }

        Entity target = evaluateSmartTarget(clientRef);
        if (target != null) {
            lastKnownTargetPos = target.position();
            memoryTicks = 0;
            smoothAimToTarget(clientRef, target);
        } else if (lastKnownTargetPos != null && memoryTicks < 5) {
            memoryTicks++;
            smoothAimToPosition(clientRef, lastKnownTargetPos);
        } else {
            lockedTarget = null;
            targetLockTicks = 0;
            cumulativeWindX = 0.0D;
            cumulativeWindY = 0.0D;
            previousTargetVelocity = Vec3.ZERO;
            previousTargetAcceleration = Vec3.ZERO;
            lastKnownTargetPos = null;
            memoryTicks = 0;
        }

        if (recoilTicks > 0) {
            float currentYaw = clientRef.player.getYRot();
            float currentPitch = clientRef.player.getXRot();
            clientRef.player.setYRot(currentYaw + recoilYaw / recoilTicks);
            clientRef.player.setXRot(Mth.clamp(currentPitch + recoilPitch / recoilTicks, -89.0F, 89.0F));
            recoilTicks--;
        }

        refreshAimRegistryState();
    }

    private static Entity evaluateSmartTarget(Minecraft clientRef) {
        String weaponKey = resolveWeaponKey(clientRef);
        double profileReach = WEAPON_REACH_PROFILES.getOrDefault(weaponKey, 4.5D);

        if (lockedTarget != null) {
            double effectiveReach = profileReach;
            if (clientRef.player.distanceTo(lockedTarget) > 5.0D) {
                effectiveReach = 7.0D;
            }
            double distSqr = clientRef.player.distanceToSqr(lockedTarget);
            double dist = Math.sqrt(distSqr);
            double effectiveFov = dist > 5.0D ? 60.0D : maximumFovAngle;

            if (lockedTarget.isAlive() && distSqr <= (effectiveReach * effectiveReach) && computeFovCheck(clientRef, lockedTarget, effectiveFov) && verifyLineOfSight(clientRef, lockedTarget)) {
                targetLockTicks++;
                targetLostTicks = 0;
                if (targetLockTicks < 5000) {
                    return lockedTarget;
                }
            }
            targetLostTicks++;
            if (targetLostTicks < 5) {
                return lockedTarget;
            }
            lockedTarget = null;
            targetLockTicks = 0;
            targetLostTicks = 0;
            targetSwitchThrottleTicks = 1 + secureRandom.nextInt(2);
        }

        if (targetSwitchThrottleTicks > 0) return null;

        Entity bestEntity = null;
        double minDistanceSqr = (7.0D * 7.0D) + 1.0D;

        for (Entity entity : clientRef.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || living == clientRef.player || !living.isAlive()) continue;
            if (living instanceof Player player && (player.isSpectator() || player.isCreative())) continue;
            
            double distSqr = clientRef.player.distanceToSqr(living);
            double currentReach = profileReach;
            
            ItemStack hand = clientRef.player.getMainHandItem();
            boolean isLongRange = hand.getItem() instanceof BowItem || hand.getItem() instanceof CrossbowItem;
            boolean targetStatic = living.getDeltaMovement().horizontalDistanceSqr() < 0.001D;
            
            if (isLongRange || targetStatic || distSqr > (4.5D * 4.5D)) {
                currentReach = 7.0D;
            } else {
                currentReach = profileReach;
            }
            
            if (distSqr > (currentReach * currentReach)) continue;
            
            double dist = Math.sqrt(distSqr);
            double effectiveFov = dist > 5.0D ? 60.0D : maximumFovAngle;
            
            if (!computeFovCheck(clientRef, living, effectiveFov)) continue;
            if (!verifyLineOfSight(clientRef, living)) continue;

            if (distSqr < minDistanceSqr) {
                minDistanceSqr = distSqr;
                bestEntity = living;
            }
        }

        if (bestEntity != null && bestEntity != lockedTarget) {
            lockedTarget = bestEntity;
            targetLockTicks = 0;
            targetLostTicks = 0;
        }
        return lockedTarget;
    }

    private static boolean computeFovCheck(Minecraft clientRef, Entity entity, double maxAngle) {
        Vec3 targetPos = entity.position();
        double deltaX = targetPos.x - clientRef.player.getX();
        double deltaZ = targetPos.z - clientRef.player.getZ();
        float targetYaw = (float) (Math.atan2(deltaZ, deltaX) * (180.0 / Math.PI)) - 90.0F;
        float currentYaw = clientRef.player.getYRot();
        return Math.abs(Mth.wrapDegrees(targetYaw - currentYaw)) <= maxAngle;
    }

    public static void smoothAimToTarget(Minecraft clientRef, Entity target) {
        Vec3 resolvedPos = computeResolvedTargetPosition(clientRef, target);
        performAimInterpolation(clientRef, resolvedPos, target);
    }

    private static void smoothAimToPosition(Minecraft clientRef, Vec3 position) {
        performAimInterpolation(clientRef, position, null);
    }

    private static Vec3 computeResolvedTargetPosition(Minecraft clientRef, Entity target) {
        String weaponKey = resolveWeaponKey(clientRef);
        double profileSmooth = WEAPON_SMOOTHING_PROFILES.getOrDefault(weaponKey, 0.35D);
        double distanceToTarget = clientRef.player.distanceTo(target);

        double baseSmooth = profileSmooth;
        if (distanceToTarget < 2.5D) {
            baseSmooth = 0.25D;
        } else if (distanceToTarget > 5.0D) {
            baseSmooth = 0.40D;
        } else {
            baseSmooth = profileSmooth;
        }

        if (clientRef.player.getDeltaMovement().horizontalDistanceSqr() > 0.01D) {
            baseSmooth = Math.min(0.60D, baseSmooth + 0.10D);
        }

        kinematicSmoothingRate = baseSmooth;

        saccadeTimer++;
        float maxOvershootYaw = distanceToTarget < 2.5D ? 0.005f : 0.08f;
        float maxOvershootPitch = distanceToTarget < 2.5D ? 0.005f : 0.05f;
        float decayRate = distanceToTarget < 2.5D ? 0.99f : 0.97f;

        if (saccadeTimer == 0) {
            overshootYawOffset = (float) ((secureRandom.nextDouble() - 0.5) * 0.8D);
            overshootPitchOffset = (float) ((secureRandom.nextDouble() - 0.5) * 0.3D);
        } else if (saccadeTimer == 1) {
            overshootYawOffset *= 0.2f;
            overshootPitchOffset *= 0.2f;
        } else if (saccadeTimer > 15 + secureRandom.nextInt(10)) {
            saccadeTimer = 0;
            overshootYawOffset = (float) ((secureRandom.nextDouble() - 0.5) * maxOvershootYaw * 2.0f);
            overshootPitchOffset = (float) ((secureRandom.nextDouble() - 0.5) * maxOvershootPitch * 2.0f);
        } else {
            overshootYawOffset *= decayRate;
            overshootPitchOffset *= decayRate;
            if (Math.abs(overshootYawOffset) < 0.01f) overshootYawOffset = 0.0f;
            if (Math.abs(overshootPitchOffset) < 0.01f) overshootPitchOffset = 0.0f;
        }

        Vec3 currentVel = target.getDeltaMovement();
        Vec3 acceleration = currentVel.subtract(previousTargetVelocity);
        Vec3 jerk = acceleration.subtract(previousTargetAcceleration);

        long latency = 50L;
        if (clientRef.getConnection() != null) {
            try {
                net.minecraft.client.multiplayer.PlayerInfo info = clientRef.getConnection().getPlayerInfo(clientRef.player.getUUID());
                if (info != null) latency = info.getLatency();
            } catch (Exception ignored) {}
        }
        double pingCompensation = (latency / 50.0) * 0.02D;

        Vec3 predictedPos = target.position()
                .add(currentVel.scale(PREDICTION_TICKS * 0.05D + pingCompensation))
                .add(acceleration.scale(0.5D * PREDICTION_TICKS * PREDICTION_TICKS * 0.0025D))
                .add(jerk.scale((1.0 / 6.0) * PREDICTION_TICKS * PREDICTION_TICKS * PREDICTION_TICKS * 0.000125D));

        previousTargetVelocity = currentVel;
        previousTargetAcceleration = acceleration;

        return predictedPos.add(
                (secureRandom.nextDouble() - 0.5) * 0.04D,
                target.getBbHeight() * 0.42D,
                (secureRandom.nextDouble() - 0.5) * 0.04D
        );
    }

    private static void performAimInterpolation(Minecraft clientRef, Vec3 resolvedPos, Entity target) {
        double deltaX = resolvedPos.x - clientRef.player.getX();
        double deltaY = resolvedPos.y - clientRef.player.getEyeY();
        double deltaZ = resolvedPos.z - clientRef.player.getZ();
        double horizontalDistance = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
        if (horizontalDistance < 0.001D) horizontalDistance = 0.001D;

        float calculatedTargetYaw = (float) (Math.atan2(deltaZ, deltaX) * (180.0 / Math.PI)) - 90.0F;
        float calculatedTargetPitch = (float) (-(Math.atan2(deltaY, horizontalDistance) * (180.0 / Math.PI)));
        calculatedTargetPitch = Mth.clamp(calculatedTargetPitch, -89.0F, 89.0F);

        float currentYaw = clientRef.player.getYRot();
        float currentPitch = clientRef.player.getXRot();
        float rawYawDiff = Mth.wrapDegrees(calculatedTargetYaw - currentYaw);
        float rawPitchDiff = calculatedTargetPitch - currentPitch;

        double distanceToTarget = target != null ? clientRef.player.distanceTo(target) : 3.0D;
        float deadzone = distanceToTarget < 2.5D ? 0.3f : 0.5f;
        float distanceFromCenter = (float) Math.sqrt(rawYawDiff * rawYawDiff + rawPitchDiff * rawPitchDiff);
        if (distanceFromCenter < deadzone) {
            return;
        }

        if (distanceFromCenter > containmentRadius) {
            float pullFactor = (distanceFromCenter - containmentRadius) * containmentStrength;
            float pullYaw = (rawYawDiff / distanceFromCenter) * pullFactor;
            float pullPitch = (rawPitchDiff / distanceFromCenter) * pullFactor;
            rawYawDiff -= pullYaw;
            rawPitchDiff -= pullPitch;
        }

        float finalYawDiff = rawYawDiff;
        float finalPitchDiff = rawPitchDiff;
        if (distanceFromCenter > 0.8f) {
            finalYawDiff += overshootYawOffset;
            finalPitchDiff += overshootPitchOffset;
        }

        float t = Math.min(1.0f, distanceFromCenter / 10.0f);
        float eased = 1.0f - (1.0f - t) * (1.0f - t) * (1.0f - t);
        finalYawDiff *= eased;
        finalPitchDiff *= eased;

        if (Float.isNaN(finalYawDiff) || Float.isInfinite(finalYawDiff)) finalYawDiff = 0.0f;
        if (Float.isNaN(finalPitchDiff) || Float.isInfinite(finalPitchDiff)) finalPitchDiff = 0.0f;

        float sineWaveRhythm = (float) Math.sin(globalExecutionCounter * 0.25D) * 0.08f;
        finalYawDiff += sineWaveRhythm;
        finalPitchDiff += sineWaveRhythm * 0.6f;

        if (windMouseEngineActive) {
            cumulativeWindX = cumulativeWindX * 0.97D + (secureRandom.nextGaussian() * 0.03D);
            if (!horizontalAxisOnly) {
                cumulativeWindY = cumulativeWindY * 0.97D + (secureRandom.nextGaussian() * 0.03D * verticalSmoothingMultiplier);
            }

            float curveStepYaw = (float) (finalYawDiff / 14.0D + cumulativeWindX * 0.0003D);
            float noiseYaw = (float) (secureRandom.nextGaussian() * stochasticJitterScale);
            float nextEvaluatedYaw = currentYaw + curveStepYaw + noiseYaw;

            float nextEvaluatedPitch = currentPitch;
            if (!horizontalAxisOnly) {
                float curveStepPitch = (float) (finalPitchDiff / 14.0D + cumulativeWindY * 0.0003D);
                float noisePitch = (float) (secureRandom.nextGaussian() * stochasticJitterScale);
                nextEvaluatedPitch = Mth.clamp(currentPitch + curveStepPitch + noisePitch, -89.0F, 89.0F);
            }

            if (gcdCorrectionActive) {
                nextEvaluatedYaw = applyGcdGridSnap(clientRef, currentYaw, nextEvaluatedYaw);
            }

            clientRef.player.setYRot(nextEvaluatedYaw);
            if (!horizontalAxisOnly) {
                clientRef.player.setXRot(Mth.clamp(nextEvaluatedPitch, -89.0F, 89.0F));
            }
            applyGcdHardwareTurnSimulation(clientRef, currentYaw, nextEvaluatedYaw, horizontalAxisOnly ? 0.0D : (nextEvaluatedPitch - currentPitch));
        } else {
            float nextEvaluatedYaw = currentYaw + finalYawDiff * (float) kinematicSmoothingRate;
            float nextEvaluatedPitch = currentPitch;
            if (!horizontalAxisOnly) {
                nextEvaluatedPitch = Mth.clamp(currentPitch + finalPitchDiff * (float) kinematicSmoothingRate, -89.0F, 89.0F);
            }

            if (gcdCorrectionActive) {
                nextEvaluatedYaw = applyGcdGridSnap(clientRef, currentYaw, nextEvaluatedYaw);
            }

            clientRef.player.setYRot(nextEvaluatedYaw);
            if (!horizontalAxisOnly) {
                clientRef.player.setXRot(Mth.clamp(nextEvaluatedPitch, -89.0F, 89.0F));
            }
            applyGcdHardwareTurnSimulation(clientRef, currentYaw, nextEvaluatedYaw, horizontalAxisOnly ? 0.0D : (nextEvaluatedPitch - currentPitch));
        }
    }

    private static void executeAutoCalibrationLearning() {
        totalAttacks++;
        if (totalAttacks >= 100) {
            double hitRate = (double) hitCount / totalAttacks;
            if (hitRate > 0.95D) {
                stochasticJitterScale += 0.00001D;
                randomMissProbability += 0.005D;
            } else if (hitRate < 0.70D) {
                stochasticJitterScale = Math.max(0.00001D, stochasticJitterScale - 0.00001D);
                randomMissProbability = Math.max(0.005D, randomMissProbability - 0.005D);
            }
            hitCount = 0;
            totalAttacks = 0;
        }
    }

    public static void registerAttackResult(boolean hit) {
        totalAttacks++;
        if (hit) hitCount++;
    }

    public static void triggerSimulatedRecoil() {
        recoilYaw = (float) ((secureRandom.nextDouble() - 0.5) * 0.3D);
        recoilPitch = (float) ((secureRandom.nextDouble() - 0.5) * 0.2D);
        recoilTicks = 3;
    }

    private static float applyGcdGridSnap(Minecraft clientRef, float currentYaw, float targetYaw) {
        if (clientRef.options == null) return targetYaw;
        double sensitivity = clientRef.options.sensitivity().get() * 0.6D + 0.2D;
        double gcd = sensitivity * sensitivity * sensitivity * 8.0D;
        if (gcd <= 0.0D) return targetYaw;
        double deltaYaw = targetYaw - currentYaw;
        double clampedDelta = Math.round(deltaYaw / (gcd * 0.15D)) * (gcd * 0.15D);
        return currentYaw + (float) clampedDelta;
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
        SWIGHT_ELASTIC_REGISTRY.put("ExecutionTicks", globalExecutionCounter);
        SWIGHT_ELASTIC_REGISTRY.put("ActiveLockState", lockedTarget != null);
        SWIGHT_ELASTIC_REGISTRY.put("WindOffset", cumulativeWindX);
        SWIGHT_ELASTIC_REGISTRY.put("HistorySize", YAW_HISTORY_QUEUE.size());
    }

    public static UUID getSubsessionIdentity() {
        return SUBSESSION_IDENTITY;
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
        kinematicSmoothingRate = 0.35D;
        stochasticJitterScale = 0.00002D;
        maximumFovAngle = 100.0F;
        maximumReachBound = 4.5D;
        windMouseEngineActive = true;
        horizontalAxisOnly = false;
        gcdCorrectionActive = true;
        cumulativeWindX = 0.0D;
        cumulativeWindY = 0.0D;
    }

    public static double getWindOffsetX() {
        return cumulativeWindX;
    }

    public static double getWindOffsetY() {
        return cumulativeWindY;
    }
}