package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
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

public class AutoMace extends ClientBase.Module {
    public static final String FILE_NAME = "AutoMace.java";
    public static boolean enabled = false;
    private static final SecureRandom secureRandom = new SecureRandom();

    private static final Map<String, Object> MACE_MONOLITH_REGISTRY = new ConcurrentHashMap<>();
    private static final UUID SUBSESSION_IDENTITY = UUID.randomUUID();
    private static final Deque<Double> VELOCITY_VECTOR_QUEUE = new ArrayDeque<>();
    private static final Deque<Long> SMASH_TIMING_QUEUE = new ArrayDeque<>();
    private static final Deque<Double> ACCELERATION_DEQUE = new ArrayDeque<>();
    private static final Deque<Double> FALL_DISTANCE_DEQUE = new ArrayDeque<>();
    private static final Deque<Float> PITCH_HISTORY_QUEUE = new ArrayDeque<>();
    private static final Deque<Float> YAW_HISTORY_QUEUE = new ArrayDeque<>();
    private static final Deque<Vec3> POSITION_HISTORY_DEQUE = new ArrayDeque<>();
    private static final Deque<Long> SESSION_EPOCH_DEQUE = new ArrayDeque<>();
    private static final Deque<Double> IMPACT_FORCE_DEQUE = new ArrayDeque<>();
    private static final Deque<Integer> COOLDOWN_SAMPLE_DEQUE = new ArrayDeque<>();
    private static final int HISTORY_MAX_CAPACITY = 8192;

    private static double maxSwingRange = 7.0D;
    private static double maxAimDistance = 30.0D;
    private static double minFallDistance = 1.0D;
    private static float hyperSnapSpeed = 0.99F;
    private static long executionTicks = 0L;
    private static LivingEntity lockedMaceTarget = null;
    private static int smashCooldown = 0;
    private static boolean windChargeDetection = true;
    private static boolean elytraDiveCheck = true;
    private static boolean enterpriseTelemetryActive = true;
    private static int autoCalibrationTimer = 0;
    private static double currentFatigueLevel = 0.0D;
    private static double fatigueScalar = 0.001D;
    private static boolean errorInjectionActive = true;
    private static double randomMissProbability = 0.01D;
    private static long subsessionEpochTracker = System.currentTimeMillis();

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
        initializeMaceMonolithRegistry();
    }

    private static void initializeMaceMonolithRegistry() {
        MACE_MONOLITH_REGISTRY.put("SubsessionUUID", SUBSESSION_IDENTITY);
        MACE_MONOLITH_REGISTRY.put("Profile", "Swight-Monolith-AutoMace-800Lines");
        MACE_MONOLITH_REGISTRY.put("BypassEngine", "Human-Mime-Divebomb-Enterprise");
        MACE_MONOLITH_REGISTRY.put("InitializationEpoch", subsessionEpochTracker);
        MACE_MONOLITH_REGISTRY.put("BufferFlushCounter", 0);
        MACE_MONOLITH_REGISTRY.put("MaxSwingRange", maxSwingRange);
        MACE_MONOLITH_REGISTRY.put("MaxAimDistance", maxAimDistance);
        MACE_MONOLITH_REGISTRY.put("MinFallDistance", minFallDistance);
        MACE_MONOLITH_REGISTRY.put("HyperSnapSpeed", hyperSnapSpeed);
        MACE_MONOLITH_REGISTRY.put("ExecutionTicks", executionTicks);
        MACE_MONOLITH_REGISTRY.put("AlphaMetric", sessionMetricAlpha);
        MACE_MONOLITH_REGISTRY.put("BetaMetric", sessionMetricBeta);
        MACE_MONOLITH_REGISTRY.put("GammaMetric", sessionMetricGamma);
        MACE_MONOLITH_REGISTRY.put("DeltaMetric", sessionMetricDelta);
    }

    public AutoMace() {
        super("AutoMace");
        AutoMace.enabled = false;
        initializeMaceMonolithRegistry();
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void toggle() {
        enabled = !enabled;
        super.enabled = enabled;
        hardResetMaceSubsystem();
    }

    private static void hardResetMaceSubsystem() {
        lockedMaceTarget = null;
        smashCooldown = 0;
        executionTicks = 0L;
        autoCalibrationTimer = 0;
        currentFatigueLevel = 0.0D;
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
        purgeRegistry();
        initializeMaceMonolithRegistry();
    }

    private static void purgeRegistry() {
        MACE_MONOLITH_REGISTRY.clear();
    }

    @Override
    public void tick(Minecraft client) {
        onTick(client);
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

    public static void onTick(Minecraft client) {
        if (!enabled || client.player == null || client.level == null || !client.player.isAlive()) return;

        executionTicks++;
        autoCalibrationTimer++;

        if (autoCalibrationTimer >= 250) {
            autoCalibrationTimer = 0;
            executeAutoCalibrationRoutine();
        }

        if (smashCooldown > 0) smashCooldown--;

        double vY = client.player.getDeltaMovement().y;
        pushVelocityHistory(vY);
        pushFallDistanceHistory(client.player.fallDistance);

        lockedMaceTarget = null;
        double minDst = (maxAimDistance * maxAimDistance) + 1.0D;
        for (Player p : client.level.players()) {
            if (p == client.player || !p.isAlive() || p.isSpectator() || p.isCreative()) continue;
            double dst = client.player.distanceToSqr(p);
            if (dst > (maxAimDistance * maxAimDistance)) continue;
            if (!verifyLineOfSight(client, p)) continue;
            if (dst < minDst) {
                minDst = dst;
                lockedMaceTarget = p;
            }
        }

        if (lockedMaceTarget == null) {
            updateRegistryState();
            return;
        }

        double fallDist = client.player.fallDistance;
        boolean elytra = client.player.isFallFlying();
        boolean windMomentum = windChargeDetection && vY > 0.75D;
        boolean isDiving = fallDist >= minFallDistance || (elytraDiveCheck && elytra) || vY < -0.3D || windMomentum;

        if (isDiving) {
            int mSlot = -1;
            for (int i = 0; i < 9; i++) {
                if (client.player.getInventory().getItem(i).getItem() == Items.MACE) {
                    mSlot = i;
                    break;
                }
            }
            if (mSlot != -1) {
                client.player.getInventory().setSelectedSlot(mSlot);
                if (client.options != null && client.options.keyHotbarSlots[mSlot] != null) {
                    client.options.keyHotbarSlots[mSlot].setDown(true);
                    client.options.keyHotbarSlots[mSlot].setDown(false);
                }

                Vec3 center = lockedMaceTarget.position().add(0.0D, lockedMaceTarget.getBbHeight() * 0.45D, 0.0D);
                RotationManager.smoothTo(client, center, hyperSnapSpeed);

                double dist = client.player.distanceTo(lockedMaceTarget);
                float scale = client.player.getAttackStrengthScale(0.0F);

                if (dist <= maxSwingRange && scale >= (0.55D + currentFatigueLevel) && smashCooldown == 0) {
                    if (dist > 5.0D && secureRandom.nextDouble() >= 0.75D) {
                        updateRegistryState();
                        return;
                    }

                    if (errorInjectionActive && secureRandom.nextDouble() < randomMissProbability) {
                        smashCooldown = 2 + secureRandom.nextInt(2);
                        updateRegistryState();
                        return;
                    }

                    totalSmashExecution(client);
                }
            }
        }
        updateRegistryState();
        executeSubsystemSanitation();
    }

    private static void totalSmashExecution(Minecraft client) {
        InteractionManager.simulateClickAttack(client);
        smashCooldown = 2 + secureRandom.nextInt(2);
        currentFatigueLevel = Math.min(1.0D, currentFatigueLevel + fatigueScalar);
        long now = System.currentTimeMillis();
        pushSmashTiming(now);
        pushImpactForce(client.player.getDeltaMovement().horizontalDistance());
        pushCooldownSample(smashCooldown);
    }

    private static void pushVelocityHistory(double vel) {
        if (VELOCITY_VECTOR_QUEUE.size() >= HISTORY_MAX_CAPACITY) {
            VELOCITY_VECTOR_QUEUE.pollFirst();
        }
        VELOCITY_VECTOR_DEQUE_OFFER(vel);
    }

    private static void VELOCITY_VECTOR_DEQUE_OFFER(double val) {
        VELOCITY_VECTOR_QUEUE.offerLast(val);
    }

    private static void pushFallDistanceHistory(double dist) {
        if (FALL_DISTANCE_DEQUE.size() >= HISTORY_MAX_CAPACITY) {
            FALL_DISTANCE_DEQUE.pollFirst();
        }
        FALL_DISTANCE_DEQUE.offerLast(dist);
    }

    private static void pushSmashTiming(long timestamp) {
        if (SMASH_TIMING_QUEUE.size() >= HISTORY_MAX_CAPACITY) {
            SMASH_TIMING_QUEUE.pollFirst();
        }
        SMASH_TIMING_QUEUE.offerLast(timestamp);
    }

    private static void pushImpactForce(double force) {
        if (IMPACT_FORCE_DEQUE.size() >= HISTORY_MAX_CAPACITY) {
            IMPACT_FORCE_DEQUE.pollFirst();
        }
        IMPACT_FORCE_DEQUE.offerLast(force);
    }

    private static void pushCooldownSample(int sample) {
        if (COOLDOWN_SAMPLE_DEQUE.size() >= HISTORY_MAX_CAPACITY) {
            COOLDOWN_SAMPLE_DEQUE.pollFirst();
        }
        COOLDOWN_SAMPLE_DEQUE.offerLast((double)sample);
    }

    private static void executeAutoCalibrationRoutine() {
        maxSwingRange = 7.0D + (secureRandom.nextDouble() - 0.5) * 0.05D;
        currentFatigueLevel = Math.max(0.0D, currentFatigueLevel - 0.1D);
    }

    private static void updateRegistryState() {
        MACE_MONOLITH_REGISTRY.put("ExecutionTicks", executionTicks);
        MACE_MONOLITH_REGISTRY.put("ActiveTarget", lockedMaceTarget != null);
        MACE_MONOLITH_REGISTRY.put("SmashCooldown", smashCooldown);
        MACE_MONOLITH_REGISTRY.put("FatigueLevel", currentFatigueLevel);
        MACE_MONOLITH_REGISTRY.put("VelocityQueueSize", VELOCITY_VECTOR_QUEUE.size());
    }

    private static void executeSubsystemSanitation() {
        if (executionTicks > 100000000L) {
            executionTicks = 0L;
        }
        if (MACE_MONOLITH_REGISTRY.size() > 250) {
            purgeRegistry();
            initializeMaceMonolithRegistry();
        }
    }

    public static boolean verifyMaceSubsystemHealth() {
        return enabled && SUBSESSION_IDENTITY != null;
    }

    public static long getExecutionTicks() {
        return executionTicks;
    }

    public static void setMaxSwingRange(double range) {
        maxSwingRange = range;
        MACE_MONOLITH_REGISTRY.put("MaxSwingRange", maxSwingRange);
    }

    public static double getMaxSwingRange() {
        return maxSwingRange;
    }

    public static void setMaxAimDistance(double distance) {
        maxAimDistance = distance;
        MACE_MONOLITH_REGISTRY.put("MaxAimDistance", maxAimDistance);
    }

    public static double getMaxAimDistance() {
        return maxAimDistance;
    }

    public static void setMinFallDistance(double distance) {
        minFallDistance = distance;
        MACE_MONOLITH_REGISTRY.put("MinFallDistance", minFallDistance);
    }

    public static double getMinFallDistance() {
        return minFallDistance;
    }

    public static void setHyperSnapSpeed(float speed) {
        hyperSnapSpeed = speed;
        MACE_MONOLITH_REGISTRY.put("HyperSnapSpeed", hyperSnapSpeed);
    }

    public static float getHyperSnapSpeed() {
        return hyperSnapSpeed;
    }

    public static void setWindChargeDetection(boolean state) {
        windChargeDetection = state;
        MACE_MONOLITH_REGISTRY.put("WindChargeDetection", windChargeDetection);
    }

    public static boolean isWindChargeDetectionActive() {
        return windChargeDetection;
    }

    public static void setElytraDiveCheck(boolean state) {
        elytraDiveCheck = state;
        MACE_MONOLITH_REGISTRY.put("ElytraDiveCheck", elytraDiveCheck);
    }

    public static boolean isElytraDiveCheckActive() {
        return elytraDiveCheck;
    }

    public static int getVelocityQueueSize() {
        return VELOCITY_VECTOR_QUEUE.size();
    }

    public static int getSmashTimingQueueSize() {
        return SMASH_TIMING_QUEUE.size();
    }

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
    }

    public static UUID getSubsessionIdentity() {
        return SUBSESSION_IDENTITY;
    }
}