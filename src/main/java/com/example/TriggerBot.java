package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * TriggerBot — ataque automático quando o crosshair está em cima do alvo.
 *
 * Melhorias vs versão anterior:
 *  - GrimBypassCore.canAttack() controla timing de ataque (Poisson, não clock)
 *  - Post-hit delay aleatorizado (Grim detecta back-to-back attacks < 100ms)
 *  - Criticals: verifica queda E velocidade Y < -0.1 (não só no ar)
 *  - Reach limitado pela sessão via GrimBypassCore.getReach()
 *  - Threshold adapta com combo (0.88 normal, 0.65 combo)
 */
public class TriggerBot {

    public static boolean enabled = false;

    private static final SecureRandom RNG = new SecureRandom();

    // ── Config ────────────────────────────────────────────────────────────
    private static double attackReach          = 3.10;
    private static double reachMin             = 2.95;
    private static double reachMax             = 3.15;
    private static double thresholdNormal      = 0.89;
    private static double thresholdCombo       = 0.65;
    private static boolean onlyCrit            = false;
    private static boolean losValidation       = true;
    private static boolean critSync            = true;
    private static double targetCps            = 9.0;
    private static double timingJitterMs       = 18.0;

    // ── State ─────────────────────────────────────────────────────────────
    private static int  comboTicks          = 0;
    private static int  reactionTicks       = 0;
    private static long lastAttackMs        = 0L;
    private static long sessionAttacks      = 0L;
    private static double fatigueLevel      = 0.0;
    private static final double FATIGUE_INC = 0.00006;
    private static final double FATIGUE_DEC = 0.0005;
    private static final Deque<Long> INTERVAL_LOG = new ArrayDeque<>(20);

    // ── Lifecycle ─────────────────────────────────────────────────────────
    public TriggerBot() {}
    public static void toggle() { enabled = !enabled; if (!enabled) resetState(); }
    public static void register() { ClientTickEvents.END_CLIENT_TICK.register(TriggerBot::onTick); }

    // ── Main tick ─────────────────────────────────────────────────────────
    public static void onTick(Minecraft mc) {
        if (!enabled || mc.player == null || mc.level == null) return;
        if (!mc.player.isAlive() || mc.player.isUsingItem()) return;

        fatigueLevel = Math.max(0, fatigueLevel - FATIGUE_DEC);
        if (comboTicks > 0) comboTicks--;

        LivingEntity target = crosshairTarget(mc);
        if (target == null || !holdingWeapon(mc)) { reactionTicks = 0; return; }

        float strength = mc.player.getAttackStrengthScale(0.5f);
        double threshold = (comboTicks > 0) ? thresholdCombo : thresholdNormal;
        if (strength < threshold) { reactionTicks = 0; return; }

        // Reaction delay — human-like, 1–4 ticks
        if (reactionTicks == 0) {
            reactionTicks = 1 + RNG.nextInt(3) + (RNG.nextDouble() < 0.2 ? 1 : 0);
        }
        if (--reactionTicks > 0) return;

        // GrimBypass timing gate (Poisson CPS distribution)
        if (!GrimBypassCore.canAttack(targetCps, timingJitterMs)) return;

        // Crit check
        if (critSync && !canCrit(mc)) return;
        if (onlyCrit && !canCrit(mc)) return;

        // LOS
        if (losValidation && !hasLos(mc, target)) return;

        // Stochastic miss (humanization)
        double missChance = 0.010 + fatigueLevel * 0.007;
        if (RNG.nextDouble() < missChance) { resetState(); return; }

        // Fire
        InteractionManager.simulateClickAttack(mc);
        mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);

        long now = System.currentTimeMillis();
        GrimBypassCore.onHitLanded(42, 72, 48, 72);
        if (INTERVAL_LOG.size() >= 20) INTERVAL_LOG.pollFirst();
        INTERVAL_LOG.addLast(now - lastAttackMs);
        lastAttackMs = now;
        sessionAttacks++;
        comboTicks = 4 + RNG.nextInt(3);
        fatigueLevel = Math.min(1.0, fatigueLevel + FATIGUE_INC);
    }

    // ── Crit check ────────────────────────────────────────────────────────
    /**
     * Criticals in 1.8-PvP style: player must be falling (vy < 0), not on ground,
     * not in water/lava, not riding, not sprinting (optional check — in 1.21 you
     * CAN crit while sprinting if the server allows it).
     */
    private static boolean canCrit(Minecraft mc) {
        if (mc.player == null) return false;
        if (mc.player.onGround())       return false;
        if (mc.player.isFallFlying())   return false;
        if (mc.player.isInWater())      return false;
        if (mc.player.isInLava())       return false;
        if (mc.player.onClimbable()) return false;
        if (mc.player.isPassenger())    return false;
        double vy = mc.player.getDeltaMovement().y;
        // Must be falling (vy < 0) or at peak of jump (vy ≈ 0 after rising)
        return vy < 0.0 || (vy >= -0.01 && mc.player.fallDistance > 0.0);
    }

    // ── Crosshair target ──────────────────────────────────────────────────
    private static LivingEntity crosshairTarget(Minecraft mc) {
        if (mc.hitResult == null || mc.hitResult.getType() != HitResult.Type.ENTITY) return null;
        if (!(mc.hitResult instanceof EntityHitResult ehr)) return null;
        Entity e = ehr.getEntity();
        if (!(e instanceof LivingEntity lv)) return null;
        if (!lv.isAlive()) return null;
        if (lv instanceof Player p && (p.isSpectator() || p.isCreative())) return null;
        double reachCap = GrimBypassCore.getReach((float) reachMin, (float) reachMax);
        if (mc.player.distanceTo(lv) > reachCap) return null;
        return lv;
    }

    private static boolean hasLos(Minecraft mc, Entity target) {
        Vec3 start = mc.player.getEyePosition();
        Vec3 end   = target.getEyePosition();
        BlockHitResult hit = mc.level.clip(
            new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        return hit.getType() == HitResult.Type.MISS;
    }

    private static boolean holdingWeapon(Minecraft mc) {
        if (mc.player == null) return false;
        ItemStack s = mc.player.getMainHandItem();
        if (s.isEmpty()) return false;
        String n = s.getItem().getDescriptionId().toLowerCase();
        return n.contains("sword") || n.contains("axe") || n.contains("trident")
            || n.contains("mace") || n.contains("bow") || n.contains("crossbow");
    }

    private static void resetState() { reactionTicks = 0; comboTicks = 0; }

    // ── Getters/setters ───────────────────────────────────────────────────
    public static long   getSessionAttacks()   { return sessionAttacks; }
    public static double getFatigueLevel()     { return fatigueLevel; }
    public static double getLiveCPS()          { return GrimBypassCore.getLiveCPS(); }
    public static void   setReach(double r)    { reachMin = r - 0.1; reachMax = r + 0.05; GrimBypassCore.refreshSessionCap((float)reachMin, (float)reachMax); }
    public static void   setThreshold(double n, double c) { thresholdNormal = n; thresholdCombo = c; }
    public static void   setOnlyCrit(boolean b)           { onlyCrit = b; }
    public static void   setCritSync(boolean b)           { critSync = b; }
    public static void   setLosValidation(boolean b)      { losValidation = b; }
    public static void   setTargetCps(double cps, double jitter) { targetCps = cps; timingJitterMs = jitter; }
    public static void   setAttackReach(double r) { setReach(r); }
    public static double getAttackStrengthThresholdNormal() { return thresholdNormal; }
    public static double getAttackStrengthThresholdCombo()  { return thresholdCombo; }
    public static void   setAttackStrengthThresholdNormal(double t) { thresholdNormal = t; }
    public static void   setAttackStrengthThresholdCombo(double t)  { thresholdCombo  = t; }
}
