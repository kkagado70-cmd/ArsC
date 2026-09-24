package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.util.Mth;

import java.util.Random;

public class TriggerBot {

    public static boolean enabled = true;

    private static final Random RNG = new Random();

    private static int  reactionTicks    = 0;
    private static int  reactionDelay    = 0;
    private static long lastAttackMs     = 0L;
    private static long lastMissMs       = 0L;
    private static boolean waitingForCrit = false;
    private static int  inAirTicks       = 0;

    private static final double STRENGTH_THRESHOLD = 0.90;
    private static final double CRIT_THRESHOLD     = 0.90;
    private static final double MISS_CHANCE        = 0.018;
    private static final int    MIN_REACT_TICKS    = 1;
    private static final int    MAX_REACT_TICKS    = 3;
    private static final long   MIN_ATTACK_MS      = 580L;

    public static void onTick(Minecraft mc) {
        if (!enabled || mc.player == null || mc.level == null) return;
        if (mc.screen != null) { reactionTicks = 0; return; }

        Entity hit = getCrosshairEntity(mc);
        if (hit == null) {
            reactionTicks = 0;
            reactionDelay = 0;
            waitingForCrit = false;
            inAirTicks = 0;
            return;
        }

        if (!(hit instanceof LivingEntity le) || !le.isAlive() || le.getHealth() <= 0) {
            reactionTicks = 0;
            return;
        }

        if (mc.player.isUsingItem()) { reactionTicks = 0; return; }

        float strength = mc.player.getAttackStrengthScale(0.5f);
        if (strength < STRENGTH_THRESHOLD) { reactionTicks = 0; return; }

        if (reactionDelay == 0) {
            reactionDelay = MIN_REACT_TICKS + RNG.nextInt(MAX_REACT_TICKS - MIN_REACT_TICKS + 1);
            if (RNG.nextDouble() < 0.15) reactionDelay += 1;
        }

        reactionTicks++;
        if (reactionTicks < reactionDelay) return;

        long now = System.currentTimeMillis();
        if (now - lastAttackMs < MIN_ATTACK_MS) return;

        if (now - lastMissMs < 120L) return;

        if (shouldWaitForCrit(mc)) return;

        if (RNG.nextDouble() < MISS_CHANCE) {
            lastMissMs = now;
            reactionTicks = 0;
            reactionDelay = 0;
            return;
        }

        mc.gameMode.attack(mc.player, hit);
        mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        lastAttackMs  = now;
        reactionTicks = 0;
        reactionDelay = 0;
        inAirTicks    = 0;
    }

    private static boolean shouldWaitForCrit(Minecraft mc) {
        if (mc.player.onGround()) { inAirTicks = 0; waitingForCrit = false; return false; }
        if (mc.player.isInWater() || mc.player.isInLava() || mc.player.isFallFlying()) return false;
        if (mc.player.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS)) return false;

        inAirTicks++;
        double vy = mc.player.getDeltaMovement().y;

        if (!waitingForCrit && !mc.player.onGround()) waitingForCrit = true;

        if (waitingForCrit && vy < -0.08) {
            waitingForCrit = false;
            return false;
        }

        return waitingForCrit;
    }

    private static Entity getCrosshairEntity(Minecraft mc) {
        if (mc.hitResult == null) return null;
        if (mc.hitResult.getType() != HitResult.Type.ENTITY) return null;
        if (!(mc.hitResult instanceof EntityHitResult ehr)) return null;
        Entity e = ehr.getEntity();
        if (e == mc.player) return null;
        if (e instanceof Player p && p.isCreative()) return null;
        return e;
    }
}
