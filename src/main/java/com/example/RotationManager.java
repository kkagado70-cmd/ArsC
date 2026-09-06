package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.ArrayList;

public class RotationManager {
    private static final Minecraft mc = Minecraft.getInstance();
    private static final SecureRandom secureRandom = new SecureRandom();
    private static final Deque<RotationSample> historyBuffer = new ArrayDeque<>();
    private static final List<RotationListener> listeners = new ArrayList<>();
    
    private static float currentYaw = 0.0f;
    private static float currentPitch = 0.0f;
    private static float targetYaw = 0.0f;
    private static float targetPitch = 0.0f;
    private static float prevYaw = 0.0f;
    private static float prevPitch = 0.0f;
    private static double velocityYaw = 0.0;
    private static double velocityPitch = 0.0;
    private static long executionStart = 0L;
    private static long totalDuration = 0L;
    private static boolean active = false;
    private static RotationProfile currentProfile = RotationProfile.EXOTIC_BEZIER;
    private static int tickCounter = 0;
    private static float accumulatedJitter = 0.0f;
    private static boolean silentMode = true;

    public enum RotationProfile {
        EXOTIC_BEZIER,
        GAUSSIAN_SPLINE,
        HARMONIC_SINE,
        ADAPTIVE_GCD,
        ELASTIC_SNAP,
        CUBIC_HERMITE
    }

    public interface RotationListener {
        void onRotationUpdate(float yaw, float pitch);
        void onRotationFinished();
    }

    public static class RotationSample {
        public final float yaw;
        public final float pitch;
        public final long timestamp;
        public final int tick;

        public RotationSample(float y, float p, long t, int k) {
            this.yaw = y;
            this.pitch = p;
            this.timestamp = t;
            this.tick = k;
        }
    }

    public static void registerListener(RotationListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public static void unregisterListener(RotationListener listener) {
        listeners.remove(listener);
    }

    public static void initializeEngine() {
        if (mc.player != null) {
            currentYaw = mc.player.getYRot();
            currentPitch = mc.player.getXRot();
            targetYaw = currentYaw;
            targetPitch = currentPitch;
            prevYaw = currentYaw;
            prevPitch = currentPitch;
        }
        historyBuffer.clear();
        active = false;
        tickCounter = 0;
        accumulatedJitter = 0.0f;
    }

    public static void executeBypassRotation(Vec3 destination, RotationProfile profile, long durationMs, boolean silent) {
        if (mc.player == null) return;
        
        silentMode = silent;
        prevYaw = mc.player.getYRot();
        prevPitch = mc.player.getXRot();
        currentYaw = prevYaw;
        currentPitch = prevPitch;
        
        double diffX = destination.x - mc.player.getX();
        double diffY = destination.y - mc.player.getEyeY();
        double diffZ = destination.z - mc.player.getZ();
        double horizontalDistance = Math.sqrt(diffX * diffX + diffZ * diffZ);

        float calculatedYaw = (float) (Math.toDegrees(Math.atan2(diffZ, diffX)) - 90.0D);
        float calculatedPitch = (float) (-Math.toDegrees(Math.atan2(diffY, horizontalDistance)));

        targetYaw = currentYaw + Mth.wrapDegrees(calculatedYaw - currentYaw);
        targetPitch = Mth.clamp(calculatedPitch, -90.0F, 90.0F);

        currentProfile = profile;
        executionStart = System.currentTimeMillis();
        totalDuration = Math.max(15L, durationMs + secureRandom.nextInt(35));
        active = true;
        tickCounter = 0;
    }

    public static void handleClientTick(Minecraft client) {
        if (!active || client.player == null) return;

        tickCounter++;
        long elapsed = System.currentTimeMillis() - executionStart;
        float progress = (float) elapsed / (float) totalDuration;

        if (progress >= 1.0f) {
            applyFinalizedAngles(client, targetYaw, targetPitch);
            active = false;
            notifyListenersFinished();
            return;
        }

        float interpolatedYaw = evaluateInterpolationCurve(prevYaw, targetYaw, progress, currentProfile);
        float interpolatedPitch = evaluateInterpolationCurve(prevPitch, targetPitch, progress, currentProfile);

        accumulatedJitter = (float)((secureRandom.nextDouble() - 0.5) * 0.12);
        interpolatedYaw += accumulatedJitter;
        interpolatedPitch += accumulatedJitter * 0.75f;

        applyFinalizedAngles(client, interpolatedYaw, interpolatedPitch);
        notifyListenersUpdate(interpolatedYaw, interpolatedPitch);
        
        historyBuffer.addFirst(new RotationSample(interpolatedYaw, interpolatedPitch, System.currentTimeMillis(), tickCounter));
        if (historyBuffer.size() > 32) {
            historyBuffer.removeLast();
        }
    }

    private static float evaluateInterpolationCurve(float start, float end, float t, RotationProfile profile) {
        return switch (profile) {
            case EXOTIC_BEZIER -> (float)(Math.pow(1.0f - t, 3.0f) * start + 3.0f * Math.pow(1.0f - t, 2.0f) * t * (start + (end - start) * 0.35f) + 3.0f * (1.0f - t) * Math.pow(t, 2.0f) * (end - (end - start) * 0.15f) + Math.pow(t, 3.0f) * end);
            case GAUSSIAN_SPLINE -> (float)(start + (end - start) * (1.0f - Math.exp(-t * 6.0f)));
            case HARMONIC_SINE -> (float)(start + (end - start) * (0.5f - Math.cos(t * Math.PI) * 0.5f));
            case ADAPTIVE_GCD -> (float)(start + (end - start) * (Math.sin(t * Math.PI / 2.0f)));
            case ELASTIC_SNAP -> (float)(start + (end - start) * (Math.pow(2.0, -10 * t) * Math.sin((t - 0.075) * (2 * Math.PI) / 0.3) + 1));
            case CUBIC_HERMITE -> (float)(start + (end - start) * (3.0f * t * t - 2.0f * t * t * t));
        };
    }

    private static void applyFinalizedAngles(Minecraft client, float rawYaw, float rawPitch) {
        if (client.player == null) return;

        double sensitivity = client.options.sensitivity().get();
        double multiplier = sensitivity * 0.6D + 0.2D;
        double gcd = multiplier * multiplier * multiplier * 8.0D * 0.15D;

        float snappedYaw = (float) (client.player.getYRot() + Math.round((rawYaw - client.player.getYRot()) / gcd) * gcd);
        float snappedPitch = (float) (client.player.getXRot() + Math.round((rawPitch - client.player.getXRot()) / gcd) * gcd);

        float deltaYaw = snappedYaw - client.player.getYRot();
        float deltaPitch = snappedPitch - client.player.getXRot();

        if (silentMode) {
            client.player.setYRot(snappedYaw);
            client.player.setXRot(Mth.clamp(snappedPitch, -90.0F, 90.0F));
        } else {
            client.player.setYRot(snappedYaw);
            client.player.setXRot(Mth.clamp(snappedPitch, -90.0F, 90.0F));
            client.player.turn(deltaYaw / (float)(sensitivity * 0.6D + 0.2D), -deltaPitch / (float)(sensitivity * 0.6D + 0.2D));
        }
        
        currentYaw = snappedYaw;
        currentPitch = Mth.clamp(snappedPitch, -90.0F, 90.0F);
    }

    private static void notifyListenersUpdate(float yaw, float pitch) {
        for (RotationListener l : listeners) {
            l.onRotationUpdate(yaw, pitch);
        }
    }

    private static void notifyListenersFinished() {
        for (RotationListener l : listeners) {
            l.onRotationFinished();
        }
    }

    public static boolean isRotationActive() { return active; }
    public static float fetchCurrentYaw() { return currentYaw; }
    public static float fetchCurrentPitch() { return currentPitch; }
    
    public static void purgeEngineState() {
        active = false;
        historyBuffer.clear();
        listeners.clear();
        tickCounter = 0;
    }

    public static void verifyEngineHealth(Minecraft client) {
        if (client.player == null) {
            purgeEngineState();
        }
    }

    public static void calibrateSensitivities() {
        velocityYaw = 0.0;
        velocityPitch = 0.0;
    }

    public static Deque<RotationSample> fetchHistoryTrace() { return historyBuffer; }

    public static double computeAngleDelta(float yaw1, float pitch1, float yaw2, float pitch2) {
        float dy = Mth.wrapDegrees(yaw1 - yaw2);
        float dp = pitch1 - pitch2;
        return Math.sqrt(dy * dy + dp * dp);
    }

    public static void simulateInertiaDrift(float intensity) {
        if (!active && mc.player != null) {
            currentYaw += (float)(secureRandom.nextGaussian() * intensity);
            currentPitch += (float)(secureRandom.nextGaussian() * intensity);
        }
    }

    public static boolean validateGcdCompliance(float delta, double gcd) {
        double remainder = Math.abs(delta % gcd);
        return remainder < 0.0001 || Math.abs(remainder - gcd) < 0.0001;
    }

    public static float applyMathematicalNoise(float value, float amplitude) {
        return value + (float)((secureRandom.nextDouble() - 0.5) * amplitude);
    }

    public static void flushInputBuffer() { historyBuffer.clear(); }

    public static void overrideTargetCoordinates(float yaw, float pitch) {
        targetYaw = yaw;
        targetPitch = Mth.clamp(pitch, -90.0f, 90.0f);
    }

    public static long fetchExecutionDuration() { return totalDuration; }
    public static RotationProfile fetchActiveProfile() { return currentProfile; }

    public static void stepExecutionCycle() {
        if (active && mc.player != null) {
            handleClientTick(mc);
        }
    }

    public static float calculateOptimalYaw(Vec3 source, Vec3 destination) {
        double dx = destination.x - source.x;
        double dz = destination.z - source.z;
        return (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
    }

    public static float calculateOptimalPitch(Vec3 source, Vec3 destination) {
        double dx = destination.x - source.x;
        double dy = destination.y - source.y;
        double dz = destination.z - source.z;
        double hDist = Math.sqrt(dx * dx + dz * dz);
        return (float) (-Math.toDegrees(Math.atan2(dy, hDist)));
    }

    public static boolean isWithinPitchBounds(float pitch) {
        return pitch >= -90.0f && pitch <= 90.0f;
    }

    public static void emergencyReset(Minecraft client) {
        if (client.player != null) {
            client.player.setYRot(currentYaw);
            client.player.setXRot(currentPitch);
        }
        purgeEngineState();
    }
}