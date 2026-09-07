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

    private static final Map<String, Object> ENTERPRISE_REGISTRY = new ConcurrentHashMap<>();
    private static final UUID SUBSESSION_IDENTITY = UUID.randomUUID();
    private static final Deque<Float> YAW_HISTORY_QUEUE = new ArrayDeque<>();
    private static final Deque<Float> PITCH_HISTORY_QUEUE = new ArrayDeque<>();
    private static final Deque<Vec3> VELOCITY_VECTOR_DEQUE = new ArrayDeque<>();
    private static final Deque<Long> TIMING_LATENCY_QUEUE = new ArrayDeque<>();
    private static final Deque<Double> ACCELERATION_SAMPLE_DEQUE = new ArrayDeque<>();
    private static final Deque<Double> JERK_SAMPLE_DEQUE = new ArrayDeque<>();
    private static final Deque<Float> OVERSHOOT_ERROR_DEQUE = new ArrayDeque<>();
    private static final Deque<Long> SESSION_TIMESTAMP_DEQUE = new ArrayDeque<>();
    private static final int HISTORY_MAX_CAPACITY = 4096;

    private static double kinematicSmoothingRate = 0.30D;
    private static double stochasticJitterScale = 0.0004D;
    private static float maximumFovAngle = 100.0F;
    private static double maximumReachBound = 4.5D;   // Aim Range padrão (expande para 7.0 em condições)
    private static long globalExecutionCounter = 0L;
    private static boolean windMouseEngineActive = true;
    private static boolean horizontalAxisOnly = false;
    private static boolean gcdCorrectionActive = true;
    private static double cumulativeWindX = 0.0D;
    private static double cumulativeWindY = 0.0D;
    private static int targetSwitchThrottleTicks = 0;

    private static Vec3 previousTargetVelocity = Vec3.ZERO;
    private static Vec3 previousTargetAcceleration = Vec3.ZERO;
    private static final float PREDICTION_TICKS = 3.0f;
    private static float containmentStrength = 0.15f;
    private static float containmentRadius = 1.2f;
    private static float overshootYawOffset = 0.0f;
    private static float overshootPitchOffset = 0.0f;
    private static int saccadeTimer = 0;
    private static double targetPredictionScalar = 1.15D;
    private static long averagePing = 50L;
    private static double verticalSmoothingMultiplier = 1.1D;
    private static boolean errorInjectionActive = true;
    private static double randomMissProbability = 0.025D;

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

    static {
        initializeEnterpriseRegistry();
    }

    private static void initializeEnterpriseRegistry() {
        ENTERPRISE_REGISTRY.put("SubsessionUUID", SUBSESSION_IDENTITY);
        ENTERPRISE_REGISTRY.put("Profile", "Swight-Ultimate-AimAssist-500Lines");
        ENTERPRISE_REGISTRY.put("BypassEngine", "Human-Mime-Fuzzy-Enterprise");
        ENTERPRISE_REGISTRY.put("InitializationEpoch", System.currentTimeMillis());
        ENTERPRISE_REGISTRY.put("BufferFlushCounter", 0);
        ENTERPRISE_REGISTRY.put("HorizontalOnlyMode", horizontalAxisOnly);
        ENTERPRISE_REGISTRY.put("WindMouseState", windMouseEngineActive);
        ENTERPRISE_REGISTRY.put("GcdCorrectionState", gcdCorrectionActive);
        ENTERPRISE_REGISTRY.put("SmoothingFactor", kinematicSmoothingRate);
        ENTERPRISE_REGISTRY.put("JitterScale", stochasticJitterScale);
        ENTERPRISE_REGISTRY.put("MaxFov", maximumFovAngle);
        ENTERPRISE_REGISTRY.put("MaxReach", maximumReachBound);
        ENTERPRISE_REGISTRY.put("ExecutionTicks", globalExecutionCounter);
        ENTERPRISE_REGISTRY.put("AlphaMetric", sessionMetricAlpha);
        ENTERPRISE_REGISTRY.put("BetaMetric", sessionMetricBeta);
        ENTERPRISE_REGISTRY.put("GammaMetric", sessionMetricGamma);
        ENTERPRISE_REGISTRY.put("DeltaMetric", sessionMetricDelta);
    }

    public AimAssist() {
        super("AimAssist");
        AimAssist.enabled = true;
        initializeEnterpriseRegistry();
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
        YAW_HISTORY_QUEUE.clear();
        PITCH_HISTORY_QUEUE.clear();
        VELOCITY_VECTOR_DEQUE.clear();
        TIMING_LATENCY_QUEUE.clear();
        ACCELERATION_SAMPLE_DEQUE.clear();
        JERK_SAMPLE_DEQUE.clear();
        OVERSHOOT_ERROR_DEQUE.clear();
        SESSION_TIMESTAMP_DEQUE.clear();
        purgeRegistry();
        initializeEnterpriseRegistry();
    }

    private static void purgeRegistry() {
        ENTERPRISE_REGISTRY.clear();
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
        return name.contains("sword") || name.contains("axe") || name.contains("trident") || name.contains("mace");
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

        globalExecutionCounter++;
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
            previousTargetVelocity = Vec3.ZERO;
            previousTargetAcceleration = Vec3.ZERO;
        }
        refreshAimRegistryState();
    }

    private static Entity evaluateSmartTarget(Minecraft clientRef) {
        if (lockedTarget != null) {
            double effectiveReach = maximumReachBound;
            if (clientRef.player.distanceTo(lockedTarget) > 5.0D) {
                effectiveReach = 7.0D;
            }
            if (lockedTarget.isAlive() && clientRef.player.distanceToSqr(lockedTarget) <= (effectiveReach * effectiveReach) && computeFovCheck(clientRef, lockedTarget, maximumFovAngle) && verifyLineOfSight(clientRef, lockedTarget)) {
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
            double currentReach = maximumReachBound;

            ItemStack hand = clientRef.player.getMainHandItem();
            boolean isLongRange = hand.getItem() instanceof BowItem || hand.getItem() instanceof CrossbowItem;
            boolean targetStatic = living.getDeltaMovement().horizontalDistanceSqr() < 0.001D;

            if (isLongRange || targetStatic || distSqr > (4.5D * 4.5D)) {
                currentReach = 7.0D;
            } else {
                currentReach = 4.5D;
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

    private static void executeGcdAwareAimPipeline(Minecraft clientRef, Entity target) {
        saccadeTimer++;
        float targetSpeed = (float) target.getDeltaMovement().horizontalDistance();
        double distanceToTarget = clientRef.player.distanceTo(target);

        if (distanceToTarget > 5.0D) {
            kinematicSmoothingRate = 0.50D;
            randomMissProbability = 0.06D;
        } else {
            kinematicSmoothingRate = 0.30D;
            randomMissProbability = 0.025D;
        }

        float maxOvershootYaw = (0.3f + (1.0f - Math.min(1.0f, targetSpeed * 1.5f)) * 0.5f);
        float maxOvershootPitch = (0.2f + (1.0f - Math.min(1.0f, targetSpeed * 1.5f)) * 0.4f);

        if (saccadeTimer > 18 + secureRandom.nextInt(12)) {
            saccadeTimer = 0;
            overshootYawOffset = (float) ((secureRandom.nextDouble() - 0.5) * maxOvershootYaw * 2.0f);
            overshootPitchOffset = (float) ((secureRandom.nextDouble() - 0.5) * maxOvershootPitch * 2.0f);
        } else {
            overshootYawOffset *= 0.92f;
            overshootPitchOffset *= 0.92f;
            if (Math.abs(overshootYawOffset) < 0.02f) overshootYawOffset = 0.0f;
            if (Math.abs(overshootPitchOffset) < 0.02f) overshootPitchOffset = 0.0f;
        }

        Vec3 currentVel = target.getDeltaMovement();
        Vec3 acceleration = currentVel.subtract(previousTargetVelocity);
        Vec3 jerk = acceleration.subtract(previousTargetAcceleration);

        double pingCompensation = (averagePing / 50.0) * 0.02D;
        Vec3 predictedPos = target.position()
                .add(currentVel.scale(PREDICTION_TICKS * 0.05D + pingCompensation))
                .add(acceleration.scale(0.5D * PREDICTION_TICKS * PREDICTION_TICKS * 0.0025D))
                .add(jerk.scale((1.0 / 6.0) * PREDICTION_TICKS * PREDICTION_TICKS * PREDICTION_TICKS * 0.000125D));

        previousTargetVelocity = currentVel;
        previousTargetAcceleration = acceleration;

        Vec3 resolvedTargetPos = predictedPos.add(
                (secureRandom.nextDouble() - 0.5) * 0.12D,
                target.getBbHeight() * (0.42D + secureRandom.nextDouble() * 0.08D),
                (secureRandom.nextDouble() - 0.5) * 0.12D
        );

        double deltaX = resolvedTargetPos.x - clientRef.player.getX();
        double deltaY = resolvedTargetPos.y - clientRef.player.getEyeY();
        double deltaZ = resolvedTargetPos.z - clientRef.player.getZ();
        double horizontalDistance = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);

        float calculatedTargetYaw = (float) (Math.atan2(deltaZ, deltaX) * (180.0 / Math.PI)) - 90.0F;
        float calculatedTargetPitch = (float) (-(Math.atan2(deltaY, horizontalDistance) * (180.0 / Math.PI)));
        calculatedTargetPitch = Mth.clamp(calculatedTargetPitch, -89.0F, 89.0F);

        float currentYaw = clientRef.player.getYRot();
        float currentPitch = clientRef.player.getXRot();
        float rawYawDiff = Mth.wrapDegrees(calculatedTargetYaw - currentYaw);
        float rawPitchDiff = calculatedTargetPitch - currentPitch;

        float yawToCenter = rawYawDiff;
        float pitchToCenter = rawPitchDiff;
        float distanceFromCenter = (float) Math.sqrt(yawToCenter * yawToCenter + pitchToCenter * pitchToCenter);

        if (distanceFromCenter > containmentRadius) {
            float pullFactor = (distanceFromCenter - containmentRadius) * containmentStrength;
            float pullYaw = (yawToCenter / distanceFromCenter) * pullFactor;
            float pullPitch = (pitchToCenter / distanceFromCenter) * pullFactor;
            rawYawDiff -= pullYaw;
            rawPitchDiff -= pullPitch;
        }

        if (Math.abs(rawYawDiff) < 0.3f && Math.abs(rawPitchDiff) < 0.3f) {
            if (saccadeTimer % 4 == 0) {
                float microJitter = (float) ((secureRandom.nextDouble() - 0.5) * 0.02D);
                clientRef.player.setYRot(currentYaw + microJitter);
                if (!horizontalAxisOnly) {
                    clientRef.player.setXRot(currentPitch + (float) ((secureRandom.nextDouble() - 0.5) * 0.02D));
                }
            }
            return;
        }

        float finalYawDiff = rawYawDiff;
        float finalPitchDiff = rawPitchDiff;
        if (Math.abs(rawYawDiff) > 0.5f) {
            finalYawDiff = rawYawDiff + overshootYawOffset;
        }
        if (Math.abs(rawPitchDiff) > 0.5f) {
            finalPitchDiff = rawPitchDiff + overshootPitchOffset;
        }

        if (errorInjectionActive && secureRandom.nextDouble() < randomMissProbability) {
            finalYawDiff += (float) ((secureRandom.nextDouble() - 0.5) * 1.5D);
            finalPitchDiff += (float) ((secureRandom.nextDouble() - 0.5) * 1.0D);
        }

        float currentDist = (float) Math.sqrt(clientRef.player.distanceToSqr(target));
        float yawSpeed = Math.abs(currentYaw - clientRef.player.yRotO);
        float pitchSpeed = Math.abs(currentPitch - clientRef.player.xRotO);
        float angularSpeed = (float) Math.sqrt(yawSpeed * yawSpeed + pitchSpeed * pitchSpeed);
        float smoothingBoost = Math.min(0.25f, angularSpeed / 60.0f);

        float baseSmooth = (float) kinematicSmoothingRate;
        float targetSmooth = baseSmooth + smoothingBoost;
        float dynamicSmooth = Mth.clamp(targetSmooth, 0.22f, 0.70f);

        if (windMouseEngineActive) {
            cumulativeWindX = cumulativeWindX / Math.sqrt(3.0D) + (secureRandom.nextGaussian() * 0.9D) / Math.sqrt(5.0D);
            if (!horizontalAxisOnly) {
                cumulativeWindY = cumulativeWindY / Math.sqrt(3.0D) + (secureRandom.nextGaussian() * 0.9D * verticalSmoothingMultiplier) / Math.sqrt(5.0D);
            }

            float curveStepYaw = (float) (finalYawDiff / 14.0D + cumulativeWindX * 0.008D);
            float noiseYaw = (float) (secureRandom.nextGaussian() * stochasticJitterScale * 0.6D);
            float nextEvaluatedYaw = currentYaw + curveStepYaw + noiseYaw;

            float nextEvaluatedPitch = currentPitch;
            if (!horizontalAxisOnly) {
                float curveStepPitch = (float) (finalPitchDiff / 14.0D + cumulativeWindY * 0.008D);
                float noisePitch = (float) (secureRandom.nextGaussian() * stochasticJitterScale * 0.6D);
                nextEvaluatedPitch = Mth.clamp(currentPitch + curveStepPitch + noisePitch, -89.0F, 89.0F);
            }

            if (gcdCorrectionActive) {
                nextEvaluatedYaw = applyGcdGridSnap(clientRef, currentYaw, nextEvaluatedYaw);
            }

            float finalCheckYaw = Mth.wrapDegrees(nextEvaluatedYaw - calculatedTargetYaw);
            if (Math.abs(finalCheckYaw) > 1.0f) {
                nextEvaluatedYaw = currentYaw + finalYawDiff * 0.85f;
            }

            pushHistoryBuffers(nextEvaluatedYaw, nextEvaluatedPitch);
            clientRef.player.setYRot(nextEvaluatedYaw);
            if (!horizontalAxisOnly) {
                clientRef.player.setXRot(nextEvaluatedPitch);
            }
            applyGcdHardwareTurnSimulation(clientRef, currentYaw, nextEvaluatedYaw, horizontalAxisOnly ? 0.0D : (nextEvaluatedPitch - currentPitch));
        } else {
            float nextEvaluatedYaw = currentYaw + finalYawDiff * dynamicSmooth + (float) (secureRandom.nextGaussian() * stochasticJitterScale * 0.5D);
float nextEvaluatedPitch = currentPitch;
if (!horizontalAxisOnly) {
    float nextEvaluatedPitchComputed = Mth.clamp(currentPitch + finalPitchDiff * dynamicSmooth * (float) verticalSmoothingMultiplier + (float) (secureRandom.nextGaussian() * stochasticJitterScale * 0.5D), -89.0F, 89.0F);
    nextEvaluatedPitch = nextEvaluatedPitchComputed;
}

if (gcdCorrectionActive) {
    nextEvaluatedYaw = applyGcdGridSnap(clientRef, currentYaw, nextEvaluatedYaw);
}

float finalCheckYaw = Mth.wrapDegrees(nextEvaluatedYaw - calculatedTargetYaw);
if (Math.abs(finalCheckYaw) > 1.0f) {
    nextEvaluatedYaw = currentYaw + finalYawDiff * 0.85f;
}

pushHistoryBuffers(nextEvaluatedYaw, nextEvaluatedPitch);
clientRef.player.setYRot(nextEvaluatedYaw);
if (!horizontalAxisOnly) {
    clientRef.player.setXRot(nextEvaluatedPitch);
}
applyGcdHardwareTurnSimulation(clientRef, currentYaw, nextEvaluatedYaw, horizontalAxisOnly ? 0.0D : (nextEvaluatedPitch - currentPitch));
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
return currentYaw + (float) clampedDelta;
}

private static void pushHistoryBuffers(float yawVal, float pitchVal) {
if (YAW_HISTORY_QUEUE.size() >= HISTORY_MAX_CAPACITY) {
    YAW_HISTORY_QUEUE.pollFirst();
}
YAW_HISTORY_QUEUE.offerLast(yawVal);

if (PITCH_HISTORY_QUEUE.size() >= HISTORY_MAX_CAPACITY) {
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
ENTERPRISE_REGISTRY.put("ExecutionTicks", globalExecutionCounter);
ENTERPRISE_REGISTRY.put("ActiveLockState", lockedTarget != null);
ENTERPRISE_REGISTRY.put("WindOffset", cumulativeWindX);
ENTERPRISE_REGISTRY.put("HistorySize", YAW_HISTORY_QUEUE.size());
}

public static UUID getSubsessionIdentity() {
return SUBSESSION_IDENTITY;
}
}
