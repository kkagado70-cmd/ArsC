package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AutoMace extends ClientBase.Module {

    public static final String FILE_NAME = "AutoMace.java";
    public static boolean enabled = false;

    private static final SecureRandom secureRandom = new SecureRandom();
    private static final Map<String, Object> MACE_MONOLITH_REGISTRY = new ConcurrentHashMap<>();
    private static final UUID SUBSESSION_IDENTITY = UUID.randomUUID();

    private static final Deque<Double>  VELOCITY_VECTOR_QUEUE  = new ArrayDeque<>();
    private static final Deque<Long>    SMASH_TIMING_QUEUE     = new ArrayDeque<>();
    private static final Deque<Double>  ACCELERATION_DEQUE     = new ArrayDeque<>();
    private static final Deque<Double>  FALL_DISTANCE_DEQUE    = new ArrayDeque<>();
    private static final Deque<Float>   PITCH_HISTORY_QUEUE    = new ArrayDeque<>();
    private static final Deque<Float>   YAW_HISTORY_QUEUE      = new ArrayDeque<>();
    private static final Deque<Vec3>    POSITION_HISTORY_DEQUE = new ArrayDeque<>();
    private static final Deque<Long>    SESSION_EPOCH_DEQUE    = new ArrayDeque<>();
    private static final Deque<Double>  IMPACT_FORCE_DEQUE     = new ArrayDeque<>();
    private static final Deque<Integer> COOLDOWN_SAMPLE_DEQUE  = new ArrayDeque<>();
    private static final Deque<Double>  AIM_ERROR_DEQUE        = new ArrayDeque<>();
    private static final Deque<Double>  PEAK_Y_LOG             = new ArrayDeque<>();
    private static final int HISTORY_MAX_CAPACITY = 8192;

    private static double  maxSwingRange            = 7.0D;
    private static double  maxAimDistance           = 30.0D;
    private static double  minFallDistance          = 1.0D;
    private static float   hyperSnapSpeed           = 0.99F;
    private static long    executionTicks           = 0L;
    private static LivingEntity lockedMaceTarget    = null;
    private static int     smashCooldown            = 0;
    private static boolean windChargeDetection      = true;
    private static boolean elytraDiveCheck          = true;
    private static boolean enterpriseTelemetryActive = true;
    private static int     autoCalibrationTimer     = 0;
    private static double  currentFatigueLevel      = 0.0D;
    private static double  fatigueScalar            = 0.001D;
    private static double  fatigueRecovery          = 0.0003D;
    private static boolean errorInjectionActive     = true;
    private static double  randomMissProbability    = 0.01D;
    private static long    subsessionEpochTracker   = System.currentTimeMillis();
    private static boolean adaptiveFovScalingActive = true;
    private static boolean strictRaycastVerification = true;
    private static boolean kineticInertiaModelActive = true;
    private static boolean rotationalFrictionActive  = true;
    private static double  massSimulatedDrag         = 0.02D;
    private static double  frictionCoefficient       = 0.04D;
    private static boolean deepTelemetryAuditActive  = true;
    private static int     telemetryFlushIntervalTicks = 300;
    private static long    lastTelemetryFlushEpoch   = 0L;

    private static double peakY            = Double.NEGATIVE_INFINITY;
    private static boolean hasPeaked       = false;
    private static int     aimTicks        = 0;
    private static int     trackTicks      = 0;
    private static boolean isTracking      = false;
    private static int     savedSlot       = -1;
    private static float   aimYawTolerance = 4.5f;
    private static float   aimPitchTolerance = 5.0f;
    private static int     maxAimTicksVal  = 10;

    private static double sessionMetricAlpha   = 0.5D;
    private static double sessionMetricBeta    = 0.5D;
    private static double sessionMetricGamma   = 0.5D;
    private static double sessionMetricDelta   = 0.5D;
    private static double sessionMetricEpsilon = 0.5D;
    private static double sessionMetricZeta    = 0.5D;
    private static double sessionMetricEta     = 0.5D;
    private static double sessionMetricTheta   = 0.5D;
    private static double sessionMetricIota    = 0.5D;
    private static double sessionMetricKappa   = 0.5D;

    static {
        MACE_MONOLITH_REGISTRY.put("SubsessionUUID", SUBSESSION_IDENTITY);
        MACE_MONOLITH_REGISTRY.put("Profile", "AutoMace-KineticMonolith-Enterprise");
        MACE_MONOLITH_REGISTRY.put("MaxSwingRange", maxSwingRange);
        MACE_MONOLITH_REGISTRY.put("MinFallDistance", minFallDistance);
        initializeMaceMonolithRegistry();
    }

    private static void initializeMaceMonolithRegistry() {
        MACE_MONOLITH_REGISTRY.put("WindChargeDetection", windChargeDetection);
        MACE_MONOLITH_REGISTRY.put("ElytraDiveCheck", elytraDiveCheck);
        MACE_MONOLITH_REGISTRY.put("FatigueLevel", currentFatigueLevel);
        MACE_MONOLITH_REGISTRY.put("KineticInertia", kineticInertiaModelActive);
        MACE_MONOLITH_REGISTRY.put("AdaptiveFov", adaptiveFovScalingActive);
    }

    public AutoMace() { super("AutoMace"); this.enabled = false; }

    @Override
    public boolean isEnabled() { return enabled; }

    @Override
    public void toggle() {
        enabled = !enabled;
        super.enabled = enabled;
        if (!enabled) {
            lockedMaceTarget = null;
            isTracking       = false;
            aimTicks         = 0;
            trackTicks       = 0;
            executionTicks   = 0L;
            peakY            = Double.NEGATIVE_INFINITY;
            hasPeaked        = false;
            clearAllMaceQueues();
        }
    }

    @Override
    public void tick(Minecraft client) { onTick(client); }

    public static void onTick(Minecraft client) {
        if (!enabled || client.player == null || client.level == null || !client.player.isAlive()) return;

        executionTicks++;
        autoCalibrationTimer++;
        currentFatigueLevel = Math.max(0.0D, currentFatigueLevel - fatigueRecovery);

        if (autoCalibrationTimer >= 250) {
            autoCalibrationTimer = 0;
            executeAutoCalibrationRoutine();
        }

        if (smashCooldown > 0) smashCooldown--;

        RotationManager.samplePlayerGcd(client);

        double vY = client.player.getDeltaMovement().y;
        pushVelocityHistory(vY);
        pushFallDistanceHistory(client.player.fallDistance);
        trackPeak(client);

        lockedMaceTarget = null;
        double minDst = (maxAimDistance * maxAimDistance) + 1.0D;
        for (Player p : client.level.players()) {
            if (p == client.player || !p.isAlive() || p.isSpectator() || p.isCreative()) continue;
            double dst = client.player.distanceToSqr(p);
            if (dst > (maxAimDistance * maxAimDistance)) continue;
            if (strictRaycastVerification && !verifyLineOfSight(client, p)) continue;
            if (dst < minDst) { minDst = dst; lockedMaceTarget = p; }
        }

        if (lockedMaceTarget == null) { isTracking = false; updateRegistryState(); return; }

        double fallDist    = client.player.fallDistance;
        boolean elytra     = client.player.isFallFlying();
        boolean windMoment = windChargeDetection && vY > 0.75D;
        boolean peaked     = hasPeaked || (peakY - client.player.getY() >= minFallDistance);
        boolean diving     = (!client.player.onGround() && peaked && vY < -0.05D)
                          || fallDist >= minFallDistance
                          || (elytraDiveCheck && elytra)
                          || windMoment;

        if (!diving) { isTracking = false; updateRegistryState(); return; }

        int mSlot = -1;
        for (int i = 0; i < 9; i++) {
            if (client.player.getInventory().getItem(i).getItem() == Items.MACE) { mSlot = i; break; }
        }
        if (mSlot < 0) { updateRegistryState(); return; }

        if (client.player.getInventory().getSelectedSlot() != mSlot) {
            if (savedSlot < 0) savedSlot = client.player.getInventory().getSelectedSlot();
            InventoryManager.selectSlot(client, mSlot);
            updateRegistryState(); return;
        }

        Vec3 center = lockedMaceTarget.position().add(0.0D, lockedMaceTarget.getBbHeight() * 0.45D, 0.0D);

        if (!isTracking) {
            aimTicks  = 0;
            trackTicks = 0;
            isTracking = true;
        }

        aimTicks++;
        trackTicks++;

        double aimErr = Math.sqrt(
            Math.pow(RotationManager.computeYawError(client, center), 2) +
            Math.pow(RotationManager.computePitchError(client, center), 2)
        );
        pushAimError(aimErr);

        if (kineticInertiaModelActive) {
            float factor = aimErr > 20.0D ? hyperSnapSpeed :
                          aimErr > 8.0D   ? 0.78f          :
                          (0.28f + 0.5f * (float)(aimErr / 8.0D));
            if (aimTicks < 4) factor *= (aimTicks / 4.0f);
            RotationManager.setEasingMode(aimErr > 20.0D ? RotationManager.EasingMode.SWIGHT_HIGH_SENS : RotationManager.EasingMode.KINEMATIC_SPRING);
            RotationManager.smoothTo(client, center, factor);
        } else {
            RotationManager.smoothTo(client, center, hyperSnapSpeed);
        }

        double dist  = client.player.distanceTo(lockedMaceTarget);
        float  scale = client.player.getAttackStrengthScale(0.0F);

        if (dist <= maxSwingRange && scale >= (0.55D + currentFatigueLevel) && smashCooldown == 0) {
            if (dist > 5.0D && secureRandom.nextDouble() >= 0.75D) {
                updateRegistryState(); return;
            }
            if (errorInjectionActive && secureRandom.nextDouble() < randomMissProbability) {
                smashCooldown = 2 + secureRandom.nextInt(2);
                updateRegistryState(); return;
            }
            totalSmashExecution(client);
        }

        if (trackTicks > 200 || aimTicks > 200) {
            isTracking = false; aimTicks = 0; trackTicks = 0;
            if (savedSlot >= 0) { InventoryManager.restoreSavedSlot(client); savedSlot = -1; }
        }

        updateRegistryState();
        executeSubsystemSanitation();
    }

    private static void trackPeak(Minecraft client) {
        double y  = client.player.getY();
        double vy = client.player.getDeltaMovement().y;
        if (client.player.onGround()) { peakY = y; hasPeaked = false; return; }
        if (vy > 0) { peakY = Math.max(peakY == Double.NEGATIVE_INFINITY ? y : peakY, y); }
        else hasPeaked = true;
        pushPeakYLog(peakY);
    }

    private static void totalSmashExecution(Minecraft client) {
        InteractionManager.simulateClickAttack(client);
        smashCooldown = 2 + secureRandom.nextInt(2);
        currentFatigueLevel = Math.min(1.0D, currentFatigueLevel + fatigueScalar);
        long now = System.currentTimeMillis();
        pushSmashTiming(now);
        pushImpactForce(client.player.getDeltaMovement().horizontalDistance());
        pushCooldownSample(smashCooldown);
        if (savedSlot >= 0) { InventoryManager.restoreSavedSlot(client); savedSlot = -1; }
        isTracking = false; aimTicks = 0; trackTicks = 0;
    }

    private static boolean verifyLineOfSight(Minecraft client, Entity target) {
        if (client.player == null || target == null || client.level == null) return false;
        Vec3 start = client.player.getEyePosition();
        Vec3 end   = target.getEyePosition();
        BlockHitResult hit = client.level.clip(
            new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, client.player));
        return hit.getType() == HitResult.Type.MISS;
    }

    private static void pushVelocityHistory(double v) { if (VELOCITY_VECTOR_QUEUE.size() >= HISTORY_MAX_CAPACITY) VELOCITY_VECTOR_QUEUE.pollFirst(); VELOCITY_VECTOR_QUEUE.offerLast(v); }
    private static void pushFallDistanceHistory(double d) { if (FALL_DISTANCE_DEQUE.size() >= HISTORY_MAX_CAPACITY) FALL_DISTANCE_DEQUE.pollFirst(); FALL_DISTANCE_DEQUE.offerLast(d); }
    private static void pushSmashTiming(long t) { if (SMASH_TIMING_QUEUE.size() >= HISTORY_MAX_CAPACITY) SMASH_TIMING_QUEUE.pollFirst(); SMASH_TIMING_QUEUE.offerLast(t); }
    private static void pushImpactForce(double f) { if (IMPACT_FORCE_DEQUE.size() >= HISTORY_MAX_CAPACITY) IMPACT_FORCE_DEQUE.pollFirst(); IMPACT_FORCE_DEQUE.offerLast(f); }
    private static void pushCooldownSample(int c) { if (COOLDOWN_SAMPLE_DEQUE.size() >= HISTORY_MAX_CAPACITY) COOLDOWN_SAMPLE_DEQUE.pollFirst(); COOLDOWN_SAMPLE_DEQUE.offerLast(c); }
    private static void pushAimError(double e) { if (AIM_ERROR_DEQUE.size() >= HISTORY_MAX_CAPACITY) AIM_ERROR_DEQUE.pollFirst(); AIM_ERROR_DEQUE.offerLast(e); }
    private static void pushPeakYLog(double y) { if (PEAK_Y_LOG.size() >= HISTORY_MAX_CAPACITY) PEAK_Y_LOG.pollFirst(); PEAK_Y_LOG.offerLast(y); }

    private static void executeAutoCalibrationRoutine() {
        maxSwingRange     = 7.0D + (secureRandom.nextDouble() - 0.5) * 0.05D;
        currentFatigueLevel = Math.max(0.0D, currentFatigueLevel - 0.1D);
        randomMissProbability = Math.max(0.005D, randomMissProbability + (secureRandom.nextDouble() - 0.5) * 0.002D);
        sessionMetricAlpha  = 0.48D + secureRandom.nextDouble() * 0.04D;
        MACE_MONOLITH_REGISTRY.put("AutoCalibrated", System.currentTimeMillis());
    }

    private static void updateRegistryState() {
        MACE_MONOLITH_REGISTRY.put("ExecutionTicks",    executionTicks);
        MACE_MONOLITH_REGISTRY.put("ActiveTarget",      lockedMaceTarget != null);
        MACE_MONOLITH_REGISTRY.put("SmashCooldown",     smashCooldown);
        MACE_MONOLITH_REGISTRY.put("FatigueLevel",      currentFatigueLevel);
        MACE_MONOLITH_REGISTRY.put("VelocityQueueSize", VELOCITY_VECTOR_QUEUE.size());
        MACE_MONOLITH_REGISTRY.put("IsTracking",        isTracking);
        MACE_MONOLITH_REGISTRY.put("PeakY",             peakY);
    }

    private static void executeSubsystemSanitation() {
        if (executionTicks > 100000000L) executionTicks = 0L;
        if (MACE_MONOLITH_REGISTRY.size() > 250) { purgeRegistry(); initializeMaceMonolithRegistry(); }
    }

    private static void purgeRegistry() {
        MACE_MONOLITH_REGISTRY.clear();
        MACE_MONOLITH_REGISTRY.put("SubsessionUUID", SUBSESSION_IDENTITY);
    }

    public static boolean verifyMaceSubsystemHealth() { return enabled && SUBSESSION_IDENTITY != null; }
    public static long    getExecutionTicks()          { return executionTicks; }
    public static void    setMaxSwingRange(double r)   { maxSwingRange = r; MACE_MONOLITH_REGISTRY.put("MaxSwingRange", r); }
    public static double  getMaxSwingRange()           { return maxSwingRange; }
    public static void    setMaxAimDistance(double d)  { maxAimDistance = d; MACE_MONOLITH_REGISTRY.put("MaxAimDistance", d); }
    public static double  getMaxAimDistance()          { return maxAimDistance; }
    public static void    setMinFallDistance(double d) { minFallDistance = d; MACE_MONOLITH_REGISTRY.put("MinFallDistance", d); }
    public static double  getMinFallDistance()         { return minFallDistance; }
    public static void    setHyperSnapSpeed(float s)   { hyperSnapSpeed = s; MACE_MONOLITH_REGISTRY.put("HyperSnapSpeed", s); }
    public static float   getHyperSnapSpeed()          { return hyperSnapSpeed; }
    public static void    setWindChargeDetection(boolean b) { windChargeDetection = b; MACE_MONOLITH_REGISTRY.put("WindChargeDetection", b); }
    public static boolean isWindChargeDetectionActive() { return windChargeDetection; }
    public static void    setElytraDiveCheck(boolean b) { elytraDiveCheck = b; MACE_MONOLITH_REGISTRY.put("ElytraDiveCheck", b); }
    public static boolean isElytraDiveCheckActive()    { return elytraDiveCheck; }
    public static int     getVelocityQueueSize()       { return VELOCITY_VECTOR_QUEUE.size(); }
    public static int     getSmashTimingQueueSize()    { return SMASH_TIMING_QUEUE.size(); }
    public static double  getFatigueLevel()            { return currentFatigueLevel; }
    public static LivingEntity getLockedTarget()       { return lockedMaceTarget; }
    public static UUID    getSubsessionIdentity()      { return SUBSESSION_IDENTITY; }

    public static void clearAllMaceQueues() {
        VELOCITY_VECTOR_QUEUE.clear();
        SMASH_TIMING_QUEUE.clear();
        ACCELERATION_DEQUE.clear();
        FALL_DISTANCE_DEQUE.clear();
        PITCH_HISTORY_QUEUE.clear();
        YAW_HISTORY_QUEUE.clear();
        POSITION_HISTORY_DEQUE.clear();
        SESSION_EPOCH_DEQUE.clear();
        IMPACT_FORCE_DEQUE.clear();
        COOLDOWN_SAMPLE_DEQUE.clear();
        AIM_ERROR_DEQUE.clear();
        PEAK_Y_LOG.clear();
    }
}
