package com.example;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * GrimAC bypass core — CPS gate + reach cap + GCD-aware jitter.
 *
 * Grim checks:
 *  - TimerA: attack interval consistent with human CPS
 *  - ReachEntityInteract: eye-to-hitbox > blockInteractionRange (3.0 vanilla)
 *  - AimDuplicateLook: same rotation sent in ≥2 consecutive packets
 *  - GCD: yaw/pitch deltas must be multiples of sensitivity GCD
 */
public final class GrimBypassCore {
    private static final SecureRandom RNG = new SecureRandom();

    // ── CPS gate ─────────────────────────────────────────────────────────
    private static final Deque<Long> HIT_TIMES = new ArrayDeque<>(32);
    private static long  lastAttackMs = 0L;
    // post-hit lockout (simulates nerve delay)
    private static long  postHitLockoutUntil = 0L;
    // reach cap
    private static float sessionReachMin = 2.85f;
    private static float sessionReachMax = 3.00f;

    private GrimBypassCore() {}

    /**
     * Must return true for the module to be allowed to attack.
     * @param targetCps desired CPS (8-10 for Grim-safe)
     * @param jitterMs  ± jitter in ms
     */
    public static boolean canAttack(double targetCps, double jitterMs) {
        long now = System.currentTimeMillis();
        if (now < postHitLockoutUntil) return false;
        long minInterval = (long)(1000.0 / targetCps);
        long jitter = (long)(RNG.nextGaussian() * jitterMs * 0.5);
        return (now - lastAttackMs) >= (minInterval + jitter);
    }

    /**
     * Call after a hit lands. Arms the post-hit lockout.
     * @param postHitMinMs  min post-hit delay (ms)
     * @param postHitMaxMs  max post-hit delay (ms)
     * @param comboMinMs    unused (kept for compat)
     * @param comboMaxMs    unused
     */
    public static void onHitLanded(int postHitMinMs, int postHitMaxMs, int comboMinMs, int comboMaxMs) {
        long now = System.currentTimeMillis();
        lastAttackMs = now;
        // B17: post-hit 20-40ms (not 42-72) to keep CPS ~10
        long lockout = 20 + (long)(RNG.nextDouble() * 20);
        postHitLockoutUntil = now + lockout;
        if (HIT_TIMES.size() >= 32) HIT_TIMES.pollFirst();
        HIT_TIMES.addLast(now);
    }

    /**
     * Returns a GCD-snapped reach value for this session.
     * Grim flags reach > 3.0 (block interaction range).
     */
    public static float getReach(float min, float max) {
        // Never exceed vanilla reach
        float cap = Math.min(max, 3.0f);
        float base = Math.min(min, cap);
        return base + (float)(RNG.nextDouble() * (cap - base));
    }

    public static double getLiveCPS() {
        long now = System.currentTimeMillis();
        long windowMs = 1000L;
        long cutoff = now - windowMs;
        return HIT_TIMES.stream().filter(t -> t >= cutoff).count();
    }

    public static void refreshSessionCap(float min, float max) {
        sessionReachMin = Math.min(min, 3.0f);
        sessionReachMax = Math.min(max, 3.0f);
    }

    /**
     * Called each tick by AimAssist. Applies micro-drift to avoid
     * AimDuplicateLook flags (same rotation ≥ 2 packets).
     * The drift is GCD-aligned so it doesn't look like silent aim.
     * @param magnitude base drift magnitude (degrees)
     */
    public static void tickDrift(float magnitude) {
        // Drift is handled inside RotationManager.samplePlayerGcd + smoothTo
        // This hook exists for future use; RotationManager already snaps to GCD
    }

    /**
     * Snaps a raw rotation delta to the nearest GCD multiple.
     * Grim derives sensitivity from GCD of yaw/pitch deltas.
     * If delta is not a GCD multiple, it's flagged as "silent aim".
     */
    public static double snapToGcd(double delta, double gcd) {
        if (gcd <= 0.0) return delta;
        return Math.round(delta / gcd) * gcd;
    }
}
