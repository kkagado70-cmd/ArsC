package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

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

    private static final Deque<Float> YAW_HISTORY = new ArrayDeque<>();
    private static final Deque<Float> PITCH_HISTORY = new ArrayDeque<>();
    private static final Deque<Long> TIMESTAMP_HISTORY = new ArrayDeque<>();
    private static final int HISTORY_CAP = 512;

    private static float currentYaw = 0.0f;
    private static float currentPitch = 0.0f;
    private static float yawVelocity = 0.0f;
    private static float pitchVelocity = 0.0f;
    private static boolean active = false;
    private static boolean locked = false;

    private static EasingMode easingMode = EasingMode.EASE_OUT_EXPO;
    private static RotationPriority priority = RotationPriority.NORMAL;

    private static double deadzoneThreshold = 0.025D;
    private static double jitterMagnitude = 0.000045D;
    private static double springStiffness = 0.18D;
    private static double springDamping = 0.72D;
    private static float maxYawStep = 90.0f;
    private static float maxPitchStep = 75.0f;
    private static float minPitch = -89.9f;
    private static float maxPitch = 89.9f;
    private static long totalRotationInvocations = 0L;
    private static int stableTickCount = 0;
    private static float lastTargetYaw = 0.0f;
    private static float lastTargetPitch = 0.0f;
    private static float savedPlayerYaw = 0.0f;
    private static float savedPlayerPitch = 0.0f;
    private static boolean hasSavedRotation = false;
    private static int stabilityThreshold = 2;
    private static float stabilityYawTolerance = 1.5f;
    private static float stabilityPitchTolerance = 1.5f;

    static {
        ROTATION_REGISTRY.put("SubsessionUUID", SUBSESSION_ID);
        ROTATION_REGISTRY.put("Profile", "Swight-Eyezingz-RotationManager-Enterprise");
        ROTATION_REGISTRY.put("EasingMode", easingMode.name());
        ROTATION_REGISTRY.put("GCDBypass", true);
        ROTATION_REGISTRY.put("SpringDamping", springDamping);
        ROTATION_REGISTRY.put("SpringStiffness", springStiffness);
    }

    public static void smoothTo(Minecraft client, Vec3 target, float factor) {
        if (client == null || client.player == null || target == null || locked) return;
        totalRotationInvocations++;

        float[] desired = computeDesiredAngles(client, target);
        float desiredYaw = desired[0];
        float desiredPitch = desired[1];

        float curYaw = client.player.getYRot();
        float curPitch = client.player.getXRot();
        float yawDiff = Mth.wrapDegrees(desiredYaw - curYaw);
        float pitchDiff = desiredPitch - curPitch;

        if (Math.abs(yawDiff) < deadzoneThreshold && Math.abs(pitchDiff) < deadzoneThreshold) {
            stableTickCount++;
            active = stableTickCount < stabilityThreshold;
            return;
        }

        stableTickCount = 0;
        float[] steps = computeStepByMode(yawDiff, pitchDiff, factor, curYaw, curPitch);
        float stepYaw = steps[0];
        float stepPitch = steps[1];

        stepYaw = Mth.clamp(stepYaw, -maxYawStep, maxYawStep);
        stepPitch = Mth.clamp(stepPitch, -maxPitchStep, maxPitchStep);

        double jY = secureRandom.nextGaussian() * jitterMagnitude;
        double jP = secureRandom.nextGaussian() * jitterMagnitude;

        float nextYaw = curYaw + stepYaw + (float) jY;
        float nextPitch = Mth.clamp(curPitch + stepPitch + (float) jP, minPitch, maxPitch);

        applyGCDRotation(client, nextYaw - curYaw, nextPitch - curPitch);

        client.player.setYRot(Mth.wrapDegrees(nextYaw));
        client.player.setXRot(nextPitch);

        currentYaw = nextYaw;
        currentPitch = nextPitch;
        lastTargetYaw = desiredYaw;
        lastTargetPitch = desiredPitch;
        active = true;

        pushHistory(nextYaw, nextPitch);
    }

    public static void snapTo(Minecraft client, Vec3 target) {
        if (client == null || client.player == null || target == null || locked) return;
        totalRotationInvocations++;

        float[] desired = computeDesiredAngles(client, target);
        float desiredYaw = desired[0];
        float desiredPitch = desired[1];

        float curYaw = client.player.getYRot();
        float curPitch = client.player.getXRot();

        float dYaw = Mth.wrapDegrees(desiredYaw - curYaw);
        float dPitch = desiredPitch - curPitch;

        applyGCDRotation(client, dYaw, dPitch);
        client.player.setYRot(Mth.wrapDegrees(desiredYaw));
        client.player.setXRot(Mth.clamp(desiredPitch, minPitch, maxPitch));

        currentYaw = desiredYaw;
        currentPitch = desiredPitch;
        yawVelocity = 0.0f;
        pitchVelocity = 0.0f;
        active = true;
        stableTickCount = 0;

        pushHistory(desiredYaw, desiredPitch);
    }

    public static void applyDelta(Minecraft client, float dYaw, float dPitch) {
        if (client == null || client.player == null || locked) return;
        float curYaw = client.player.getYRot();
        float curPitch = client.player.getXRot();
        float nextYaw = Mth.wrapDegrees(curYaw + dYaw);
        float nextPitch = Mth.clamp(curPitch + dPitch, minPitch, maxPitch);
        applyGCDRotation(client, dYaw, dPitch);
        client.player.setYRot(nextYaw);
        client.player.setXRot(nextPitch);
        currentYaw = nextYaw;
        currentPitch = nextPitch;
        pushHistory(nextYaw, nextPitch);
    }

    public static void applyGCDRotation(Minecraft client, double dYaw, double dPitch) {
        if (client == null || client.player == null || client.options == null) return;
        double sensitivity = client.options.sensitivity().get() * 0.6 + 0.2;
        if (sensitivity <= 0.0) return;
        double gcd = sensitivity * sensitivity * sensitivity * 8.0;
        if (gcd <= 0.0) return;
        double roundedYaw = Math.round(dYaw / gcd) * gcd;
        double roundedPitch = Math.round(dPitch / gcd) * gcd;
        client.player.turn(roundedYaw / 0.15, roundedPitch / 0.15);
    }

    private static float[] computeDesiredAngles(Minecraft client, Vec3 target) {
        double dx = target.x - client.player.getX();
        double dy = target.y - client.player.getEyeY();
        double dz = target.z - client.player.getZ();
        double hDist = Math.max(1e-9, Math.sqrt(dx * dx + dz * dz));
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, hDist)));
        pitch = Mth.clamp(pitch, minPitch, maxPitch);
        return new float[]{yaw, pitch};
    }

    private static float[] computeStepByMode(float yawDiff, float pitchDiff, float factor, float curYaw, float curPitch) {
        return switch (easingMode) {
            case LINEAR -> new float[]{
                    yawDiff * Math.min(1.0f, factor),
                    pitchDiff * Math.min(1.0f, factor)
            };
            case EASE_IN_OUT_CUBIC -> {
                float t = Math.min(1.0f, Math.abs(yawDiff) / 25.0f);
                float eased = t < 0.5f ? 4 * t * t * t : (float) (1 - Math.pow(-2 * t + 2, 3) / 2);
                yield new float[]{
                        yawDiff * eased * Math.min(0.95f, factor),
                        pitchDiff * eased * Math.min(0.95f, factor)
                };
            }
            case EASE_OUT_EXPO -> {
                float expY = yawDiff == 0 ? 0 : (float) (Math.signum(yawDiff) * (1 - Math.pow(2, -10 * Math.abs(yawDiff) / 45.0)));
                float expP = pitchDiff == 0 ? 0 : (float) (Math.signum(pitchDiff) * (1 - Math.pow(2, -10 * Math.abs(pitchDiff) / 30.0)));
                yield new float[]{
                        yawDiff * Math.abs(expY) * Math.min(0.92f, factor + 0.08f),
                        pitchDiff * Math.abs(expP) * Math.min(0.92f, factor + 0.08f)
                };
            }
            case KINEMATIC_SPRING -> {
                float springForceY = (float) (yawDiff * springStiffness - yawVelocity * springDamping);
                float springForceP = (float) (pitchDiff * springStiffness - pitchVelocity * springDamping);
                yawVelocity += springForceY;
                pitchVelocity += springForceP;
                yawVelocity = Mth.clamp(yawVelocity, -maxYawStep, maxYawStep);
                pitchVelocity = Mth.clamp(pitchVelocity, -maxPitchStep, maxPitchStep);
                yield new float[]{yawVelocity, pitchVelocity};
            }
            case SWIGHT_HIGH_SENS -> {
                float rawT = Math.min(1.0f, Math.abs(yawDiff) / 18.0f);
                float eased = 1.0f - (1.0f - rawT) * (1.0f - rawT) * (1.0f - rawT);
                float aggrFactor = Math.min(0.98f, factor + 0.15f);
                yield new float[]{
                        yawDiff * eased * aggrFactor,
                        pitchDiff * eased * aggrFactor
                };
            }
        };
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
        double hDist = Math.max(1e-9, Math.sqrt(dx * dx + dz * dz));
        float desired = (float) -Math.toDegrees(Math.atan2(dy, hDist));
        return desired - client.player.getXRot();
    }

    public static boolean isAligned(Minecraft client, Vec3 target, float yawTol, float pitchTol) {
        if (client == null || client.player == null || target == null) return false;
        return Math.abs(computeYawError(client, target)) <= yawTol
                && Math.abs(computePitchError(client, target)) <= pitchTol;
    }

    public static boolean isStable() {
        return stableTickCount >= stabilityThreshold;
    }

    public static void saveRotation(Minecraft client) {
        if (client == null || client.player == null) return;
        savedPlayerYaw = client.player.getYRot();
        savedPlayerPitch = client.player.getXRot();
        hasSavedRotation = true;
    }

    public static void restoreRotation(Minecraft client) {
        if (!hasSavedRotation || client == null || client.player == null) return;
        float dYaw = Mth.wrapDegrees(savedPlayerYaw - client.player.getYRot());
        float dPitch = savedPlayerPitch - client.player.getXRot();
        applyGCDRotation(client, dYaw, dPitch);
        client.player.setYRot(savedPlayerYaw);
        client.player.setXRot(savedPlayerPitch);
        hasSavedRotation = false;
        yawVelocity = 0.0f;
        pitchVelocity = 0.0f;
        active = false;
    }

    public static void resetVelocity() {
        yawVelocity = 0.0f;
        pitchVelocity = 0.0f;
        stableTickCount = 0;
    }

    public static void lock() {
        locked = true;
    }

    public static void unlock() {
        locked = false;
    }

    public static void reset() {
        active = false;
        locked = false;
        yawVelocity = 0.0f;
        pitchVelocity = 0.0f;
        stableTickCount = 0;
        hasSavedRotation = false;
        YAW_HISTORY.clear();
        PITCH_HISTORY.clear();
        TIMESTAMP_HISTORY.clear();
    }

    private static void pushHistory(float yaw, float pitch) {
        if (YAW_HISTORY.size() >= HISTORY_CAP) {
            YAW_HISTORY.pollFirst();
            PITCH_HISTORY.pollFirst();
            TIMESTAMP_HISTORY.pollFirst();
        }
        YAW_HISTORY.offerLast(yaw);
        PITCH_HISTORY.offerLast(pitch);
        TIMESTAMP_HISTORY.offerLast(System.currentTimeMillis());
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

    public static void setPriority(RotationPriority p) {
        priority = p;
    }

    public static void setStabilityThreshold(int ticks) {
        stabilityThreshold = Math.max(1, ticks);
    }

    public static void setStabilityTolerances(float yawTol, float pitchTol) {
        stabilityYawTolerance = yawTol;
        stabilityPitchTolerance = pitchTol;
    }

    public static void setJitterMagnitude(double mag) {
        jitterMagnitude = Math.max(0.0, mag);
    }

    public static void setSpringParameters(double stiffness, double damping) {
        springStiffness = Math.max(0.01, stiffness);
        springDamping = Math.max(0.01, damping);
        ROTATION_REGISTRY.put("SpringStiffness", springStiffness);
        ROTATION_REGISTRY.put("SpringDamping", springDamping);
    }

    public static void setDeadzoneThreshold(double threshold) {
        deadzoneThreshold = Math.max(0.001, threshold);
    }

    public static void setMaxSteps(float maxYaw, float maxPitch) {
        maxYawStep = Math.max(0.1f, maxYaw);
        maxPitchStep = Math.max(0.1f, maxPitch);
    }

    public static boolean isActive() {
        return active;
    }

    public static boolean isLocked() {
        return locked;
    }

    public static float getCurrentYaw() {
        return currentYaw;
    }

    public static float getCurrentPitch() {
        return currentPitch;
    }

    public static float getLastTargetYaw() {
        return lastTargetYaw;
    }

    public static float getLastTargetPitch() {
        return lastTargetPitch;
    }

    public static long getTotalInvocations() {
        return totalRotationInvocations;
    }

    public static int getHistorySize() {
        return YAW_HISTORY.size();
    }

    public static EasingMode getEasingMode() {
        return easingMode;
    }

    public static UUID getSubsessionIdentity() {
        return SUBSESSION_ID;
    }
}
