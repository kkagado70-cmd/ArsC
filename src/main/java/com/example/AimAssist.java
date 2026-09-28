package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AimAssist {

    public static final String FILE_NAME = "AimAssist.java";
    public static boolean enabled = false;

    private static final SecureRandom secureRandom = new SecureRandom();
    private static final Map<String, Object> AIM_REGISTRY = new ConcurrentHashMap<>();
    private static final UUID SUBSESSION_IDENTITY = UUID.randomUUID();

    private static Entity lockedTarget           = null;
    private static Entity previousLockedTarget   = null;
    private static int    targetLockTicks        = 0;
    private static int    switchCooldown         = 0;
    private static long   globalExecutionCounter = 0L;
    private static int    autoCalibrationCounter = 0;

    private static final KalmanFilter1D kalmanX = new KalmanFilter1D(0.008D, 0.08D);
    private static final KalmanFilter1D kalmanY = new KalmanFilter1D(0.008D, 0.08D);
    private static final KalmanFilter1D kalmanZ = new KalmanFilter1D(0.008D, 0.08D);

    private static Vec3 previousTargetVelocity     = Vec3.ZERO;
    private static Vec3 previousTargetAcceleration = Vec3.ZERO;

    private static final Deque<Double> YAW_ERROR_HISTORY   = new ArrayDeque<>();
    private static final Deque<Double> PITCH_ERROR_HISTORY  = new ArrayDeque<>();
    private static final Deque<Double> SPEED_HISTORY        = new ArrayDeque<>();
    private static final Deque<Long>   SWITCH_EPOCH_HISTORY = new ArrayDeque<>();
    private static final Deque<Double> JITTER_MAGNITUDE_LOG = new ArrayDeque<>();
    private static final Deque<Vec3>   TARGET_POS_HISTORY   = new ArrayDeque<>();
    private static final int HISTORY_MAX_CAPACITY = 8192;

    private static double kinematicSmoothingRate = 0.45D;
    private static float  maximumFovAngle        = 180.0F;
    private static double maximumReachBound      = 7.0D;
    private static double currentFatigueLevel    = 0.0D;
    private static double fatigueScalar          = 0.00012D;
    private static double fatigueRecoveryRate    = 0.0008D;
    private static boolean adaptiveSmoothing     = true;
    private static boolean pingCompensation      = true;
    private static boolean losValidation         = true;
    private static boolean saccadeActive         = false;
    private static int     saccadeTimer          = 0;
    private static float   saccadeYawOffset      = 0.0f;
    private static float   saccadePitchOffset    = 0.0f;
    private static double  overshootDecay        = 0.91D;
    private static int     overshootInterval     = 20;
    private static boolean microAdjustActive     = true;
    private static double  microAdjustMagnitude  = 0.0004D;
    private static double  sessionAimAccuracy    = 1.0D;
    private static long    sessionAimHits        = 0L;
    private static long    sessionAimMisses      = 0L;
    private static int     consecutiveMissCount  = 0;
    private static double  reachScalarByWeapon   = 1.0D;

    private static final Map<String, Double> WEAPON_SMOOTHING = new ConcurrentHashMap<>();
    private static final Map<String, Double> WEAPON_REACH     = new ConcurrentHashMap<>();
    private static final Map<String, Double> WEAPON_NOISE     = new ConcurrentHashMap<>();

    static {
        AIM_REGISTRY.put("SubsessionUUID", SUBSESSION_IDENTITY);
        AIM_REGISTRY.put("Profile", "AimAssist-KinematicKalman-Enterprise");

        WEAPON_SMOOTHING.put("sword",    0.45D);
        WEAPON_SMOOTHING.put("axe",      0.50D);
        WEAPON_SMOOTHING.put("mace",     0.42D);
        WEAPON_SMOOTHING.put("trident",  0.45D);
        WEAPON_SMOOTHING.put("bow",      0.60D);
        WEAPON_SMOOTHING.put("crossbow", 0.60D);

        WEAPON_REACH.put("sword",    7.0D);
        WEAPON_REACH.put("axe",      7.0D);
        WEAPON_REACH.put("mace",     7.0D);
        WEAPON_REACH.put("trident",  7.0D);
        WEAPON_REACH.put("bow",      64.0D);
        WEAPON_REACH.put("crossbow", 64.0D);

        WEAPON_NOISE.put("sword",    0.0003D);
        WEAPON_NOISE.put("axe",      0.0003D);
        WEAPON_NOISE.put("mace",     0.0002D);
        WEAPON_NOISE.put("trident",  0.0003D);
        WEAPON_NOISE.put("bow",      0.0006D);
        WEAPON_NOISE.put("crossbow", 0.0005D);
    }

    public AimAssist() {}

    public static void toggle() {
        enabled = !enabled;
        if (!enabled) resetFilters();
    }

    public static void onTick(Minecraft client) {
        if (!enabled || client.player == null || client.level == null) return;
        if (!client.player.isAlive()) return;
        if (!isHoldingWeapon(client)) { lockedTarget = null; return; }
        if (ShieldBreaker.isShieldStunActive()) return;

        globalExecutionCounter++;
        autoCalibrationCounter++;

        if (autoCalibrationCounter >= 300) {
            autoCalibrationCounter = 0;
            executeAutoCalibrationRoutine();
        }

        currentFatigueLevel = Math.max(0.0D, currentFatigueLevel - fatigueRecoveryRate);

        RotationManager.samplePlayerGcd(client);

        if (switchCooldown > 0) switchCooldown--;

        Entity target = evaluateSmartTarget(client);
        if (target != previousLockedTarget) {
            resetFilters();
            previousLockedTarget = target;
            switchCooldown = 3 + secureRandom.nextInt(4);
        }

        if (target != null) {
            smoothAimToTarget(client, target);
            currentFatigueLevel = Math.min(1.0D, currentFatigueLevel + fatigueScalar);
        } else {
            lockedTarget = null;
        }

        updateAimRegistry(target);
    }

    private static Entity evaluateSmartTarget(Minecraft client) {
        String weaponKey = resolveWeaponKey(client);
        double reach = WEAPON_REACH.getOrDefault(weaponKey, maximumReachBound) * reachScalarByWeapon;

        if (lockedTarget != null && switchCooldown > 0) {
            double dst = client.player.distanceToSqr(lockedTarget);
            if (((LivingEntity) lockedTarget).isAlive() && dst <= (reach * reach)) {
                if (!losValidation || verifyLineOfSight(client, lockedTarget)) {
                    targetLockTicks++;
                    return lockedTarget;
                }
            }
            lockedTarget = null;
            targetLockTicks = 0;
        }

        Entity best = null;
        double minAngle = (double) maximumFovAngle / 2.0;

        for (Entity e : client.level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity living)) continue;
            if (living == client.player || !living.isAlive()) continue;
            if (living instanceof Player p && (p.isSpectator() || p.isCreative())) continue;
            double dst = client.player.distanceToSqr(living);
            if (dst > (reach * reach)) continue;
            if (losValidation && !verifyLineOfSight(client, living)) continue;
            double angle = computeFovAngle(client, living.getEyePosition());
            if (angle < minAngle) { minAngle = angle; best = living; }
        }

        if (best != null) { lockedTarget = best; targetLockTicks = 0; }
        return lockedTarget;
    }

    private static void smoothAimToTarget(Minecraft client, Entity target) {
        double kx = kalmanX.update(target.getX());
        double ky = kalmanY.update(target.getY() + target.getBbHeight() * 0.42D);
        double kz = kalmanZ.update(target.getZ());

        Vec3 currentVel  = target.getDeltaMovement();
        Vec3 acceleration = currentVel.subtract(previousTargetVelocity);
        Vec3 jerk         = acceleration.subtract(previousTargetAcceleration);

        long latency = 50L;
        if (pingCompensation && client.getConnection() != null) {
            try {
                PlayerInfo info = client.getConnection().getPlayerInfo(client.player.getUUID());
                if (info != null) latency = info.getLatency();
            } catch (Exception ignored) {}
        }
        double pingComp = (latency / 50.0) * 0.018D;

        Vec3 predicted = new Vec3(kx, ky, kz)
            .add(currentVel.scale(2.0D * 0.05D + pingComp))
            .add(acceleration.scale(0.5D * 4.0D * 0.0025D))
            .add(jerk.scale(0.125D * 0.000125D));

        previousTargetVelocity     = currentVel;
        previousTargetAcceleration = acceleration;

        pushTargetPosHistory(new Vec3(kx, ky, kz));

        updateSaccade();
        predicted = predicted.add(saccadeYawOffset * 0.01, saccadePitchOffset * 0.01, 0);

        String weaponKey = resolveWeaponKey(client);
        double smooth = WEAPON_SMOOTHING.getOrDefault(weaponKey, kinematicSmoothingRate);
        double noise  = WEAPON_NOISE.getOrDefault(weaponKey, microAdjustMagnitude);

        double dist = client.player.distanceTo(target);
        if (adaptiveSmoothing) {
            if (dist < 2.5D) smooth *= 0.75D;
            else if (dist > 5.5D) smooth = Math.min(0.98D, smooth + 0.08D);
        }

        smooth = Math.max(0.05D, smooth - currentFatigueLevel * 0.12D);

        if (microAdjustActive) {
            predicted = predicted.add(
                secureRandom.nextGaussian() * noise,
                secureRandom.nextGaussian() * noise * 0.6,
                0
            );
        }

        double yawErr   = Math.abs(RotationManager.computeYawError(client, predicted));
        double pitchErr = Math.abs(RotationManager.computePitchError(client, predicted));
        pushErrorHistory(yawErr, pitchErr);

        RotationManager.EasingMode mode = dist < 3.0D ? RotationManager.EasingMode.KINEMATIC_SPRING
                        : yawErr > 25.0D ? RotationManager.EasingMode.SWIGHT_HIGH_SENS
                        : RotationManager.EasingMode.EASE_OUT_EXPO;
        RotationManager.setEasingMode(mode);
        RotationManager.smoothTo(client, predicted, (float) smooth);

        if (yawErr < 2.0D && pitchErr < 2.0D) {
            sessionAimHits++;
        } else {
            sessionAimMisses++;
            consecutiveMissCount++;
        }
        updateAccuracy();
    }

    private static void updateSaccade() {
        saccadeTimer++;
        if (saccadeTimer > overshootInterval + secureRandom.nextInt(15)) {
            saccadeTimer = 0;
            saccadeYawOffset   = (float)((secureRandom.nextDouble() - 0.5) * 1.2D);
            saccadePitchOffset = (float)((secureRandom.nextDouble() - 0.5) * 0.9D);
            saccadeActive = true;
        } else {
            saccadeYawOffset   *= (float) overshootDecay;
            saccadePitchOffset *= (float) overshootDecay;
            if (Math.abs(saccadeYawOffset) < 0.02f) { saccadeYawOffset = 0.0f; saccadeActive = false; }
            if (Math.abs(saccadePitchOffset) < 0.02f) saccadePitchOffset = 0.0f;
        }
    }

    private static void executeAutoCalibrationRoutine() {
        kinematicSmoothingRate = 0.42D + (secureRandom.nextDouble() - 0.5) * 0.04D;
        currentFatigueLevel    = Math.max(0.0D, currentFatigueLevel - 0.05D);
        consecutiveMissCount   = Math.max(0, consecutiveMissCount - 2);
        AIM_REGISTRY.put("AutoCalibrated", System.currentTimeMillis());
    }

    private static void updateAccuracy() {
        long total = sessionAimHits + sessionAimMisses;
        if (total > 0) sessionAimAccuracy = (double) sessionAimHits / total;
    }

    private static void updateAimRegistry(Entity target) {
        AIM_REGISTRY.put("LockedTarget", target != null);
        AIM_REGISTRY.put("LockTicks", targetLockTicks);
        AIM_REGISTRY.put("FatigueLevel", currentFatigueLevel);
        AIM_REGISTRY.put("Accuracy", sessionAimAccuracy);
        AIM_REGISTRY.put("SaccadeActive", saccadeActive);
        AIM_REGISTRY.put("SampledGcd", RotationManager.getSampledGcd());
        AIM_REGISTRY.put("GlobalExecCount", globalExecutionCounter);
    }

    private static boolean verifyLineOfSight(Minecraft client, Entity target) {
        if (client.player == null || target == null || client.level == null) return false;
        Vec3 start = client.player.getEyePosition();
        Vec3 end   = target.getEyePosition();
        BlockHitResult hit = client.level.clip(
            new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, client.player));
        return hit.getType() == HitResult.Type.MISS;
    }

    private static double computeFovAngle(Minecraft client, Vec3 targetEye) {
        Vec3 look = client.player.getLookAngle();
        Vec3 dir  = targetEye.subtract(client.player.getEyePosition()).normalize();
        double dot = Mth.clamp(look.dot(dir), -1.0, 1.0);
        return Math.toDegrees(Math.acos(dot));
    }

    private static String resolveWeaponKey(Minecraft client) {
        if (client.player == null) return "sword";
        ItemStack stack = client.player.getMainHandItem();
        if (stack.isEmpty()) return "sword";
        String name = stack.getItem().getDescriptionId().toLowerCase();
        if (name.contains("crossbow")) return "crossbow";
        if (name.contains("bow")) return "bow";
        if (name.contains("axe")) return "axe";
        if (name.contains("mace")) return "mace";
        if (name.contains("trident")) return "trident";
        return "sword";
    }

    private static boolean isHoldingWeapon(Minecraft client) {
        if (client.player == null) return false;
        ItemStack s = client.player.getMainHandItem();
        if (s.isEmpty()) return false;
        String name = s.getItem().getDescriptionId().toLowerCase();
        return name.contains("sword") || name.contains("axe") || name.contains("trident")
            || name.contains("mace") || name.contains("bow") || name.contains("crossbow");
    }

    private static void pushErrorHistory(double ye, double pe) {
        if (YAW_ERROR_HISTORY.size() >= HISTORY_MAX_CAPACITY)   YAW_ERROR_HISTORY.pollFirst();
        if (PITCH_ERROR_HISTORY.size() >= HISTORY_MAX_CAPACITY) PITCH_ERROR_HISTORY.pollFirst();
        YAW_ERROR_HISTORY.offerLast(ye);
        PITCH_ERROR_HISTORY.offerLast(pe);
    }

    private static void pushTargetPosHistory(Vec3 pos) {
        if (TARGET_POS_HISTORY.size() >= HISTORY_MAX_CAPACITY) TARGET_POS_HISTORY.pollFirst();
        TARGET_POS_HISTORY.offerLast(pos);
    }

    public static void resetFilters() {
        lockedTarget = null;
        previousLockedTarget = null;
        targetLockTicks = 0;
        kalmanX.reset();
        kalmanY.reset();
        kalmanZ.reset();
        previousTargetVelocity     = Vec3.ZERO;
        previousTargetAcceleration = Vec3.ZERO;
        saccadeYawOffset   = 0.0f;
        saccadePitchOffset = 0.0f;
        saccadeActive      = false;
        saccadeTimer       = 0;
        consecutiveMissCount = 0;
    }

    public static boolean isLockedOnTarget() { return lockedTarget != null; }
    public static Entity getLockedTarget()   { return lockedTarget; }
    public static double getSessionAccuracy() { return sessionAimAccuracy; }
    public static UUID getSubsessionIdentity() { return SUBSESSION_IDENTITY; }

    public static void setMaximumFov(float fov)          { maximumFovAngle = fov; }
    public static void setMaximumReach(double reach)     { maximumReachBound = reach; }
    public static void setKinematicSmoothing(double s)   { kinematicSmoothingRate = s; }
    public static void setAdaptiveSmoothing(boolean b)   { adaptiveSmoothing = b; }
    public static void setPingCompensation(boolean b)    { pingCompensation = b; }
    public static void setLosValidation(boolean b)       { losValidation = b; }
    public static void setMicroAdjust(boolean b, double m) { microAdjustActive = b; microAdjustMagnitude = m; }
    public static void setSaccadeInterval(int i)         { overshootInterval = Math.max(5, i); }
    public static void setFatigueScalar(double s)        { fatigueScalar = Math.max(0, s); }
    public static double getFatigueLevel()               { return currentFatigueLevel; }
    public static int getTargetLockTicks()               { return targetLockTicks; }
    public static long getGlobalExecCount()              { return globalExecutionCounter; }
    public static int getYawErrorHistorySize()           { return YAW_ERROR_HISTORY.size(); }
    public static double getLastYawError()               { return YAW_ERROR_HISTORY.isEmpty() ? 0 : YAW_ERROR_HISTORY.peekLast(); }
    public static double getLastPitchError()             { return PITCH_ERROR_HISTORY.isEmpty() ? 0 : PITCH_ERROR_HISTORY.peekLast(); }

    private static class KalmanFilter1D {
        private double q, r, p, k, x;
        public KalmanFilter1D(double q, double r) { this.q = q; this.r = r; this.p = 1.0D; this.x = 0.0D; }
        public double update(double measurement) {
            p += q;
            k  = p / (p + r);
            x += k * (measurement - x);
            p *= (1.0D - k);
            return x;
        }
        public void reset() { p = 1.0D; x = 0.0D; }
    }

    /**
     * Registra este módulo no ClientTickEvents.END_CLIENT_TICK do Fabric.
     * Chamar uma vez durante a inicialização do mod (ex: ClientModInitializer.onInitializeClient()).
     *
     * Exemplo:
     *   AimAssist.register();
     */
    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(AimAssist::onTick);
    }

}