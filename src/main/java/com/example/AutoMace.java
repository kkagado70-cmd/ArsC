package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import java.security.SecureRandom;

/**
 * AutoMace — dive bomb assistant.
 *
 * Fixes vs versão anterior:
 *  - minFallDistance baixado de 1.0 → 0.25 (normal jumps agora registram)
 *  - KINEMATIC_SPRING removido → só EASE_OUT_EXPO (não oscila)
 *  - hyperSnapSpeed de 0.99 → 0.55 (era snap instantâneo, agora suave)
 *  - Slot restore na hora certa (agora dentro do smash, não num tick extra)
 *  - Wind charge / elytra check mantidos
 */
public class AutoMace {

    public static boolean enabled = false;

    private static final SecureRandom RNG = new SecureRandom();

    // ── Config ────────────────────────────────────────────────────────────
    private static double maxSwingRange   = 6.5;
    private static double maxAimDistance  = 24.0;
    private static double minFallDistance = 0.25;   // ← era 1.0, muito alto
    private static float  aimSpeed        = 0.55f;  // ← era 0.99 (snap)

    // ── State ─────────────────────────────────────────────────────────────
    private static LivingEntity lockedMaceTarget = null;
    private static int  smashCooldown     = 0;
    private static int  savedSlot         = -1;
    private static double peakY           = Double.NEGATIVE_INFINITY;
    private static boolean hasPeaked      = false;

    // ── Lifecycle ─────────────────────────────────────────────────────────
    public AutoMace() {}

    public static void toggle() {
        enabled = !enabled;
        if (!enabled) reset();
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(AutoMace::onTick);
    }

    // ── Main tick ─────────────────────────────────────────────────────────
    public static void onTick(Minecraft mc) {
        if (!enabled || mc.player == null || mc.level == null) return;
        if (!mc.player.isAlive()) { reset(); return; }

        if (smashCooldown > 0) { smashCooldown--; return; }

        RotationManager.samplePlayerGcd(mc);

        double vY   = mc.player.getDeltaMovement().y;
        double fall = mc.player.fallDistance;
        boolean elytra = mc.player.isFallFlying();
        boolean wind   = vY > 0.75;

        // Track peak Y for fall-distance calc
        trackPeak(mc);

        boolean peaked  = hasPeaked || (peakY > Double.NEGATIVE_INFINITY
                          && (peakY - mc.player.getY()) >= minFallDistance);
        boolean falling = !mc.player.onGround()
                       && (vY < -0.05 || fall >= minFallDistance || elytra || wind);
        boolean diving  = falling && (peaked || fall >= minFallDistance || elytra || wind);

        if (!diving) {
            // Not in a dive — release slot if held
            if (savedSlot >= 0) {
                mc.player.getInventory().setSelectedSlot(savedSlot);
                savedSlot = -1;
            }
            lockedMaceTarget = null;
            return;
        }

        // Pick nearest player target
        lockedMaceTarget = null;
        double minDist = maxAimDistance * maxAimDistance;
        for (Player p : mc.level.players()) {
            if (p == mc.player || !p.isAlive() || p.isRemoved()) continue;
            if (p.isSpectator() || p.isCreative()) continue;
            double d = mc.player.distanceToSqr(p);
            if (d > minDist) continue;
            if (!hasLos(mc, p)) continue;
            minDist = d;
            lockedMaceTarget = p;
        }

        if (lockedMaceTarget == null) return;

        // Find mace in hotbar
        int maceSlot = -1;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).is(Items.MACE)) { maceSlot = i; break; }
        }
        if (maceSlot < 0) return;

        // Swap to mace
        int currentSlot = mc.player.getInventory().getSelectedSlot();
        if (currentSlot != maceSlot) {
            if (savedSlot < 0) savedSlot = currentSlot;
            mc.player.getInventory().setSelectedSlot(maceSlot);
            return; // wait one tick for server-side slot update
        }

        // Aim at target — closest hitbox point, EASE_OUT_EXPO only
        Vec3 eye   = mc.player.getEyePosition();
        double tMinY = lockedMaceTarget.getY() + 0.1;
        double tMaxY = lockedMaceTarget.getY() + lockedMaceTarget.getBbHeight() - 0.1;
        double aimY  = Math.max(tMinY, Math.min(eye.y, tMaxY));
        Vec3 aimPt   = new Vec3(lockedMaceTarget.getX(), aimY, lockedMaceTarget.getZ());

        double aimErr = Math.hypot(
            RotationManager.computeYawError(mc, aimPt),
            RotationManager.computePitchError(mc, aimPt)
        );

        // Speed scales with error — fast when far off, slows as it converges
        float spd = (aimErr > 25) ? 0.75f
                  : (aimErr > 10) ? 0.55f
                  : (aimErr > 3)  ? 0.35f
                  :                  0.15f; // settling
        RotationManager.setEasingMode(RotationManager.EasingMode.EASE_OUT_EXPO);
        RotationManager.smoothTo(mc, aimPt, spd);

        // Attack when in range and aimed
        double dist = mc.player.distanceTo(lockedMaceTarget);
        if (dist <= maxSwingRange && mc.player.getAttackStrengthScale(0f) >= 0.5f) {
            InteractionManager.simulateClickAttack(mc);
            GrimBypassCore.onHitLanded(38, 65, 48, 75);
            smashCooldown = 3 + RNG.nextInt(3);

            // Restore slot immediately after smash
            if (savedSlot >= 0) {
                mc.player.getInventory().setSelectedSlot(savedSlot);
                savedSlot = -1;
            }
            lockedMaceTarget = null;
            hasPeaked = false;
            peakY = Double.NEGATIVE_INFINITY;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────
    private static void trackPeak(Minecraft mc) {
        double y  = mc.player.getY();
        double vy = mc.player.getDeltaMovement().y;
        if (mc.player.onGround()) { peakY = y; hasPeaked = false; return; }
        if (vy > 0) peakY = (peakY == Double.NEGATIVE_INFINITY) ? y : Math.max(peakY, y);
        else         hasPeaked = true;
    }

    private static boolean hasLos(Minecraft mc, LivingEntity target) {
        Vec3 start = mc.player.getEyePosition();
        Vec3 end   = target.getEyePosition();
        BlockHitResult hit = mc.level.clip(
            new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        return hit.getType() == HitResult.Type.MISS;
    }

    private static void reset() {
        lockedMaceTarget = null;
        smashCooldown    = 0;
        hasPeaked        = false;
        peakY            = Double.NEGATIVE_INFINITY;
    }

    // ── Getters/setters ───────────────────────────────────────────────────
    public static LivingEntity getLockedTarget()       { return lockedMaceTarget; }
    public static void setMaxSwingRange(double r)      { maxSwingRange = r; }
    public static void setMaxAimDistance(double d)     { maxAimDistance = d; }
    public static void setMinFallDistance(double d)    { minFallDistance = d; }
    public static void setHyperSnapSpeed(float s)      { aimSpeed = s; }
    public static float getHyperSnapSpeed()            { return aimSpeed; }
    public static double getFatigueLevel()             { return 0.0; }
    public static boolean verifyMaceSubsystemHealth()  { return enabled; }
}
