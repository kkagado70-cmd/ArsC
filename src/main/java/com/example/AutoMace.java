package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class AutoMace {

    public static boolean enabled = false;

    private enum State { IDLE, SCAN, AIM, TRACK, ATTACK, COOLDOWN }

    private static final Random RNG = new Random();

    private static State        state        = State.IDLE;
    private static int          timer        = 0;
    private static int          aimTick      = 0;
    private static int          trackTick    = 0;
    private static LivingEntity target       = null;
    private static int          savedSlot    = -1;

    private static double peakY     = Double.NEGATIVE_INFINITY;
    private static boolean peaked   = false;

    private static final double SCAN_RANGE    = 14.0;
    private static final double ATTACK_RANGE  = 4.8;
    private static final double MIN_FALL      = 1.8;
    private static final float  AIM_YT        = 4.5f;
    private static final float  AIM_PT        = 5.0f;
    private static final int    AIM_MAX       = 10;
    private static final int    TRACK_MAX     = 200;
    private static final int    COOLDOWN_BASE = 22;

    public static void onTick(Minecraft mc) {
        if (!enabled || mc.player == null || mc.level == null) return;
        if (mc.screen != null) { hardReset(mc); return; }

        trackPeak(mc);

        switch (state) {
            case IDLE    -> tickIdle(mc);
            case SCAN    -> tickScan(mc);
            case AIM     -> tickAim(mc);
            case TRACK   -> tickTrack(mc);
            case ATTACK  -> tickAttack(mc);
            case COOLDOWN -> { if (--timer <= 0) hardReset(mc); }
        }
    }

    private static void trackPeak(Minecraft mc) {
        double y = mc.player.getY();
        double vy = mc.player.getDeltaMovement().y;
        if (mc.player.onGround()) { peakY = y; peaked = false; return; }
        if (vy > 0) peakY = Math.max(peakY, y);
        else peaked = true;
    }

    private static void tickIdle(Minecraft mc) {
        if (!holdingMace(mc)) return;
        peakY  = mc.player.getY();
        peaked = false;
        state  = State.SCAN;
    }

    private static void tickScan(Minecraft mc) {
        if (!holdingMace(mc)) { hardReset(mc); return; }
        target = pickTarget(mc);
        if (target == null) return;

        savedSlot = mc.player.getInventory().getSelectedSlot();
        aimTick   = 0;
        trackTick = 0;
        state     = State.AIM;
    }

    private static void tickAim(Minecraft mc) {
        if (!holdingMace(mc) || !isTargetValid(mc)) { hardReset(mc); return; }

        aimTick++;
        Vec3 aim = aimPoint(target);
        float ang = angDist(mc, aim);
        float spd = ang > 20.0f ? 0.86f : ang > 8.0f ? 0.58f : (0.22f + 0.36f * ang / 8.0f);
        if (aimTick < 4) spd *= aimTick / 4.0f;

        RotationManager.setEasingMode(RotationManager.EasingMode.KINEMATIC_SPRING);
        RotationManager.smoothTo(mc, aim, spd);

        boolean ok = RotationManager.isAligned(mc, aim, AIM_YT, AIM_PT);
        if (ok || aimTick >= AIM_MAX) {
            if (!ok) RotationManager.snapTo(mc, aim);
            aimTick  = 0;
            trackTick = 0;
            state    = State.TRACK;
        }
    }

    private static void tickTrack(Minecraft mc) {
        if (!holdingMace(mc) || !isTargetValid(mc)) { hardReset(mc); return; }

        trackTick++;
        if (trackTick > TRACK_MAX) { hardReset(mc); return; }

        if (trackTick % 2 == 0) {
            Vec3 aim = aimPoint(target);
            float ang = angDist(mc, aim);
            float spd = ang > 8.0f ? 0.52f : 0.22f;
            RotationManager.smoothTo(mc, aim, spd);
        }

        double vy       = mc.player.getDeltaMovement().y;
        double fallDist = peakY - mc.player.getY();
        boolean falling   = !mc.player.onGround() && peaked && vy < -0.05;
        boolean farEnough = fallDist >= MIN_FALL;
        boolean inRange   = mc.player.distanceTo(target) <= ATTACK_RANGE;

        if (falling && farEnough && inRange) {
            timer = 0;
            state = State.ATTACK;
        }
    }

    private static void tickAttack(Minecraft mc) {
        if (!isTargetValid(mc)) { hardReset(mc); return; }

        Vec3 aim = aimPoint(target);
        if (!RotationManager.isAligned(mc, aim, AIM_YT + 2.0f, AIM_PT + 2.0f)) {
            RotationManager.snapTo(mc, aim);
        }

        float str = mc.player.getAttackStrengthScale(0.5f);
        if (str < 0.78f && timer < 3) { timer++; return; }

        InteractionManager.simulateClickAttack(mc, InteractionManager.InteractionPriority.IMMEDIATE);
        mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);

        timer = COOLDOWN_BASE + RNG.nextInt(8);
        state = State.COOLDOWN;
    }

    private static LivingEntity pickTarget(Minecraft mc) {
        LivingEntity best = null;
        double bestDist = SCAN_RANGE;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity le)) continue;
            if (le == mc.player) continue;
            if (!le.isAlive() || le.getHealth() <= 0) continue;
            if (le instanceof Player p && p.isCreative()) continue;
            double d = mc.player.distanceTo(le);
            if (d < bestDist) { bestDist = d; best = le; }
        }
        return best;
    }

    private static Vec3 aimPoint(LivingEntity e) {
        Vec3 pos = e.getEyePosition(1.0f);
        Vec3 vel = e.getDeltaMovement();
        return pos.add(vel.x * 0.8, 0, vel.z * 0.8);
    }

    private static float angDist(Minecraft mc, Vec3 aim) {
        float ye = RotationManager.computeYawError(mc, aim);
        float pe = RotationManager.computePitchError(mc, aim);
        return (float) Math.sqrt(ye * ye + pe * pe);
    }

    private static boolean isTargetValid(Minecraft mc) {
        if (target == null || !target.isAlive() || target.getHealth() <= 0) return false;
        if (mc.player.distanceTo(target) > SCAN_RANGE) return false;
        return true;
    }

    private static boolean holdingMace(Minecraft mc) {
        return mc.player.getMainHandItem().getItem() == Items.MACE;
    }

    private static void hardReset(Minecraft mc) {
        if (mc != null && mc.player != null && savedSlot >= 0 && savedSlot < 9) {
            mc.player.getInventory().setSelectedSlot(savedSlot);
        }
        RotationManager.reset();
        state    = State.IDLE;
        timer    = 0;
        aimTick  = 0;
        trackTick = 0;
        target   = null;
        savedSlot = -1;
        peakY    = Double.NEGATIVE_INFINITY;
        peaked   = false;
    }
}
