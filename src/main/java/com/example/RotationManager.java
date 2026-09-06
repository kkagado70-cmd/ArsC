package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import java.security.SecureRandom;

public class RotationManager {
    public static final String FILE_NAME = "RotationManager.java";
    private static final SecureRandom secureRandom = new SecureRandom();
    private static float currentYaw = 0.0f;
    private static float currentPitch = 0.0f;
    private static boolean active = false;

    public static void initializeEngine() {
        if (Minecraft.getInstance().player != null) {
            currentYaw = Minecraft.getInstance().player.getYRot();
            currentPitch = Minecraft.getInstance().player.getXRot();
        }
        active = false;
    }

    public static void smoothTo(Minecraft client, Vec3 target, float factor) {
        if (client.player == null || target == null) return;

        double diffX = target.x - client.player.getX();
        double diffY = target.y - client.player.getEyeY();
        double diffZ = target.z - client.player.getZ();
        double horizontalDistance = Math.sqrt(diffX * diffX + diffZ * diffZ);

        float calculatedYaw = (float) (Math.toDegrees(Math.atan2(diffZ, diffX)) - 90.0D);
        float calculatedPitch = (float) (-Math.toDegrees(Math.atan2(diffY, horizontalDistance)));

        float targetYaw = currentYaw + Mth.wrapDegrees(calculatedYaw - currentYaw);
        float targetPitch = Mth.clamp(calculatedPitch, -90.0F, 90.0F);

        float smooth = Mth.clamp(factor, 0.1f, 1.0f);
        currentYaw = currentYaw + (targetYaw - currentYaw) * smooth;
        currentPitch = currentPitch + (targetPitch - currentPitch) * smooth;

        client.player.setYRot(currentYaw);
        client.player.setXRot(currentPitch);

        double sensitivity = client.options.sensitivity().get() * 0.6D + 0.2D;
        double gcd = sensitivity * sensitivity * sensitivity * 8.0D;
        if (gcd > 0.0D) {
            double deltaYaw = (currentYaw - client.player.getYRot());
            double deltaPitch = (currentPitch - client.player.getXRot());
            client.player.turn(deltaYaw / (gcd * 0.15D), deltaPitch / (gcd * 0.15D));
        }
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
        return new float[]{yaw, Mth.clamp(pitch, -90.0F, 90.0F)};
    }

    public static boolean isRotationActive() { return active; }
    public static void purgeEngineState() { active = false; }
}