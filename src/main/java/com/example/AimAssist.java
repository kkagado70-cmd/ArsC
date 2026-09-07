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

    private static final Map<String, Object> AIM_REGISTRY = new ConcurrentHashMap<>();
    private static final UUID SUBSESSION_ID = UUID.randomUUID();
    private static final Deque<Float> YAW_HISTORY = new ArrayDeque<>();
    private static final Deque<Float> PITCH_HISTORY = new ArrayDeque<>();
    private static final Deque<Vec3> VELOCITY_SAMPLES = new ArrayDeque<>();
    private static final int HISTORY_CAP = 512;

    private static double smoothingRate = 0.14D;
    private static double jitterScale = 0.0008D;
    private static float maxFov = 105.0F;
    private static double maxReach = 5.0D;
    private static long executionCounter = 0L;
    private static boolean windMouseActive = true;
    private static boolean gcdActive = true;
    private static double windX = 0.0D;
    private static double windY = 0.0D;
    private static int switchThrottle = 0;
    private static boolean swightTrackingCurve = true;
    private static double boneTargetOffset = 0.45D;
    private static boolean dynamicPredictionActive = true;
    private static double predictionFactor = 1.25D;
    private static boolean microCorrectionEnabled = true;
    private static int microCorrectionInterval = 3;
    private static boolean antiHeuristicActive = true;
    private static int anomalyCount = 0;
    private static boolean stealthMode = true;
    private static double maxTurnRate = 24.0D;
    private static boolean losStrict = true;
    private static int targetDelayTicks = 1;
    private static boolean screenShareShield = true;
    private static double verticalMultiplier = 1.1D;
    private static boolean targetAccelerationComp = true;
    private static double inertiaDamping = 0.90D;
    private static boolean strictGcdSnap = true;
    private static int sampleCursor = 0;
    private static boolean adaptiveFovScale = true;
    private static double distanceFovModifier = 0.95D;
    private static boolean targetPriorityHealth = true;
    private static boolean strafePredictionActive = true;
    private static double strafeScalar = 0.35D;
    private static boolean jitterStochasticity = true;
    private static double noiseAmplitude = 0.0015D;
    private static boolean targetTransitionSmoothing = true;
    private static int transitionTicks = 5;
    private static boolean blockHitboxBypass = true;
    private static double hitboxExpansion = 0.1D;
    private static boolean wallPredictionBypass = true;
    private static boolean movementInertiaSync = true;
    private static double velocityWeight = 0.4D;
    private static boolean packetOrderSync = true;
    private static long lastAimEpoch = 0L;
    private static boolean diagnosticTelemetry = true;
    private static int sessionAimCount = 0;
    private static boolean dynamicReachScaling = true;
    private static double minReachBound = 3.0D;
    private static boolean targetSnapPrevention = true;
    private static double maxDeltaCap = 15.0D;
    private static boolean mouseHardwareBypass = true;
    private static boolean profileLocked = false;
    private static double stochasticVariance = 0.03D;
    private static int emergencyResetThreshold = 100;
    private static boolean movementJitterCompensation = true;
    private static double selfVelocityDampener = 0.65D;
    private static boolean antiSnapLockEngine = true;
    private static double minimumBlendThreshold = 0.02D;

    static {
        initializeRegistry();
    }

    private static void initializeRegistry() {
        AIM_REGISTRY.put("SubsessionUUID", SUBSESSION_ID);
        AIM_REGISTRY.put("Profile", "Swight-Tier1-AimAssist-AbsolutePrecision");
        AIM_REGISTRY.put("Smoothing", smoothingRate);
        AIM_REGISTRY.put("WindMouse", windMouseActive);
        AIM_REGISTRY.put("MovementCompensation", movementJitterCompensation);
        AIM_REGISTRY.put("AntiSnapLock", antiSnapLockEngine);
        AIM_REGISTRY.put("Telemetry", diagnosticTelemetry);
    }

    public AimAssist() {
        super("AimAssist");
        AimAssist.enabled = true;
    }

    @Override
    public boolean isEnabled() { return enabled; }

    @Override
    public void toggle() {
        enabled = !enabled;
        super.enabled = enabled;
        hardReset();
    }

    private static void hardReset() {
        lockedTarget = null;
        targetLockTicks = 0;
        switchThrottle = 0;
        windX = 0.0D;
        windY = 0.0D;
        anomalyCount = 0;
        sessionAimCount = 0;
        YAW_HISTORY.clear();
        PITCH_HISTORY.clear();
        VELOCITY_SAMPLES.clear();
        AIM_REGISTRY.clear();
        initializeRegistry();
    }

    @Override
    public void tick(Minecraft client) { onTick(client); }

    private static boolean validateWeapon(Minecraft client) {
        if (client.player == null) return false;
        ItemStack stack = client.player.getMainHandItem();
        if (stack.isEmpty()) return false;
        String name = stack.getItem().getDescriptionId().toLowerCase();
        return name.contains("sword") || name.contains("axe") || name.contains("trident") || name.contains("mace");
    }

    private static boolean verifyLos(Minecraft client, Entity target) {
        if (client.player == null || target == null) return false;
        Vec3 start = client.player.getEyePosition();
        Vec3 end = target.getEyePosition();
        BlockHitResult hit = client.level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, client.player));
        return hit.getType() == HitResult.Type.MISS;
    }

    public static void onTick(Minecraft client) {
        if (!enabled || client.player == null || client.level == null || !client.player.isAlive()) return;
        if (!validateWeapon(client)) { hardReset(); return; }

        executionCounter++;
        sessionAimCount++;
        lastAimEpoch = System.currentTimeMillis();

        if (switchThrottle > 0) switchThrottle--;

        Entity target = evaluateTarget(client);
        if (target != null) {
            executeAimPipeline(client, target);
        } else {
            lockedTarget = null;
            targetLockTicks = 0;
            windX = 0.0D;
            windY = 0.0D;
        }
        updateRegistry();
    }

    private static Entity evaluateTarget(Minecraft client) {
        if (lockedTarget != null) {
            if (lockedTarget.isAlive() && client.player.distanceToSqr(lockedTarget) <= (maxReach * maxReach) && fovCheck(client, lockedTarget, maxFov) && (!losStrict || verifyLos(client, lockedTarget))) {
                targetLockTicks++;
                if (targetLockTicks < 900) return lockedTarget;
            }
            lockedTarget = null;
            targetLockTicks = 0;
            switchThrottle = 2 + secureRandom.nextInt(3);
        }
        if (switchThrottle > 0) return null;

        Entity best = null;
        double minDst = (maxReach * maxReach) + 1.0D;
        for (Player p : client.level.players()) {
            if (p == client.player || !p.isAlive() || p.isSpectator() || p.isCreative()) continue;
            double dst = client.player.distanceToSqr(p);
            if (dst > (maxReach * maxReach)) continue;
            if (losStrict && !verifyLos(client, p)) continue;
            if (dst < minDst) { minDst = dst; best = p; }
        }
        if (best != null && best != lockedTarget) {
            lockedTarget = best;
            targetLockTicks = 0;
        }
        return lockedTarget;
    }

    private static boolean fovCheck(Minecraft client, Entity entity, double angleBound) {
        Vec3 pos = entity.position();
        double dx = pos.x - client.player.getX();
        double dz = pos.z - client.player.getZ();
        float tYaw = (float)(Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
        return Math.abs(Mth.wrapDegrees(tYaw - client.player.getYRot())) <= angleBound;
    }

    private static void executeAimPipeline(Minecraft client, Entity target) {
        Vec3 pVel = client.player.getDeltaMovement().scale(selfVelocityDampener);
        Vec3 pred = target.getDeltaMovement().scale(predictionFactor).subtract(pVel);
        Vec3 tPos = target.position().add(0.0D, target.getBbHeight() * boneTargetOffset, 0.0D).add(pred);
        
        double dx = tPos.x - client.player.getX();
        double dy = tPos.y - client.player.getEyeY();
        double dz = tPos.z - client.player.getZ();
        double hDst = Math.sqrt(dx * dx + dz * dz);

        float tYaw = (float)(Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
        float tPitch = (float)(-(Math.atan2(dy, hDst) * (180.0 / Math.PI)));
        tPitch = Mth.clamp(tPitch, -89.0F, 89.0F);

        float cYaw = client.player.getYRot();
        float cPitch = client.player.getXRot();
        float dYaw = Mth.wrapDegrees(tYaw - cYaw);
        float dPitch = tPitch - cPitch;

        if (Math.abs(dYaw) < minimumBlendThreshold && Math.abs(dPitch) < minimumBlendThreshold) return;

        if (windMouseActive) {
            windX = windX / Math.sqrt(3.0D) + (secureRandom.nextGaussian() * 0.9D) / Math.sqrt(5.0D);
            windY = windY / Math.sqrt(3.0D) + (secureRandom.nextGaussian() * 0.9D * verticalMultiplier) / Math.sqrt(5.0D);

            float stepYaw = (float)(dYaw / 14.0D + windX * 0.010D);
            float nYaw = (float)(secureRandom.nextGaussian() * noiseAmplitude);
            float nY = cYaw + stepYaw + nYaw;

            float nP = cPitch;
            if (!horizontalAxisOnly(client)) {
                float stepPitch = (float)(dPitch / 14.0D + windY * 0.010D);
                float nPitch = (float)(secureRandom.nextGaussian() * noiseAmplitude);
                nP = Mth.clamp(cPitch + stepPitch + nPitch, -89.0F, 89.0F);
            }

            if (gcdActive) nY = snapGcd(client, cYaw, nY);

            pushHistory(nY, nP);
            client.player.setYRot(nY);
            if (!horizontalAxisOnly(client)) client.player.setXRot(nP);
            turnHardware(client, cYaw, nY, horizontalAxisOnly(client) ? 0.0D : (nP - cPitch));
        }
    }

    private static boolean horizontalAxisOnly(Minecraft client) { return false; }

    private static float snapGcd(Minecraft client, float cur, float target) {
        if (client.options == null) return target;
        double sens = client.options.sensitivity().get() * 0.6D + 0.2D;
        double gcd = sens * sens * sens * 8.0D;
        if (gcd <= 0.0D) return target;
        double delta = target - cur;
        double snap = Math.round(delta / (gcd * 0.15D)) * (gcd * 0.15D);
        return cur + (float)snap;
    }

    private static void pushHistory(float y, float p) {
        if (YAW_HISTORY.size() >= HISTORY_CAP) YAW_HISTORY.pollFirst();
        YAW_HISTORY.offerLast(y);
        if (PITCH_HISTORY.size() >= HISTORY_CAP) PITCH_HISTORY.pollFirst();
        PITCH_HISTORY.offerLast(p);
    }

    private static void turnHardware(Minecraft client, float curY, float nextY, double dPitch) {
        if (client.options != null) {
            double sens = client.options.sensitivity().get() * 0.6D + 0.2D;
            double gcd = sens * sens * sens * 8.0D;
            if (gcd > 0.0D) {
                client.player.turn((nextY - curY) / (gcd * 0.15D), dPitch / (gcd * 0.15D));
            }
        }
    }

    private static void updateRegistry() {
        AIM_REGISTRY.put("ExecutionTicks", executionCounter);
        AIM_REGISTRY.put("Locked", lockedTarget != null);
        AIM_REGISTRY.put("SessionAimCount", sessionAimCount);
    }

    public static boolean verifySubsystemHealth() { return enabled && SUBSESSION_ID != null; }
    public static long getExecutionCounter() { return executionCounter; }
    public static UUID getSubsessionIdentity() { return SUBSESSION_ID; }
}