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

public class TriggerBot {

    public static boolean enabled = false;

    private static final SecureRandom RNG = new SecureRandom();

    private static double reachMin        = 2.85;
    private static double reachMax        = 3.00;
    private static double thresholdNormal = 0.89;
    private static double thresholdCombo  = 0.65;
    private static boolean onlyCrit       = false;
    private static boolean losValidation  = true;
    private static boolean critSync       = true;
    private static double targetCps       = 9.0;
    private static double timingJitterMs  = 18.0;

    private static int  comboTicks     = 0;
    private static long lastAttackMs   = 0L;
    private static long sessionAttacks = 0L;
    private static double fatigueLevel = 0.0;
    private static final double FATIGUE_INC = 0.00006;
    private static final double FATIGUE_DEC = 0.0005;
    private static final Deque<Long> INTERVAL_LOG = new ArrayDeque<>(20);

    public TriggerBot() {}
    public static void toggle() { enabled = !enabled; if (!enabled) resetState(); }
    public static void register() { ClientTickEvents.END_CLIENT_TICK.register(TriggerBot::onTick); }

    public static void onTick(Minecraft mc) {
        if (!enabled || mc.player == null || mc.level == null) return;
        if (!mc.player.isAlive() || mc.player.isUsingItem()) return;

        fatigueLevel = Math.max(0, fatigueLevel - FATIGUE_DEC);
        if (comboTicks > 0) comboTicks--;

        LivingEntity target = crosshairTarget(mc);
        if (target == null || !holdingWeapon(mc)) return;

        float strength = mc.player.getAttackStrengthScale(0.5f);
        double threshold = (comboTicks > 0) ? thresholdCombo : thresholdNormal;
        if (strength < threshold) return;

        if (!GrimBypassCore.canAttack(targetCps, timingJitterMs)) return;

        if (critSync && !canCrit(mc)) return;
        if (onlyCrit && !canCrit(mc)) return;

        if (losValidation && !hasLos(mc, target)) return;

        double missChance = 0.010 + fatigueLevel * 0.007;
        if (RNG.nextDouble() < missChance) { resetState(); return; }

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

    private static boolean canCrit(Minecraft mc) {
        if (mc.player == null) return false;
        if (mc.player.onGround())     return false;
        if (mc.player.isFallFlying()) return false;
        if (mc.player.isInWater())    return false;
        if (mc.player.isInLava())     return false;
        if (mc.player.isOnLadder())   return false;
        if (mc.player.isPassenger())  return false;
        double vy = mc.player.getDeltaMovement().y;
        return vy < 0.0 && mc.player.fallDistance > 0.0;
    }

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

    private static void resetState() { comboTicks = 0; }

    public static long   getSessionAttacks()   { return sessionAttacks; }
    public static double getFatigueLevel()     { return fatigueLevel; }
    public static double getLiveCPS()          { return GrimBypassCore.getLiveCPS(); }
    public static void   setReach(double r)    { reachMin = r - 0.1; reachMax = Math.min(r + 0.05, 3.0); GrimBypassCore.refreshSessionCap((float)reachMin, (float)reachMax); }
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
