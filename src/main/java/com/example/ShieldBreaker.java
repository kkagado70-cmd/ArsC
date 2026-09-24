package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.util.Mth;

import java.util.Random;

public class ShieldBreaker {

    public static boolean enabled = true;

    private enum State { IDLE, REACT, SWAP, ATTACK, COOLDOWN }

    private static final Random RNG = new Random();

    private static State state          = State.IDLE;
    private static int   timer          = 0;
    private static int   savedSlot      = -1;
    private static LivingEntity target  = null;

    private static final int    MIN_REACT  = 1;
    private static final int    MAX_REACT  = 3;
    private static final int    COOLDOWN_T = 12;
    private static final double REACH      = 4.5;
    private static final double MISS_CHANCE = 0.01;

    public static void onTick(Minecraft mc) {
        if (!enabled || mc.player == null || mc.level == null) return;
        if (mc.screen != null) { hardReset(mc); return; }

        switch (state) {
            case IDLE    -> tickIdle(mc);
            case REACT   -> tickReact(mc);
            case SWAP    -> tickSwap(mc);
            case ATTACK  -> tickAttack(mc);
            case COOLDOWN -> { if (--timer <= 0) hardReset(mc); }
        }
    }

    private static void tickIdle(Minecraft mc) {
        LivingEntity t = getCrosshairTarget(mc);
        if (t == null || !t.isBlocking()) return;
        if (mc.player.distanceTo(t) > REACH) return;

        target    = t;
        savedSlot = mc.player.getInventory().getSelectedSlot();
        timer     = MIN_REACT + RNG.nextInt(MAX_REACT - MIN_REACT + 1);
        state     = State.REACT;
    }

    private static void tickReact(Minecraft mc) {
        if (target == null || !target.isAlive() || !target.isBlocking()) { hardReset(mc); return; }
        if (mc.player.distanceTo(target) > REACH) { hardReset(mc); return; }
        if (--timer > 0) return;

        int axeSlot = findAxe(mc);
        if (axeSlot < 0) { hardReset(mc); return; }

        if (mc.player.getInventory().getSelectedSlot() != axeSlot) {
            savedSlot = mc.player.getInventory().getSelectedSlot();
            mc.player.getInventory().setSelectedSlot(axeSlot);
            timer = 1;
            state = State.SWAP;
        } else {
            timer = 1;
            state = State.ATTACK;
        }
    }

    private static void tickSwap(Minecraft mc) {
        if (--timer > 0) return;
        timer = 1;
        state = State.ATTACK;
    }

    private static void tickAttack(Minecraft mc) {
        if (--timer > 0) return;
        if (target == null || !target.isAlive()) { restoreAndCooldown(mc); return; }

        if (RNG.nextDouble() < MISS_CHANCE) { restoreAndCooldown(mc); return; }

        float strength = mc.player.getAttackStrengthScale(0.5f);
        if (strength < 0.85f) { timer = 1; return; }

        mc.gameMode.attack(mc.player, target);
        mc.player.swing(InteractionHand.MAIN_HAND);
        restoreAndCooldown(mc);
    }

    private static void restoreAndCooldown(Minecraft mc) {
        if (savedSlot >= 0 && savedSlot < 9) mc.player.getInventory().setSelectedSlot(savedSlot);
        savedSlot = -1;
        timer     = COOLDOWN_T + RNG.nextInt(5);
        state     = State.COOLDOWN;
    }

    private static void hardReset(Minecraft mc) {
        if (savedSlot >= 0 && savedSlot < 9 && mc.player != null) {
            mc.player.getInventory().setSelectedSlot(savedSlot);
        }
        state  = State.IDLE;
        timer  = 0;
        target = null;
        savedSlot = -1;
    }

    private static int findAxe(Minecraft mc) {
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.player.getInventory().getItem(i);
            if (!s.isEmpty() && s.getItem() instanceof AxeItem) return i;
        }
        return -1;
    }

    private static LivingEntity getCrosshairTarget(Minecraft mc) {
        if (mc.hitResult == null || mc.hitResult.getType() != HitResult.Type.ENTITY) return null;
        if (!(mc.hitResult instanceof EntityHitResult ehr)) return null;
        if (!(ehr.getEntity() instanceof LivingEntity le)) return null;
        if (le == mc.player || (le instanceof Player p && p.isCreative())) return null;
        return le;
    }
}
