package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.Random;

public class TriggerBot {

    public static boolean enabled = true;

    private static final Random RNG = new Random();

    private static int  reactDelay      = 0;
    private static int  reactTick       = 0;
    private static boolean waitingCrit  = false;
    private static int  inAirTicks      = 0;
    private static int  postHitCooldown = 0;
    private static long lastAttackMs    = 0L;
    private static long missUntilMs     = 0L;

    private static final float  STRENGTH_MIN   = 0.90f;
    private static final int    REACT_MIN      = 1;
    private static final int    REACT_MAX      = 3;
    private static final long   ATTACK_GAP_MS  = 575L;
    private static final double MISS_PROB      = 0.018;
    private static final int    POST_HIT_COOL  = 2;

    public static void onTick(Minecraft mc) {
        if (!enabled || mc.player == null || mc.level == null) return;
        if (mc.screen != null) { reset(); return; }

        if (postHitCooldown > 0) { postHitCooldown--; return; }

        LivingEntity target = getCrosshairTarget(mc);
        if (target == null) { reset(); return; }

        if (mc.player.isUsingItem()) { reset(); return; }
        if (!holdingWeapon(mc)) { reset(); return; }

        float strength = mc.player.getAttackStrengthScale(0.5f);
        if (strength < STRENGTH_MIN) {
            reactTick = 0;
            return;
        }

        if (reactDelay == 0) {
            reactDelay = REACT_MIN + RNG.nextInt(REACT_MAX - REACT_MIN + 1);
            if (RNG.nextFloat() < 0.12f) reactDelay++;
        }

        reactTick++;
        if (reactTick < reactDelay) return;

        long now = System.currentTimeMillis();
        if (now < missUntilMs) return;
        if (now - lastAttackMs < ATTACK_GAP_MS) return;

        if (shouldWaitCrit(mc)) return;

        if (RNG.nextDouble() < MISS_PROB) {
            missUntilMs = now + 100L + RNG.nextInt(60);
            reactTick   = 0;
            reactDelay  = 0;
            return;
        }

        InteractionManager.simulateClickAttack(mc, InteractionManager.InteractionPriority.HIGH);
        mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        lastAttackMs    = now;
        reactTick       = 0;
        reactDelay      = 0;
        inAirTicks      = 0;
        waitingCrit     = false;
        postHitCooldown = POST_HIT_COOL;
    }

    private static boolean shouldWaitCrit(Minecraft mc) {
        if (mc.player.onGround()) { inAirTicks = 0; waitingCrit = false; return false; }
        if (mc.player.isInWater() || mc.player.isInLava() || mc.player.isFallFlying()) { waitingCrit = false; return false; }
        if (mc.player.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS)) { waitingCrit = false; return false; }

        double vy = mc.player.getDeltaMovement().y;
        inAirTicks++;
        if (!waitingCrit) waitingCrit = true;
        if (waitingCrit && vy < -0.08 && inAirTicks >= 2) { waitingCrit = false; return false; }
        return waitingCrit;
    }

    private static LivingEntity getCrosshairTarget(Minecraft mc) {
        if (mc.hitResult == null || mc.hitResult.getType() != HitResult.Type.ENTITY) return null;
        if (!(mc.hitResult instanceof EntityHitResult ehr)) return null;
        Entity e = ehr.getEntity();
        if (!(e instanceof LivingEntity le)) return null;
        if (le == mc.player) return null;
        if (le instanceof Player p && p.isCreative()) return null;
        if (!le.isAlive() || le.getHealth() <= 0) return null;
        return le;
    }

    private static boolean holdingWeapon(Minecraft mc) {
        ItemStack s = mc.player.getMainHandItem();
        if (s.isEmpty()) return false;
        return s.getItem() instanceof SwordItem
            || s.getItem() instanceof AxeItem
            || s.getItem() == Items.MACE
            || s.getItem() == Items.TRIDENT;
    }

    private static void reset() {
        reactTick   = 0;
        reactDelay  = 0;
        waitingCrit = false;
        inAirTicks  = 0;
    }
}
