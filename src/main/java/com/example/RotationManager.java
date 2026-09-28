package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class RotationManager {

    public static final String FILE_NAME = "RotationManager.java";

    public enum EasingMode {
        LINEAR,
        EASE_IN_OUT_CUBIC,
        EASE_OUT_EXPO,
        KINEMATIC_SPRING,
        SWIGHT_HIGH_SENS
    }

    public enum RotationPriority {
        LOW(0), NORMAL(1), HIGH(2), CRITICAL(3);
        public final int level;
        RotationPriority(int l) { this.level = l; }
    }

    private static final SecureRandom secureRandom = new SecureRandom();
    private static final Map<String, Object> ROTATION_REGISTRY = new ConcurrentHashMap<>();
    private static final UUID SUBSESSION_ID = UUID.randomUUID();

    private static final Deque<Float> YAW_HISTORY   = new ArrayDeque<>();
    private static final Deque<Float> PITCH_HISTORY  = new ArrayDeque<>();
    private static final Deque<Long>  TIMESTAMP_HIST = new ArrayDeque<>();
    private static final int HISTORY_CAP = 512;

    private static float currentYaw       = 0.0f;
    private static float currentPitch     = 0.0f;
    private static float yawVelocity      = 0.0f;
    private static float pitchVelocity    = 0.0f;
    private static boolean active         = false;
    private static boolean locked         = false;

    private static EasingMode easingMode        = EasingMode.EASE_OUT_EXPO;
    private static RotationPriority priority    = RotationPriority.NORMAL;

    private static double deadzoneThreshold      = 0.025D;
    private static double jitterMagnitude        = 0.0D;
    private static double gcdSensitivity         = 0.0D;
    private static double sampledGcd             = 0.0D;
    private static int    gcdSamples             = 0;
    private static double springStiffness        = 0.18D;
    private static double springDamping          = 0.72D;
    private static float  maxYawStep             = 90.0f;
    private static float  maxPitchStep           = 75.0f;
    private static float  minPitch               = -89.9f;
    private static float  maxPitch               = 89.9f;
    private static long   totalRotationInvocations = 0L;
    private static int    stableTickCount         = 0;
    private static float  lastTargetYaw           = 0.0f;
    private static float  lastTargetPitch         = 0.0f;
    private static float  savedPlayerYaw          = 0.0f;
    private static float  savedPlayerPitch        = 0.0f;
    private static boolean hasSavedRotation       = false;
    private static int    stabilityThreshold      = 2;
    private static float  stabilityYawTolerance   = 1.5f;
    private static float  stabilityPitchTolerance = 1.5f;

    static {
        ROTATION_REGISTRY.put("SubsessionUUID", SUBSESSION_ID);
        ROTATION_REGISTRY.put("Profile", "Swight-Eyezingz-RotationManager-Enterprise");
        ROTATION_REGISTRY.put("EasingMode", easingMode.name());
        ROTATION_REGISTRY.put("GCDBypass", true);
        ROTATION_REGISTRY.put("SpringDamping", springDamping);
        ROTATION_REGISTRY.put("SpringStiffness", springStiffness);
    }

    public static void samplePlayerGcd(Minecraft client) {
        if (client == null || client.player == null || client.options == null) return;
        double sens = client.options.sensitivity().get() * 0.6 + 0.2;
        gcdSensitivity = sens * sens * sens * 8.0;
        if (YAW_HISTORY.size() >= 2) {
            Float[] arr = YAW_HISTORY.toArray(new Float[0]);
            float delta = Math.abs(Mth.wrapDegrees(arr[arr.length - 1] - arr[arr.length - 2]));
            if (delta > 0.001f) {
                sampledGcd = gcdSamples == 0 ? delta : euclidGcd((float) sampledGcd, delta);
                gcdSamples++;
            }
        }
    }

    public static void smoothTo(Minecraft client, Vec3 target, float factor) {
        if (client == null || client.player == null || target == null || locked) return;
        totalRotationInvocations++;

        float[] desired = computeDesiredAngles(client, target);
        float desiredYaw   = desired[0];
        float desiredPitch = desired[1];

        float curYaw   = client.player.getYRot();
        float curPitch = client.player.getXRot();
        float yawDiff   = Mth.wrapDegrees(desiredYaw   - curYaw);
        float pitchDiff = desiredPitch - curPitch;

        if (Math.abs(yawDiff) < deadzoneThreshold && Math.abs(pitchDiff) < deadzoneThreshold) {
            stableTickCount++;
            active = stableTickCount < stabilityThreshold;
            return;
        }

        stableTickCount = 0;
        float[] steps = computeStepByMode(yawDiff, pitchDiff, factor, curYaw, curPitch);
        float stepYaw   = Mth.clamp(steps[0], -maxYawStep, maxYawStep);
        float stepPitch = Mth.clamp(steps[1], -maxPitchStep, maxPitchStep);

        double jY = secureRandom.nextGaussian() * resolveJitter(yawDiff);
        double jP = secureRandom.nextGaussian() * resolveJitter(pitchDiff) * 0.6;

        float nextYaw   = curYaw   + stepYaw   + (float) jY;
        float nextPitch = Mth.clamp(curPitch + stepPitch + (float) jP, minPitch, maxPitch);

        nextYaw   = avoidExactInteger(nextYaw);
        nextPitch = avoidExactInteger(nextPitch);

        applyGCDRotation(client, nextYaw - curYaw, nextPitch - curPitch);

        client.player.setYRot(Mth.wrapDegrees(nextYaw));
        client.player.setXRot(nextPitch);

        currentYaw   = nextYaw;
        currentPitch = nextPitch;
        lastTargetYaw   = desiredYaw;
        lastTargetPitch = desiredPitch;
        active = true;

        pushHistory(nextYaw, nextPitch);
    }

    public static void snapTo(Minecraft client, Vec3 target) {
        if (client == null || client.player == null || target == null || locked) return;
        totalRotationInvocations++;

        float[] desired     = computeDesiredAngles(client, target);
        float desiredYaw    = desired[0];
        float desiredPitch  = desired[1];
        float curYaw        = client.player.getYRot();
        float curPitch      = client.player.getXRot();

        float dYaw   = Mth.wrapDegrees(desiredYaw   - curYaw);
        float dPitch = desiredPitch - curPitch;

        dYaw   += (float)(secureRandom.nextGaussian() * resolveJitter(dYaw));
        dPitch += (float)(secureRandom.nextGaussian() * resolveJitter(dPitch) * 0.55);

        applyGCDRotation(client, dYaw, dPitch);
        client.player.setYRot(Mth.wrapDegrees(avoidExactInteger(desiredYaw)));
        client.player.setXRot(Mth.clamp(avoidExactInteger(desiredPitch), minPitch, maxPitch));

        currentYaw   = desiredYaw;
        currentPitch = desiredPitch;
        yawVelocity  = 0.0f;
        pitchVelocity = 0.0f;
        active = true;
        stableTickCount = 0;

        pushHistory(currentYaw, currentPitch);
    }

    public static void applyDelta(Minecraft client, float dYaw, float dPitch) {
        if (client == null || client.player == null || locked) return;
        float curYaw   = client.player.getYRot();
        float curPitch = client.player.getXRot();
        float nextYaw   = Mth.wrapDegrees(curYaw   + dYaw);
        float nextPitch = Mth.clamp(curPitch + dPitch, minPitch, maxPitch);
        applyGCDRotation(client, dYaw, dPitch);
        client.player.setYRot(nextYaw);
        client.player.setXRot(nextPitch);
        currentYaw   = nextYaw;
        currentPitch = nextPitch;
        pushHistory(nextYaw, nextPitch);
    }

    public static void applyGCDRotation(Minecraft client, double dYaw, double dPitch) {
        if (client == null || client.player == null || client.options == null) return;
        double sensitivity = client.options.sensitivity().get() * 0.6 + 0.2;
        if (sensitivity <= 0.0) return;
        double gcd = sensitivity * sensitivity * sensitivity * 8.0;
        if (gcd <= 0.0) return;
        double effective = sampledGcd > 0.001 ? sampledGcd : gcd;
        double roundedYaw   = Math.round(dYaw   / effective) * effective;
        double roundedPitch = Math.round(dPitch / effective) * effective;
        double noiseY = (secureRandom.nextDouble() - 0.5) * effective * 0.18;
        double noiseP = (secureRandom.nextDouble() - 0.5) * effective * 0.14;
        client.player.turn((roundedYaw + noiseY) / 0.15, (roundedPitch + noiseP) / 0.15);
    }

    private static float[] computeDesiredAngles(Minecraft client, Vec3 target) {
        double dx = target.x - client.player.getX();
        double dy = target.y - client.player.getEyeY();
        double dz = target.z - client.player.getZ();
        double hDist = Math.max(1e-9, Math.sqrt(dx * dx + dz * dz));
        float yaw   = (float)(Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float)(-Math.toDegrees(Math.atan2(dy, hDist)));
        return new float[]{ yaw, Mth.clamp(pitch, minPitch, maxPitch) };
    }

    private static float[] computeStepByMode(float yawDiff, float pitchDiff, float factor, float curYaw, float curPitch) {
        return switch (easingMode) {
            case LINEAR -> new float[]{
                yawDiff   * Math.min(1.0f, factor),
                pitchDiff * Math.min(1.0f, factor)
            };
            case EASE_IN_OUT_CUBIC -> {
                float t      = Math.min(1.0f, Math.abs(yawDiff) / 25.0f);
                float eased  = t < 0.5f ? 4 * t * t * t : (float)(1 - Math.pow(-2 * t + 2, 3) / 2);
                yield new float[]{
                    yawDiff   * eased * Math.min(0.95f, factor),
                    pitchDiff * eased * Math.min(0.95f, factor)
                };
            }
            case EASE_OUT_EXPO -> {
                float ang  = (float) Math.sqrt(yawDiff * yawDiff + pitchDiff * pitchDiff);
                float near = ang < 8.0f ? (0.28f + 0.72f * ang / 8.0f) : 1.0f;
                float expY = yawDiff   == 0 ? 0 : (float)(Math.signum(yawDiff)   * (1 - Math.pow(2, -10 * Math.abs(yawDiff)   / 45.0)));
                float expP = pitchDiff == 0 ? 0 : (float)(Math.signum(pitchDiff) * (1 - Math.pow(2, -10 * Math.abs(pitchDiff) / 30.0)));
                yield new float[]{
                    yawDiff   * Math.abs(expY) * Math.min(0.92f, factor + 0.08f) * near,
                    pitchDiff * Math.abs(expP) * Math.min(0.92f, factor + 0.08f) * near
                };
            }
            case KINEMATIC_SPRING -> {
                float springForceY = (float)(yawDiff   * springStiffness - yawVelocity   * springDamping);
                float springForceP = (float)(pitchDiff * springStiffness - pitchVelocity * springDamping);
                yawVelocity   += springForceY;
                pitchVelocity += springForceP;
                yawVelocity   = Mth.clamp(yawVelocity,   -maxYawStep,   maxYawStep);
                pitchVelocity = Mth.clamp(pitchVelocity, -maxPitchStep, maxPitchStep);
                float ang = (float) Math.sqrt(yawDiff * yawDiff + pitchDiff * pitchDiff);
                float near = ang < 8.0f ? (0.28f + 0.72f * ang / 8.0f) : 1.0f;
                yield new float[]{ yawVelocity * near, pitchVelocity * near };
            }
            case SWIGHT_HIGH_SENS -> {
                float ang  = (float) Math.sqrt(yawDiff * yawDiff + pitchDiff * pitchDiff);
                float near = ang < 8.0f ? (0.28f + 0.72f * ang / 8.0f) : 1.0f;
                float rawT  = Math.min(1.0f, ang / 18.0f);
                float eased = 1.0f - (1.0f - rawT) * (1.0f - rawT) * (1.0f - rawT);
                float aggrFactor = Math.min(0.98f, factor + 0.15f);
                yield new float[]{
                    yawDiff   * eased * aggrFactor * near,
                    pitchDiff * eased * aggrFactor * near
                };
            }
        };
    }

    private static double resolveJitter(float delta) {
        double base = 0.004 + Math.abs(delta) * 0.0002;
        if (secureRandom.nextFloat() < 0.05f) base += 0.015;
        return base;
    }

    private static float avoidExactInteger(float v) {
        float frac = v - (float) Math.floor(v);
        if (frac < 0.005f || frac > 0.995f) {
            v += (secureRandom.nextBoolean() ? 1 : -1) * (0.006f + secureRandom.nextFloat() * 0.016f);
        }
        return v;
    }

    private static float euclidGcd(float a, float b) {
        a = Math.abs(a); b = Math.abs(b);
        while (b > 0.0001f) { float t = b; b = a % b; a = t; }
        return a;
    }

    public static float computeYawError(Minecraft client, Vec3 target) {
        if (client == null || client.player == null || target == null) return 0.0f;
        double dx = target.x - client.player.getX();
        double dz = target.z - client.player.getZ();
        float desired = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
        return Mth.wrapDegrees(desired - client.player.getYRot());
    }

    public static float computePitchError(Minecraft client, Vec3 target) {
        if (client == null || client.player == null || target == null) return 0.0f;
        double dx = target.x - client.player.getX();
        double dy = target.y - client.player.getEyeY();
        double dz = target.z - client.player.getZ();
        double h  = Math.max(1e-9, Math.sqrt(dx * dx + dz * dz));
        float desired = (float) -Math.toDegrees(Math.atan2(dy, h));
        return desired - client.player.getXRot();
    }

    public static boolean isAligned(Minecraft client, Vec3 target, float yawTol, float pitchTol) {
        if (client == null || client.player == null || target == null) return false;
        return Math.abs(computeYawError(client, target)) <= yawTol
            && Math.abs(computePitchError(client, target)) <= pitchTol;
    }

    public static boolean isStable() { return stableTickCount >= stabilityThreshold; }

    public static void saveRotation(Minecraft client) {
        if (client == null || client.player == null) return;
        savedPlayerYaw   = client.player.getYRot();
        savedPlayerPitch = client.player.getXRot();
        hasSavedRotation = true;
    }

    public static void restoreRotation(Minecraft client) {
        if (!hasSavedRotation || client == null || client.player == null) return;
        float dY = Mth.wrapDegrees(savedPlayerYaw   - client.player.getYRot());
        float dP = savedPlayerPitch - client.player.getXRot();
        applyGCDRotation(client, dY, dP);
        client.player.setYRot(savedPlayerYaw);
        client.player.setXRot(savedPlayerPitch);
        hasSavedRotation = false;
        yawVelocity      = 0.0f;
        pitchVelocity    = 0.0f;
        active           = false;
    }

    public static void resetVelocity() {
        yawVelocity   = 0.0f;
        pitchVelocity = 0.0f;
        stableTickCount = 0;
    }

    public static void lock()   { locked = true; }
    public static void unlock() { locked = false; }

    public static void reset() {
        active          = false;
        locked          = false;
        yawVelocity     = 0.0f;
        pitchVelocity   = 0.0f;
        stableTickCount = 0;
        hasSavedRotation = false;
        sampledGcd      = 0.0D;
        gcdSamples      = 0;
        YAW_HISTORY.clear();
        PITCH_HISTORY.clear();
        TIMESTAMP_HIST.clear();
    }

    private static void pushHistory(float yaw, float pitch) {
        if (YAW_HISTORY.size() >= HISTORY_CAP) {
            YAW_HISTORY.pollFirst();
            PITCH_HISTORY.pollFirst();
            TIMESTAMP_HIST.pollFirst();
        }
        YAW_HISTORY.offerLast(yaw);
        PITCH_HISTORY.offerLast(pitch);
        TIMESTAMP_HIST.offerLast(System.currentTimeMillis());
    }

    public static float getYawVelocityEstimate() {
        if (YAW_HISTORY.size() < 2) return 0.0f;
        Float[] arr = YAW_HISTORY.toArray(new Float[0]);
        return Mth.wrapDegrees(arr[arr.length - 1] - arr[arr.length - 2]);
    }

    public static float getPitchVelocityEstimate() {
        if (PITCH_HISTORY.size() < 2) return 0.0f;
        Float[] arr = PITCH_HISTORY.toArray(new Float[0]);
        return arr[arr.length - 1] - arr[arr.length - 2];
    }

    public static void setEasingMode(EasingMode mode) {
        easingMode = mode;
        ROTATION_REGISTRY.put("EasingMode", easingMode.name());
    }

    public static void setPriority(RotationPriority p) { priority = p; }
    public static void setStabilityThreshold(int ticks) { stabilityThreshold = Math.max(1, ticks); }
    public static void setStabilityTolerances(float yt, float pt) { stabilityYawTolerance = yt; stabilityPitchTolerance = pt; }
    public static void setJitterMagnitude(double mag) { jitterMagnitude = Math.max(0.0, mag); }

    public static void setSpringParameters(double stiffness, double damping) {
        springStiffness = Math.max(0.01, stiffness);
        springDamping   = Math.max(0.01, damping);
        ROTATION_REGISTRY.put("SpringStiffness", springStiffness);
        ROTATION_REGISTRY.put("SpringDamping",   springDamping);
    }

    public static void setDeadzoneThreshold(double threshold) { deadzoneThreshold = Math.max(0.001, threshold); }
    public static void setMaxSteps(float maxYaw, float maxPitch) { maxYawStep = Math.max(0.1f, maxYaw); maxPitchStep = Math.max(0.1f, maxPitch); }
    public static boolean isActive()     { return active; }
    public static boolean isLocked()     { return locked; }
    public static float getCurrentYaw()  { return currentYaw; }
    public static float getCurrentPitch(){ return currentPitch; }
    public static float getLastTargetYaw()   { return lastTargetYaw; }
    public static float getLastTargetPitch() { return lastTargetPitch; }
    public static long getTotalInvocations() { return totalRotationInvocations; }
    public static int getHistorySize()       { return YAW_HISTORY.size(); }
    public static EasingMode getEasingMode() { return easingMode; }
    public static UUID getSubsessionIdentity() { return SUBSESSION_ID; }
    public static double getSampledGcd()     { return sampledGcd; }
    public static double getGcdSensitivity() { return gcdSensitivity; }
}
