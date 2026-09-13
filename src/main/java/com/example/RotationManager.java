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
    private static final UUID SUBSESSION_ID = UUID.randomUUID();
    private static final Deque<Float> YAW_HISTORY_QUEUE = new ArrayDeque<>();
    private static final Deque<Float> PITCH_HISTORY_QUEUE = new ArrayDeque<>();
    private static final int HISTORY_CAP = 512;

    private static long totalRotations = 0L;
    private static double deadzoneThreshold = 0.02D;
    private static double rotationalJitter = 0.00005D;
    private static boolean hardwareBypass = true;
    private static float maxPitch = 90.0F;
    private static float minPitch = -90.0F;
    private static boolean easingCurveActive = true;

    static {
        ROTATION_REGISTRY.put("SubsessionUUID", SUBSESSION_ID);
        ROTATION_REGISTRY.put("Profile", "Swight-RotationManager-Enterprise");
    }

    public static void smoothTo(Minecraft client, Vec3 target, float factor) {
        if (client.player == null || target == null) return;
        totalRotations++;

        double diffX = target.x - client.player.getX();
        double diffY = target.y - client.player.getEyeY();
        double diffZ = target.z - client.player.getZ();
        double hDist = Math.sqrt(diffX * diffX + diffZ * diffZ);
        if (hDist < 0.001D) hDist = 0.001D;

        float calcYaw = (float) (Math.toDegrees(Math.atan2(diffZ, diffX)) - 90.0D);
        float calcPitch = (float) (-Math.toDegrees(Math.atan2(diffY, hDist)));
        calcPitch = Mth.clamp(calcPitch, minPitch, maxPitch);

        float curYaw = client.player.getYRot();
        float curPitch = client.player.getXRot();
        float yawDiff = Mth.wrapDegrees(calcYaw - curYaw);
        float pitchDiff = calcPitch - curPitch;

        if (Math.abs(yawDiff) < deadzoneThreshold && Math.abs(pitchDiff) < deadzoneThreshold) {
            active = false;
            return;
        }

        float t = Math.min(1.0f, Math.abs(yawDiff) / 25.0f);
        float eased = 1.0f - (1.0f - t) * (1.0f - t) * (1.0f - t);
        
        float stepYaw = yawDiff * eased * Math.min(0.95f, factor + 0.1f);
        float stepPitch = pitchDiff * eased * Math.min(0.95f, factor + 0.1f);

        if (Float.isNaN(stepYaw) || Float.isInfinite(stepYaw)) stepYaw = 0.0f;
        if (Float.isNaN(stepPitch) || Float.isInfinite(stepPitch)) stepPitch = 0.0f;

        float nextYaw = curYaw + stepYaw + (float)(secureRandom.nextGaussian() * rotationalJitter);
        float nextPitch = Mth.clamp(curPitch + stepPitch + (float)(secureRandom.nextGaussian() * rotationalJitter), minPitch, maxPitch);

        if (YAW_HISTORY_QUEUE.size() >= HISTORY_CAP) YAW_HISTORY_QUEUE.pollFirst();
        YAW_HISTORY_QUEUE.offerLast(nextYaw);
        if (PITCH_HISTORY_QUEUE.size() >= HISTORY_CAP) PITCH_HISTORY_QUEUE.pollFirst();
        PITCH_HISTORY_QUEUE.offerLast(nextPitch);

        if (hardwareBypass && client.options != null) {
            double sens = client.options.sensitivity().get() * 0.6D + 0.2D;
            double gcd = sens * sens * sens * 8.0D;
            if (gcd > 0.0D) {
                double dY = (nextYaw - curYaw);
                double dP = (nextPitch - curPitch);
                client.player.turn(dY / (gcd * 0.15D), dP / (gcd * 0.15D));
            }
        }

        client.player.setYRot(nextYaw);
        client.player.setXRot(nextPitch);
        currentYaw = nextYaw;
        currentPitch = nextPitch;
        active = true;
    }

    public static boolean isRotationActive() {
        return active;
    }

    public static UUID getSubsessionIdentity() {
        return SUBSESSION_ID;
    }
}