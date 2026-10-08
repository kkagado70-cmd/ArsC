package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Random;

public class RotationManager {

    private static final Random RNG = new Random();

    private static float yawVelocity    = 0.0f;
    private static float pitchVelocity  = 0.0f;
    private static int   stableCount    = 0;
    private static float savedYaw       = 0.0f;
    private static float savedPitch     = 0.0f;
    private static boolean hasSaved     = false;

    private static final Deque<Float> YAW_HIST   = new ArrayDeque<>();
    private static final Deque<Float> PITCH_HIST  = new ArrayDeque<>();
    private static final int          HIST_CAP    = 64;

    private static float sampledGcd = 0.0f;
    private static int   gcdSamples = 0;

    public static void smoothTo(Minecraft mc, Vec3 target, float factor) {
        if (mc == null || mc.player == null || target == null) return;

        float[] desired = angles(mc, target);
        float dYaw   = Mth.wrapDegrees(desired[0] - mc.player.getYRot());
        float dPitch = desired[1] - mc.player.getXRot();

        float angDist = (float) Math.sqrt(dYaw * dYaw + dPitch * dPitch);
        float speedScale = computeSpeedScale(angDist, factor);

        float stepYaw   = applySpring(dYaw,   yawVelocity,   speedScale);
        float stepPitch = applySpring(dPitch, pitchVelocity, speedScale);

        yawVelocity   = stepYaw;
        pitchVelocity = stepPitch;

        stepYaw   = quantizeToGcd(mc, stepYaw);
        stepPitch = quantizeToGcd(mc, stepPitch);

        stepYaw   += humanNoise(angDist);
        stepPitch += humanNoise(angDist) * 0.55f;

        float nextYaw   = mc.player.getYRot()   + stepYaw;
        float nextPitch = Mth.clamp(mc.player.getXRot() + stepPitch, -89.9f, 89.9f);

        nextYaw   = avoidExactInteger(nextYaw);
        nextPitch = avoidExactInteger(nextPitch);

        sendTurn(mc, nextYaw - mc.player.getYRot(), nextPitch - mc.player.getXRot());
        mc.player.setYRot(Mth.wrapDegrees(nextYaw));
        mc.player.setXRot(nextPitch);

        if (Math.abs(dYaw) < 1.5f && Math.abs(dPitch) < 1.5f) stableCount++;
        else stableCount = 0;

        pushHist(nextYaw, nextPitch);
    }

    public static void snapTo(Minecraft mc, Vec3 target) {
        if (mc == null || mc.player == null || target == null) return;

        float[] desired = angles(mc, target);
        float dYaw   = Mth.wrapDegrees(desired[0] - mc.player.getYRot());
        float dPitch = desired[1] - mc.player.getXRot();

        dYaw   = quantizeToGcd(mc, dYaw);
        dPitch = quantizeToGcd(mc, dPitch);

        dYaw   += humanNoise(90.0f);
        dPitch += humanNoise(90.0f) * 0.45f;

        float nextYaw   = avoidExactInteger(mc.player.getYRot() + dYaw);
        float nextPitch = avoidExactInteger(Mth.clamp(mc.player.getXRot() + dPitch, -89.9f, 89.9f));

        sendTurn(mc, dYaw, dPitch);
        mc.player.setYRot(Mth.wrapDegrees(nextYaw));
        mc.player.setXRot(nextPitch);

        yawVelocity   = dYaw;
        pitchVelocity = dPitch;
        stableCount   = 0;

        pushHist(nextYaw, nextPitch);
    }

    public static boolean isAligned(Minecraft mc, Vec3 target, float yawTol, float pitchTol) {
        if (mc == null || mc.player == null || target == null) return false;
        float[] desired = angles(mc, target);
        return Math.abs(Mth.wrapDegrees(desired[0] - mc.player.getYRot())) <= yawTol
            && Math.abs(desired[1] - mc.player.getXRot()) <= pitchTol;
    }

    public static boolean isStable() { return stableCount >= 2; }

    public static void saveRotation(Minecraft mc) {
        if (mc == null || mc.player == null) return;
        savedYaw   = mc.player.getYRot();
        savedPitch = mc.player.getXRot();
        hasSaved   = true;
    }

    public static void restoreRotation(Minecraft mc) {
        if (!hasSaved || mc == null || mc.player == null) return;
        float dYaw   = Mth.wrapDegrees(savedYaw   - mc.player.getYRot());
        float dPitch = savedPitch - mc.player.getXRot();
        sendTurn(mc, dYaw, dPitch);
        mc.player.setYRot(savedYaw);
        mc.player.setXRot(savedPitch);
        hasSaved      = false;
        yawVelocity   = 0.0f;
        pitchVelocity = 0.0f;
    }

    public static void reset() {
        yawVelocity   = 0.0f;
        pitchVelocity = 0.0f;
        stableCount   = 0;
        hasSaved      = false;
        YAW_HIST.clear();
        PITCH_HIST.clear();
    }

    public static void lock()   {}
    public static void unlock() {}
    public static boolean isActive() { return true; }
    public static float getCurrentYaw()   { return YAW_HIST.isEmpty()   ? 0 : ((ArrayDeque<Float>)YAW_HIST).peekLast(); }
    public static float getCurrentPitch() { return PITCH_HIST.isEmpty() ? 0 : ((ArrayDeque<Float>)PITCH_HIST).peekLast(); }
    public static float computeYawError(Minecraft mc, Vec3 t)   { if(mc==null||mc.player==null)return 0; return Mth.wrapDegrees(angles(mc,t)[0]-mc.player.getYRot()); }
    public static float computePitchError(Minecraft mc, Vec3 t) { if(mc==null||mc.player==null)return 0; return angles(mc,t)[1]-mc.player.getXRot(); }

    public static void samplePlayerGcd(Minecraft mc) {
        if (mc == null || mc.player == null) return;
        if (YAW_HIST.size() < 2) return;
        Float[] arr = YAW_HIST.toArray(new Float[0]);
        float delta = Math.abs(Mth.wrapDegrees(arr[arr.length-1] - arr[arr.length-2]));
        if (delta > 0.001f) {
            sampledGcd = gcdSamples == 0 ? delta : euclidGcd(sampledGcd, delta);
            gcdSamples++;
        }
    }

    private static float computeSpeedScale(float angDist, float factor) {
        if (angDist < 0.01f) return 0.0f;
        float base = Math.min(1.0f, factor);
        if (angDist < 8.0f) {
            float close = angDist / 8.0f;
            float decel = 0.28f + 0.72f * close * close;
            base *= decel;
        }
        return Math.max(0.04f, base);
    }

    private static float applySpring(float diff, float vel, float scale) {
        float stiff  = 0.22f;
        float damp   = 0.74f;
        float force  = diff * stiff - vel * damp;
        float newVel = vel + force;
        float maxStep = Math.max(Math.abs(diff) * scale, 0.01f);
        return Mth.clamp(newVel, -maxStep * 2.5f, maxStep * 2.5f);
    }

    private static float quantizeToGcd(Minecraft mc, float delta) {
        double sens = mc.options.sensitivity().get() * 0.6 + 0.2;
        double gcd  = sens * sens * sens * 8.0;
        if (gcd < 0.0001) return delta;

        float effective = sampledGcd > 0.001f ? sampledGcd : (float) gcd;
        float rounded = Math.round(delta / effective) * effective;
        float noise   = (RNG.nextFloat() - 0.5f) * effective * 0.22f;
        return rounded + noise;
    }

    private static float humanNoise(float angDist) {
        float base = 0.012f + angDist * 0.00015f;
        float g    = (float)(RNG.nextGaussian() * base);
        if (RNG.nextFloat() < 0.06f) g += (RNG.nextFloat() - 0.5f) * 0.08f;
        return g;
    }

    private static float avoidExactInteger(float v) {
        float frac = v - (float)Math.floor(v);
        if (frac < 0.005f || frac > 0.995f) {
            v += (RNG.nextBoolean() ? 1 : -1) * (0.006f + RNG.nextFloat() * 0.016f);
        }
        return v;
    }

    private static void sendTurn(Minecraft mc, float dYaw, float dPitch) {
        if (mc.player == null) return;
        double sens = mc.options.sensitivity().get() * 0.6 + 0.2;
        double gcd  = sens * sens * sens * 8.0;
        if (gcd < 0.0001) return;
        double ry = Math.round(dYaw   / gcd) * gcd;
        double rp = Math.round(dPitch / gcd) * gcd;
        mc.player.turn(ry / 0.15, rp / 0.15);
    }

    private static float[] angles(Minecraft mc, Vec3 t) {
        double dx = t.x - mc.player.getX();
        double dy = t.y - mc.player.getEyeY();
        double dz = t.z - mc.player.getZ();
        double h  = Math.max(1e-9, Math.sqrt(dx*dx + dz*dz));
        float yaw   = (float)(Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float)(-Math.toDegrees(Math.atan2(dy, h)));
        return new float[]{ yaw, Mth.clamp(pitch, -89.9f, 89.9f) };
    }

    private static float euclidGcd(float a, float b) {
        a = Math.abs(a); b = Math.abs(b);
        while (b > 0.0001f) { float t = b; b = a % b; a = t; }
        return a;
    }

    private static void pushHist(float y, float p) {
        if (YAW_HIST.size() >= HIST_CAP) { YAW_HIST.pollFirst(); PITCH_HIST.pollFirst(); }
        YAW_HIST.offerLast(y);
        PITCH_HIST.offerLast(p);
    }

    public static float getYawVelocityEstimate()   { return yawVelocity; }
    public static float getPitchVelocityEstimate() { return pitchVelocity; }
    public static int   getHistorySize()            { return YAW_HIST.size(); }

    public static void applyGCDRotation(Minecraft mc, double dYaw, double dPitch) {
        sendTurn(mc, (float)dYaw, (float)dPitch);
    }
}
