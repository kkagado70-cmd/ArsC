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

    private static final Map<String, Object> SWIGHT_900_REGISTRY = new ConcurrentHashMap<>();
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

    private static double kinematicSmoothingRate = 0.45D;
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

    private static Vec3 lastKnownTargetPos = null;
    private static int memoryTicks = 0;
    private static float recoilYaw = 0.0f;
    private static float recoilPitch = 0.0f;
    private static int recoilTicks = 0;
    private static int hitCount = 0;
    private static int totalAttacks = 0;

    private static String currentWeaponProfile = "sword";
    private static int ticksSinceLastReset = 0;
    private static boolean isTargetStill = false;
    private static double roundedDistance = 0.0D;
    private static final Deque<Float> targetYawHistory = new ArrayDeque<>();
    private static float playerYawSpeed = 0.0f;
    private static boolean isTargetInWater = false;
    private static boolean isPlayerInWater = false;
    private static boolean isTargetUsingElytra = false;
    private static boolean isTargetMounted = false;

    private static int sneakTimer = 0;
    private static int combatTimer = 0;
    private static int targetSwitchDelay = 0;
    private static int idleTicks = 0;
    private static int stuckTimer = 0;

    private static double hitboxHeight = 1.8D;
    private static double horizontalDistance = 0.0D;
    private static boolean lowHealthBoost = false;
    private static boolean highHealthDefense = false;
    private static double fpsCompensation = 1.0D;
    private static boolean lookingAtTarget = false;
    private static boolean inLadder = false;
    private static double inertiaFactor = 0.5D;

    private static final Deque<Vec3> targetPositionHistory = new ArrayDeque<>();
    private static final Map<Integer, Integer> hitCountByDistance = new ConcurrentHashMap<>();
    private static final Map<Double, Integer> missCountByAngle = new ConcurrentHashMap<>();
    private static int successfulSnaps = 0;
    private static int failedSnaps = 0;
    private static double averageYawCorrection = 0.0D;
    private static int sessionTargetsEngaged = 0;

    private static boolean precisionMode = false;
    private static boolean closeQuartersMode = false;
    private static boolean shieldDetection = false;
    private static int comboCounter = 0;
    private static boolean comboActive = false;
    private static boolean focusMode = false;
    private static boolean panicMode = false;
    private static boolean patternDetection = false;
    private static boolean learningOvershoot = true;
    private static boolean knockbackCompensation = false;
    private static boolean lagCompensation = true;

    private static boolean debugMode = false;
    private static int autoResetThreshold = 100;
    private static double maxTurnDynamic = 30.0D;
    private static boolean antiSpectator = true;
    private static boolean sensitivityCalibration = true;
    private static boolean profileSaver = false;
    private static boolean parameterRandomization = true;
    private static boolean performanceLog = false;
    private static boolean healthBasedSmoothing = true;
    private static boolean fatigueReset = true;

    private static final Map<String, Double> WEAPON_SMOOTHING_PROFILES = new ConcurrentHashMap<>();
    private static final Map<String, Double> WEAPON_JITTER_PROFILES = new ConcurrentHashMap<>();
    private static final Map<String, Double> WEAPON_REACH_PROFILES = new ConcurrentHashMap<>();

    static {
        initializeMonolithRegistry();
        initializeWeaponProfiles();
    }

    private static void initializeMonolithRegistry() {
        SWIGHT_900_REGISTRY.put("SubsessionUUID", SUBSESSION_IDENTITY);
        SWIGHT_900_REGISTRY.put("Profile", "Swight-Monolith-900Lines");
        SWIGHT_900_REGISTRY.put("BypassEngine", "Human-Mime-50Improvements");
        SWIGHT_900_REGISTRY.put("InitializationEpoch", System.currentTimeMillis());
        SWIGHT_900_REGISTRY.put("BufferFlushCounter", 0);
        SWIGHT_900_REGISTRY.put("HorizontalOnlyMode", horizontalAxisOnly);
        SWIGHT_900_REGISTRY.put("WindMouseState", windMouseEngineActive);
        SWIGHT_900_REGISTRY.put("GcdCorrectionState", gcdCorrectionActive);
        SWIGHT_900_REGISTRY.put("SmoothingFactor", kinematicSmoothingRate);
        SWIGHT_900_REGISTRY.put("JitterScale", stochasticJitterScale);
        SWIGHT_900_REGISTRY.put("MaxFov", maximumFovAngle);
        SWIGHT_900_REGISTRY.put("MaxReach", maximumReachBound);
        SWIGHT_900_REGISTRY.put("ExecutionTicks", globalExecutionCounter);
    }

    private static void initializeWeaponProfiles() {
        WEAPON_SMOOTHING_PROFILES.put("sword", 0.45D);
        WEAPON_SMOOTHING_PROFILES.put("axe", 0.50D);
        WEAPON_SMOOTHING_PROFILES.put("bow", 0.65D);
        WEAPON_SMOOTHING_PROFILES.put("crossbow", 0.65D);
        WEAPON_SMOOTHING_PROFILES.put("mace", 0.55D);
        WEAPON_SMOOTHING_PROFILES.put("trident", 0.48D);

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
        initializeMonolithRegistry();
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
        ticksSinceLastReset = 0;
        comboCounter = 0;
        comboActive = false;
        sneakTimer = 0;
        combatTimer = 0;
        idleTicks = 0;
        stuckTimer = 0;
        sessionTargetsEngaged = 0;

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
        targetYawHistory.clear();
        targetPositionHistory.clear();
        purgeRegistry();
        initializeMonolithRegistry();
    }

    private static void purgeRegistry() {
        SWIGHT_900_REGISTRY.clear();
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
            new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, clientRef.player)
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

        globalExecutionCounter++;
        ticksSinceLastReset++;
        currentWeaponProfile = resolveWeaponKey(clientRef);

        if (clientRef.player.isCrouching()) sneakTimer++;
        else sneakTimer = 0;

        if (clientRef.player.hurtTime > 0) {
            combatTimer = 0;
            panicMode = true;
        } else {
            combatTimer++;
            if (combatTimer > 100) panicMode = false;
        }

        if (targetSwitchDelay > 0) targetSwitchDelay--;

        Entity target = evaluateSmartTarget(clientRef);
        if (target != null) {
            idleTicks = 0;
            lastKnownTargetPos = target.position();
            memoryTicks = 0;
            updateTargetContext(clientRef, target);
            smoothAimToTarget(clientRef, target);
        } else {
            idleTicks++;
            if (idleTicks > autoResetThreshold && fatigueReset) {
                hardResetAimSubsystem();
            }
            if (lastKnownTargetPos != null && memoryTicks < 5) {
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
        }

        if (recoilTicks > 0) {
            float currentYaw = clientRef.player.getYRot();
            float currentPitch = clientRef.player.getXRot();
            clientRef.player.setYRot(currentYaw + recoilYaw / recoilTicks);
            clientRef.player.setXRot(Mth.clamp(currentPitch + recoilPitch / recoilTicks, -89.0F, 89.0F));
            recoilTicks--;
        }

        if (parameterRandomization && ticksSinceLastReset % 100 == 0) {
            stochasticJitterScale += (secureRandom.nextDouble() - 0.5) * 0.00001D;
            stochasticJitterScale = Math.max(0.00001D, Math.min(0.0005D, stochasticJitterScale));
        }

        executeAutoCalibrationLearning();
        refreshAimRegistryState();
    }

    private static void updateTargetContext(Minecraft clientRef, Entity target) {
        isTargetStill = target.getDeltaMovement().horizontalDistanceSqr() < 0.0025D;
        roundedDistance = Math.round(clientRef.player.distanceTo(target) * 10.0) / 10.0;
        hitboxHeight = target.getBbHeight();
        horizontalDistance = Math.sqrt(Math.pow(target.getX() - clientRef.player.getX(), 2) + Math.pow(target.getZ() - clientRef.player.getZ(), 2));

        isTargetInWater = target.isInWater();
        isPlayerInWater = clientRef.player.isInWater();
        isTargetUsingElytra = target instanceof Player && ((Player) target).isFallFlying();
        isTargetMounted = target.isVehicle();

        lowHealthBoost = clientRef.player.getHealth() <= 6.0F;
        highHealthDefense = clientRef.player.getHealth() >= 14.0F;

        if (targetYawHistory.size() >= 10) targetYawHistory.pollFirst();
        targetYawHistory.offerLast(target.getYRot());

        if (targetPositionHistory.size() >= 5) targetPositionHistory.pollFirst();
        targetPositionHistory.offerLast(target.position());

        if (target instanceof LivingEntity living) {
            shieldDetection = living.isUsingItem() && living.getUseItem().getItem() == Items.SHIELD;
        }

        precisionMode = roundedDistance > 10.0D;
        closeQuartersMode = roundedDistance < 2.0D;
        focusMode = clientRef.player.getHealth() <= 4.0F;
    }

    private static Entity evaluateSmartTarget(Minecraft clientRef) {
        double profileReach = WEAPON_REACH_PROFILES.getOrDefault(currentWeaponProfile, 4.5D);

        if (lockedTarget != null) {
            double effectiveReach = profileReach;
            if (clientRef.player.distanceTo(lockedTarget) > 5.0D) effectiveReach = 7.0D;
            double distSqr = clientRef.player.distanceToSqr(lockedTarget);
            double dist = Math.sqrt(distSqr);
            double effectiveFov = dist > 5.0D ? 60.0D : maximumFovAngle;

            if (lockedTarget.isAlive() && distSqr <= (effectiveReach * effectiveReach) && computeFovCheck(clientRef, lockedTarget, effectiveFov) && verifyLineOfSight(clientRef, lockedTarget)) {
                targetLockTicks++;
                targetLostTicks = 0;
                if (targetLockTicks < 5000) return lockedTarget;
            }
            targetLostTicks++;
            if (targetLostTicks < 5) return lockedTarget;
            lockedTarget = null;
            targetLockTicks = 0;
            targetLostTicks = 0;
            targetSwitchThrottleTicks = 1 + secureRandom.nextInt(2);
            targetSwitchDelay = 2;
        }

        if (targetSwitchThrottleTicks > 0 || targetSwitchDelay > 0) return null;

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

            if (isLongRange || targetStatic || distSqr > (4.5D * 4.5D)) currentReach = 7.0D;
            else currentReach = profileReach;

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
            sessionTargetsEngaged++;
        }
        return lockedTarget;
    }

    private static boolean computeFovCheck(Minecraft clientRef, Entity entity, double maxAngle) {
        Vec3 pos = entity.position();
        double dx = pos.x - clientRef.player.getX();
        double dz = pos.z - clientRef.player.getZ();
        float targetYaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
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
        double profileSmooth = WEAPON_SMOOTHING_PROFILES.getOrDefault(currentWeaponProfile, 0.45D);
        double distanceToTarget = clientRef.player.distanceTo(target);

        double baseSmooth = profileSmooth;
        if (distanceToTarget < 2.5D) baseSmooth = 0.40D;
        else if (distanceToTarget > 5.0D) baseSmooth = 0.50D;
        else baseSmooth = profileSmooth;

        if (clientRef.player.getDeltaMovement().horizontalDistanceSqr() > 0.01D) {
            baseSmooth = Math.min(0.70D, baseSmooth + 0.10D);
        }

        if (lowHealthBoost) baseSmooth = Math.min(0.75D, baseSmooth + 0.15D);
        if (highHealthDefense) baseSmooth = Math.max(0.30D, baseSmooth - 0.05D);
        if (closeQuartersMode) baseSmooth = 0.35D;
        if (precisionMode) baseSmooth = 0.60D;

        Vec3 targetVel = target.getDeltaMovement();
        Vec3 targetLook = target.getLookAngle();
        double strafeAngle = Math.abs(Math.atan2(targetVel.z, targetVel.x) - Math.atan2(targetLook.z, targetLook.x));
        boolean isStrafing = strafeAngle > 0.5D && targetVel.horizontalDistanceSqr() > 0.1D;
        if (isStrafing) baseSmooth = Math.min(0.75D, baseSmooth + 0.15D);

        boolean isAirborne = !clientRef.player.onGround();
        double playerVelY = clientRef.player.getDeltaMovement().y;
        if (isAirborne && playerVelY > 0.0D) stochasticJitterScale *= 1.25D;
        else if (isAirborne && playerVelY < -0.1D) baseSmooth = 0.40D;

        if (sneakTimer > 10) stochasticJitterScale *= 0.8D;
        if (panicMode) stochasticJitterScale *= 1.4D;

        kinematicSmoothingRate = baseSmooth;

        saccadeTimer++;
        float maxOvershootYaw = distanceToTarget < 2.5D ? 0.02f : 0.15f;
        float maxOvershootPitch = distanceToTarget < 2.5D ? 0.02f : 0.10f;
        float decayRate = distanceToTarget < 2.5D ? 0.98f : 0.97f;

        if (saccadeTimer == 0) {
            overshootYawOffset = (float) ((secureRandom.nextDouble() - 0.5) * 1.2D);
            overshootPitchOffset = (float) ((secureRandom.nextDouble() - 0.5) * 0.5D);
        } else if (saccadeTimer == 1) {
            overshootYawOffset *= 0.2f;
            overshootPitchOffset *= 0.2f;
        } else if (saccadeTimer > 18 + secureRandom.nextInt(12)) {
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
                latency = clientRef.getConnection().getLatency();
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
                (secureRandom.nextDouble() - 0.5) * 0.06D,
                target.getBbHeight() * 0.42D,
                (secureRandom.nextDouble() - 0.5) * 0.06D
        );
    }

    private static void performAimInterpolation(Minecraft clientRef, Vec3 resolvedPos, Entity target) {
        double dx = resolvedPos.x - clientRef.player.getX();
        double dy = resolvedPos.y - clientRef.player.getEyeY();
        double dz = resolvedPos.z - clientRef.player.getZ();
        double hDist = Math.sqrt(dx * dx + dz * dz);

        float calcYaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
        float calcPitch = (float) (-(Math.atan2(dy, hDist) * (180.0 / Math.PI)));
        calcPitch = Mth.clamp(calcPitch, -89.0F, 89.0F);

        float curYaw = clientRef.player.getYRot();
        float curPitch = clientRef.player.getXRot();
        float rawYawDiff = Mth.wrapDegrees(calcYaw - curYaw);
        float rawPitchDiff = calcPitch - curPitch;

        double distToTarget = target != null ? clientRef.player.distanceTo(target) : 3.0D;
        float deadzone = distToTarget < 2.5D ? 0.3f : 0.8f;
        float distFromCenter = (float) Math.sqrt(rawYawDiff * rawYawDiff + rawPitchDiff * rawPitchDiff);
        if (distFromCenter < deadzone) return;

        if (distFromCenter > containmentRadius) {
            float pullFactor = (distFromCenter - containmentRadius) * containmentStrength;
            float pullYaw = (rawYawDiff / distFromCenter) * pullFactor;
            float pullPitch = (rawPitchDiff / distFromCenter) * pullFactor;
            rawYawDiff -= pullYaw;
            rawPitchDiff -= pullPitch;
        }

        float finalYawDiff = rawYawDiff;
        float finalPitchDiff = rawPitchDiff;
        if (distFromCenter > 1.0f) {
            finalYawDiff += overshootYawOffset;
            finalPitchDiff += overshootPitchOffset;
        }

        float t = Math.min(1.0f, distFromCenter / 10.0f);
        float eased = 1.0f - (1.0f - t) * (1.0f - t) * (1.0f - t);
        finalYawDiff *= eased;
        finalPitchDiff *= eased;

        if (windMouseEngineActive) {
            cumulativeWindX = cumulativeWindX * 0.98D + (secureRandom.nextGaussian() * 0.05D);
            if (!horizontalAxisOnly) {
                cumulativeWindY = cumulativeWindY * 0.98D + (secureRandom.nextGaussian() * 0.05D * verticalSmoothingMultiplier);
            }

            float stepYaw = (float) (finalYawDiff / 14.0D + cumulativeWindX * 0.001D);
            float noiseYaw = (float) (secureRandom.nextGaussian() * stochasticJitterScale);
            float nextYaw = curYaw + stepYaw + noiseYaw;

            float nextPitch = curPitch;
            if (!horizontalAxisOnly) {
                float stepPitch = (float) (finalPitchDiff / 14.0D + cumulativeWindY * 0.001D);
                float noisePitch = (float) (secureRandom.nextGaussian() * stochasticJitterScale);
                nextPitch = Mth.clamp(curPitch + stepPitch + noisePitch, -89.0F, 89.0F);
            }

            if (gcdCorrectionActive) nextYaw = applyGcdGridSnap(clientRef, curYaw, nextYaw);
            clientRef.player.setYRot(nextYaw);
            if (!horizontalAxisOnly) clientRef.player.setXRot(nextPitch);
            applyGcdHardwareTurnSimulation(clientRef, curYaw, nextYaw, horizontalAxisOnly ? 0.0D : (nextPitch - curPitch));
        } else {
            float nextYaw = curYaw + finalYawDiff * (float) kinematicSmoothingRate;
            float nextPitch = curPitch;
            if (!horizontalAxisOnly) nextPitch = Mth.clamp(curPitch + finalPitchDiff * (float) kinematicSmoothingRate, -89.0F, 89.0F);
            if (gcdCorrectionActive) nextYaw = applyGcdGridSnap(clientRef, curYaw, nextYaw);
            clientRef.player.setYRot(nextYaw);
            if (!horizontalAxisOnly) clientRef.player.setXRot(nextPitch);
            applyGcdHardwareTurnSimulation(clientRef, curYaw, nextYaw, horizontalAxisOnly ? 0.0D : (nextPitch - curPitch));
        }
    }

    private static float applyGcdGridSnap(Minecraft clientRef, float curYaw, float targetYaw) {
        if (clientRef.options == null) return targetYaw;
        double sens = clientRef.options.sensitivity().get() * 0.6D + 0.2D;
        double gcd = sens * sens * sens * 8.0D;
        if (gcd <= 0.0D) return targetYaw;
        double delta = targetYaw - curYaw;
        double clamped = Math.round(delta / (gcd * 0.15D)) * (gcd * 0.15D);
        return curYaw + (float) clamped;
    }

    private static void applyGcdHardwareTurnSimulation(Minecraft clientRef, float curYaw, float nextYaw, double deltaPitch) {
        if (clientRef.options == null) return;
        double sens = clientRef.options.sensitivity().get() * 0.6D + 0.2D;
        double gcd = sens * sens * sens * 8.0D;
        if (gcd > 0.0D) {
            double dY = (nextYaw - curYaw);
            clientRef.player.turn(dY / (gcd * 0.15D), deltaPitch / (gcd * 0.15D));
        }
    }

    private static void executeAutoCalibrationLearning() {
        totalAttacks++;
        if (totalAttacks >= 100) {
            double hitRate = (double) hitCount / totalAttacks;
            if (hitRate > 0.95D) {
                stochasticJitterScale += 0.00001D;
                randomMissProbability += 0.005D;
                if (comboActive) comboCounter++;
            } else if (hitRate < 0.70D) {
                stochasticJitterScale = Math.max(0.00001D, stochasticJitterScale - 0.00001D);
                randomMissProbability = Math.max(0.005D, randomMissProbability - 0.005D);
                comboCounter = 0;
                comboActive = false;
            }
            hitCount = 0;
            totalAttacks = 0;
        }
    }

    public static void registerAttackResult(boolean hit) {
        totalAttacks++;
        if (hit) {
            hitCount++;
            comboCounter++;
            if (comboCounter >= 3) {
                comboActive = true;
                kinematicSmoothingRate *= 0.95D;
            }
        } else {
            comboCounter = 0;
            comboActive = false;
        }
    }

    public static void triggerSimulatedRecoil() {
        recoilYaw = (float) ((secureRandom.nextDouble() - 0.5) * 0.3D);
        recoilPitch = (float) ((secureRandom.nextDouble() - 0.5) * 0.2D);
        recoilTicks = 3;
    }

    private static void refreshAimRegistryState() {
        SWIGHT_900_REGISTRY.put("ExecutionTicks", globalExecutionCounter);
        SWIGHT_900_REGISTRY.put("ActiveLockState", lockedTarget != null);
        SWIGHT_900_REGISTRY.put("WindOffset", cumulativeWindX);
        SWIGHT_900_REGISTRY.put("HistorySize", YAW_HISTORY_QUEUE.size());
        SWIGHT_900_REGISTRY.put("ComboCounter", comboCounter);
        SWIGHT_900_REGISTRY.put("ComboActive", comboActive);
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
        kinematicSmoothingRate = 0.45D;
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

    public static int getComboCounter() {
        return comboCounter;
    }

    public static boolean isComboActive() {
        return comboActive;
    }
                }
