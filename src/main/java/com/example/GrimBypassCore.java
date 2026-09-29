package com.example;

import net.minecraft.util.Mth;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * GrimBypassCore — static AC evasion layer.
 * 
 * Woven into TriggerBot (timing) and AimAssist (rotation drift).
 * Not a module — no toggle, no GUI entry, always active when host modules run.
 *
 * Covers:
 *  1. Attack timing — Poisson-distributed inter-attack intervals (Grim detects uniform CPS)
 *  2. Post-hit gate — suppresses next attack for 40–70ms (Grim checks back-to-back ms timing)
 *  3. Rotation drift — ±0.06° Gaussian noise inside GCD grid (Grim runs χ² on rotation histogram)
 *  4. Session reach cap — drawn once per session, never exceeds 3.15 (Grim logs per-session max)
 */
public final class GrimBypassCore {

    private GrimBypassCore() {}

    private static final SecureRandom RNG = new SecureRandom();

    // ── Attack timing ───────────────────────────────────────────────────────────
    private static double sessionReachCap  = 3.10;
    private static final Deque<Long> CPS_WINDOW = new ArrayDeque<>(20);
    private static long  nextAttackAllowed  = 0L;
    private static boolean postHitActive    = false;
    private static long  postHitUntil       = 0L;
    private static double pendingVelAccept  = 1.0;

    // ── Rotation drift ──────────────────────────────────────────────────────────
    private static float  driftYaw   = 0f;
    private static float  driftPitch = 0f;
    private static long   lastDriftMs = 0L;

    static {
        refreshSessionCap(2.95f, 3.15f);
    }

    public static void refreshSessionCap(float lo, float hi) {
        sessionReachCap = lo + (hi - lo) * RNG.nextDouble();
    }

    /**
     * Call before every attack. Returns false if the timing gate says "too soon".
     * targetCps: desired CPS midpoint (e.g. 9.0).
     * jitterMs: Gaussian noise on interval (e.g. 18.0).
     */
    public static boolean canAttack(double targetCps, double jitterMs) {
        long now = System.currentTimeMillis();
        if (postHitActive && now < postHitUntil) return false;
        postHitActive = false;
        if (now < nextAttackAllowed) return false;

        // Poisson-distributed interval — looks like jitter-clicking, not a clock
        double mean = 1000.0 / Math.max(1, targetCps);
        double iv   = -mean * Math.log(Math.max(1e-9, RNG.nextDouble()));
        iv = Math.min(iv, mean * 3.0) + RNG.nextGaussian() * jitterMs;
        iv = Math.max(iv, mean * 0.35);
        nextAttackAllowed = now + (long) iv;

        if (CPS_WINDOW.size() >= 20) CPS_WINDOW.pollFirst();
        CPS_WINDOW.addLast(now);
        return true;
    }

    /**
     * Signal that a hit landed. Arms post-hit delay and randomizes next velocity-accept ratio.
     */
    public static void onHitLanded(double postHitMinMs, double postHitMaxMs,
                                   float velAcceptMin, float velAcceptMax) {
        long delay = (long)(postHitMinMs + (postHitMaxMs - postHitMinMs) * RNG.nextDouble()
                     + RNG.nextGaussian() * 8.0);
        postHitActive = true;
        postHitUntil  = System.currentTimeMillis() + Math.max(10L, delay);
        pendingVelAccept = velAcceptMin / 100f
            + (velAcceptMax - velAcceptMin) / 100f * (float) RNG.nextDouble();
    }

    /**
     * Returns the session-bounded reach cap for this attack.
     * Grim tracks per-session maximum reach — never let a single hit exceed the session cap.
     */
    public static double getReach(float reachMin, float reachMax) {
        double mid   = (reachMin + reachMax) * 0.5;
        double sigma = (reachMax - reachMin) * 0.25;
        double s     = mid + RNG.nextGaussian() * sigma;
        return Math.max(reachMin, Math.min(s, sessionReachCap));
    }

    /** Velocity accept ratio for the current hit (applied to X/Z knockback only). */
    public static double getPendingVelAccept() { return pendingVelAccept; }

    /**
     * Tick the rotation drift. Call once per game tick when aiming is active.
     * magnitude: how wide the drift can be (recommended: 0.05–0.08).
     */
    public static void tickDrift(float magnitude) {
        long now = System.currentTimeMillis();
        if (now - lastDriftMs < 80L + (long)(RNG.nextDouble() * 80)) return;
        lastDriftMs = now;
        driftYaw   = (float) Mth.clamp(RNG.nextGaussian() * magnitude, -0.07, 0.07);
        driftPitch = (float) Mth.clamp(RNG.nextGaussian() * magnitude * 0.6, -0.05, 0.05);
    }

    /**
     * Apply drift to a [yaw, pitch] array AFTER GCD snap.
     * Keeps every step on a GCD-legal position while spreading the histogram.
     */
    public static float[] applyDrift(float[] rotations, float magnitude) {
        if (magnitude <= 0f) return rotations;
        return new float[]{
            rotations[0] + (float) Mth.clamp(driftYaw,   -magnitude * 0.5, magnitude * 0.5),
            Mth.clamp(rotations[1] + (float) Mth.clamp(driftPitch, -magnitude * 0.3, magnitude * 0.3), -90, 90)
        };
    }

    public static double getLiveCPS() {
        long now = System.currentTimeMillis();
        int n = 0; for (long ts : CPS_WINDOW) if (now - ts <= 1000L) n++;
        return n;
    }
}
