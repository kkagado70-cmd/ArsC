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
    private static final Deque<Float> YAW_QUEUE = new ArrayDeque<>();
    private static final Deque<Float> PITCH_QUEUE = new ArrayDeque<>();
    private static final int HISTORY_CAP = 512;

    private static long totalRotations = 0L;
    private static double deadzoneThreshold = 0.005D;
    private static double rotationalJitter = 0.008D;
    private static boolean hardwareBypass = true;
    private static float maxPitch = 90.0F;
    private static float minPitch = -90.0F;
    private static boolean easingCurveActive = true;

    static {
        ROTATION_REGISTRY.put("SubsessionUUID", SUBSESSION_ID);
        ROTATION_REGISTRY.put("Profile", "Enterprise-RotationManager");
    }

    public static void smoothTo(Minecraft client, Vec3 target, float factor) {
        if (client.player == null || target == null) return;
        totalRotations++;

        double diffX = target.x - client.player.getX();
        double diffY = target.y - client.player.getEyeY();
        double diffZ = target.z - client.player.getZ();
        double hDist = Math.sqrt(diffX * diffX + diffZ * diffZ);

        float calcYaw = (float) (Math.toDegrees(Math.atan2(diffZ, diffX)) - 90.0D);
        float calcPitch = (float) (-Math.toDegrees(Math.atan2(diffY, hDist)));

        if (Math.abs(calcYaw - currentYaw) < deadzoneThreshold && Math.abs(calcPitch - currentPitch) < deadzoneThreshold) {
            if (totalRotations % 8 == 0) {
                currentYaw += (float)((secureRandom.nextDouble() - 0.5) * 0.012D);
                currentPitch += (float)((secureRandom.nextDouble() - 0.5) * 0.012D);
            }
            return;
        }

        calcYaw += (float)((secureRandom.nextGaussian() * rotationalJitter) + ((secureRandom.nextDouble() - 0.5) * 0.003D));
        calcPitch += (float)((secureRandom.nextGaussian() * rotationalJitter) + ((secureRandom.nextDouble() - 0.5) * 0.003D));

        float targetYaw = currentYaw + Mth.wrapDegrees(calcYaw - currentYaw);
        float targetPitch = Mth.clamp(calcPitch, minPitch, maxPitch);

        if (easingCurveActive) {
            float easingProgress = Mth.clamp(factor + (float)((secureRandom.nextDouble() - 0.5) * 0.02D), 0.05f, 0.96f);
            currentYaw = currentYaw + (targetYaw - currentYaw) * easingProgress;
            currentPitch = currentPitch + (targetPitch - currentPitch) * easingProgress;
        } else {
            currentYaw = targetYaw;
            currentPitch = targetPitch;
        }

        if (YAW_QUEUE.size() >= HISTORY_CAP) YAW_QUEUE.pollFirst();
        YAW_QUEUE.offerLast(currentYaw);
        if (PITCH_QUEUE.size() >= HISTORY_CAP) PITCH_QUEUE.pollFirst();
        PITCH_QUEUE.offerLast(currentPitch);

        client.player.setYRot(currentYaw);
        client.player.setXRot(currentPitch);

        if (hardwareBypass && client.options != null) {
            double sens = client.options.sensitivity().get() * 0.6D + 0.2D;
            double gcd = sens * sens * sens * 8.0D;
            if (gcd > 0.0D) {
                double dY = (currentYaw - client.player.getYRot());
                double dP = (currentPitch - client.player.getXRot());
                client.player.turn(dY / (gcd * 0.15D), dP / (gcd * 0.15D));
            }
        }
        active = true;
    }

    public static boolean isRotationActive() { return active; }
    public static UUID getSubsessionIdentity() { return SUBSESSION_ID; }
}