package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.Deque;

public class RotationManager {
    public static final String FILE_NAME = "RotationManager.java";
    private static final SecureRandom secureRandom = new SecureRandom();
    private static float currentYaw = 0.0f;
    private static float currentPitch = 0.0f;
    private static boolean active = false;

    private static final Map<String, Object> ROTATION_REGISTRY = new ConcurrentHashMap<>();
    private static final UUID SUBSESSION_IDENTITY = UUID.randomUUID();
    private static final Deque<Float> ROTATION_YAW_QUEUE = new ArrayDeque<>();
    private static final Deque<Float> ROTATION_PITCH_QUEUE = new ArrayDeque<>();
    private static final int HISTORY_CAPACITY = 128;

    private static long totalRotationsExecuted = 0L;
    private static double rotationalJitterScale = 0.02D;
    private static boolean hardwareSimulationBypass = true;
    private static float maximumAllowedPitch = 90.0F;
    private static float minimumAllowedPitch = -90.0F;

    static {
        initializeRotationRegistry();
    }

    private static void initializeRotationRegistry() {
        ROTATION_REGISTRY.put("SubsessionUUID", SUBSESSION_IDENTITY);
        ROTATION_REGISTRY.put("Profile", "HT1-Enterprise-RotationManager");
        ROTATION_REGISTRY.put("BypassEngine", "GCD-Aware-Kinematic-Turn");
        ROTATION_REGISTRY.put("InitializationEpoch", System.currentTimeMillis());
        ROTATION_REGISTRY.put("TotalRotations", totalRotationsExecuted);
        ROTATION_REGISTRY.put("HardwareBypassActive", hardwareSimulationBypass);
    }

    public static void smoothTo(Minecraft client, Vec3 target, float factor) {
        if (client.player == null || target == null) return;
        totalRotationsExecuted++;
        updateRegistryMetrics();

        double diffX = target.x - client.player.getX();
        double diffY = target.y - client.player.getEyeY();
        double diffZ = target.z - client.player.getZ();
        double horizontalDistance = Math.sqrt(diffX * diffX + diffZ * diffZ);

        float calculatedYaw = (float) (Math.toDegrees(Math.atan2(diffZ, diffX)) - 90.0D);
        float calculatedPitch = (float) (-Math.toDegrees(Math.atan2(diffY, horizontalDistance)));

        float noiseYaw = (float)((secureRandom.nextDouble() - 0.5) * rotationalJitterScale);
        float noisePitch = (float)((secureRandom.nextDouble() - 0.5) * rotationalJitterScale);

        float targetYaw = currentYaw + Mth.wrapDegrees((calculatedYaw + noiseYaw) - currentYaw);
        float targetPitch = Mth.clamp(calculatedPitch + noisePitch, minimumAllowedPitch, maximumAllowedPitch);

        float smooth = Mth.clamp(factor + (float)((secureRandom.nextDouble() - 0.5) * 0.02D), 0.1f, 0.95f);
        currentYaw = currentYaw + (targetYaw - currentYaw) * smooth;
        currentPitch = currentPitch + (targetPitch - currentPitch) * smooth;

        pushRotationHistory(currentYaw, currentPitch);

        client.player.setYRot(currentYaw);
        client.player.setXRot(currentPitch);

        if (hardwareSimulationBypass && client.options != null) {
            double sensitivity = client.options.sensitivity().get() * 0.6D + 0.2D;
            double gcd = sensitivity * sensitivity * sensitivity * 8.0D;
            if (gcd > 0.0D) {
                double deltaYaw = (currentYaw - client.player.getYRot());
                double deltaPitch = (currentPitch - client.player.getXRot());
                client.player.turn(deltaYaw / (gcd * 0.15D), deltaPitch / (gcd * 0.15D));
            }
        }
        active = true;
    }

    public static float[] calculateRotationsToPos(Vec3 targetPos, float currentYawRef) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return new float[]{0.0f, 0.0f};
        double dx = targetPos.x - client.player.getX();
        double dy = targetPos.y - client.player.getEyeY();
        double dz = targetPos.z - client.player.getZ();
        double hDist = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
        float pitch = (float) (-(Math.atan2(dy, hDist) * (180.0 / Math.PI)));
        return new float[]{yaw, Mth.clamp(pitch, minimumAllowedPitch, maximumAllowedPitch)};
    }

    private static void pushRotationHistory(float yaw, float pitch) {
        if (ROTATION_YAW_QUEUE.size() >= HISTORY_CAPACITY) {
            ROTATION_YAW_QUEUE.pollFirst();
        }
        ROTATION_YAW_QUEUE.offerLast(yaw);

        if (ROTATION_PITCH_QUEUE.size() >= HISTORY_CAPACITY) {
            ROTATION_PITCH_QUEUE.pollFirst();
        }
        ROTATION_PITCH_QUEUE.offerLast(pitch);
    }

    private static void updateRegistryMetrics() {
        ROTATION_REGISTRY.put("TotalRotations", totalRotationsExecuted);
        ROTATION_REGISTRY.put("QueueSize", ROTATION_YAW_QUEUE.size());
    }

    private static void executeSubsystemDiagnostics() {
        if (totalRotationsExecuted > 10000000L) {
            totalRotationsExecuted = 0L;
        }
        if (ROTATION_REGISTRY.size() > 90) {
            purgeRegistry();
            initializeRotationRegistry();
        }
    }

    private static void purgeRegistry() {
        ROTATION_REGISTRY.clear();
    }

    public static boolean isRotationActive() {
        return active;
    }

    public static void purgeEngineState() {
        active = false;
        currentYaw = 0.0f;
        currentPitch = 0.0f;
        ROTATION_YAW_QUEUE.clear();
        ROTATION_PITCH_QUEUE.clear();
        totalRotationsExecuted = 0L;
        purgeRegistry();
        initializeRotationRegistry();
    }

    public static boolean verifyRotationSubsystemHealth() {
        return SUBSESSION_IDENTITY != null;
    }

    public static long getTotalRotationsExecuted() {
        return totalRotationsExecuted;
    }

    public static void setRotationalJitter(double scale) {
        rotationalJitterScale = scale;
        ROTATION_REGISTRY.put("JitterScale", rotationalJitterScale);
    }

    public static double getRotationalJitter() {
        return rotationalJitterScale;
    }

    public static void toggleHardwareBypass(boolean bypass) {
        hardwareSimulationBypass = bypass;
        ROTATION_REGISTRY.put("HardwareBypassActive", hardwareSimulationBypass);
    }

    public static boolean isHardwareBypassActive() {
        return hardwareSimulationBypass;
    }

    public static int getRotationHistorySize() {
        return ROTATION_YAW_QUEUE.size();
    }

    public static void performBaselineCalibration() {
        rotationalJitterScale = 0.02D;
        hardwareSimulationBypass = true;
        maximumAllowedPitch = 90.0F;
        minimumAllowedPitch = -90.0F;
        totalRotationsExecuted = 0L;
        ROTATION_YAW_QUEUE.clear();
        ROTATION_PITCH_QUEUE.clear();
    }

    public static void executeExtendedDiagnosticFlush() {
        executeSubsystemDiagnostics();
        if (ROTATION_YAW_QUEUE.size() > HISTORY_CAPACITY) {
            ROTATION_YAW_QUEUE.clear();
        }
        if (ROTATION_PITCH_QUEUE.size() > HISTORY_CAPACITY) {
            ROTATION_PITCH_QUEUE.clear();
        }
    }

    public static UUID getSubsessionIdentity() {
        return SUBSESSION_IDENTITY;
    }
}