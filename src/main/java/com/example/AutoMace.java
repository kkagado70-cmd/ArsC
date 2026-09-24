package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.InteractionHand;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

public class AutoMace {

    public static boolean enabled = false;

    private enum State { IDLE, FIND_TARGET, AIM, WAIT_FALL, ATTACK, COOLDOWN }

    private static final Random RNG = new Random();

    private static State        state        = State.IDLE;
    private static int          timer        = 0;
    private static int          aimTick      = 0;
    private static LivingEntity target       = null;
    private static int          savedSlot    = -1;
    private static double       peakY        = Double.NEGATIVE_INFINITY;
    private static boolean      hasPeaked    = false;

    private static final double MIN_FALL_DIST = 1.5;
    private static final double AIM_RANGE     = 12.0;
    private static final float  YAW_TOL       = 4.0f;
    private static final float  PITCH_TOL     = 4.5f;
    private static final int    COOLDOWN_BASE = 20;

    public static void onTick(Minecraft mc) {
        if (!enabled || mc.player == null || mc.level == null) return;
        if (mc.screen != null) { hardReset(mc); return; }

        switch (state) {
            case IDLE        -> tickIdle(mc);
            case FIND_TARGET -> tickFindTarget(mc);
            case AIM         -> tickAim(mc);
            case WAIT_FALL   -> tickWaitFall(mc);
            case ATTACK      -> tickAttack(mc);
            case COOLDOWN    -> { if (--timer <= 0) hardReset(mc); }
        }
    }

    private static void tickIdle(Minecraft mc) {
        if (!holdingMace(mc)) return;
        peakY   = mc.player.getY();
        hasPeaked = false;
        state   = State.FIND_TARGET;
    }

    private static void tickFindTarget(Minecraft mc) {
        if (!holdingMace(mc)) { hardReset(mc); return; }

        double vy = mc.player.getDeltaMovement().y;
        if (!mc.player.onGround()) {
            if (vy > 0) peakY = mc.player.getY();
            else hasPeaked = true;
        } else {
            peakY   = mc.player.getY();
            hasPeaked = false;
        }

        target = pickTarget(mc);
        if (target == null) return;

        savedSlot = mc.player.getInventory().getSelectedSlot();
        aimTick   = 0;
        state     = State.AIM;
    }

    private static void tickAim(Minecraft mc) {
        if (target == null || !target.isAlive()) { hardReset(mc); return; }
        if (!holdingMace(mc)) { hardReset(mc); return; }

        aimTick++;
        Vec3 aim = aimPoint(target);
        float ang  = angularDist(mc, aim);
        float spd  = ang > 15.0f ? 0.85f : (0.3f + 0.55f * (ang / 15.0f));

        RotationManager.smoothTo(mc, aim, spd);

        boolean aligned = RotationManager.isAligned(mc, aim, YAW_TOL, PITCH_TOL);
        if (aligned || aimTick >= 12) {
            if (!aligned) RotationManager.snapTo(mc, aim);
            aimTick = 0;
            state   = State.WAIT_FALL;
        }
    }

    private static void tickWaitFall(Minecraft mc) {
        if (target == null || !target.isAlive()) { hardReset(mc); return; }
        if (!holdingMace(mc)) { hardReset(mc); return; }

        aimTick++;
        if (aimTick % 2 == 0) {
            Vec3 aim = aimPoint(target);
            RotationManager.smoothTo(mc, aim, 0.45f);
        }

        double vy       = mc.player.getDeltaMovement().y;
        double fallDist = peakY - mc.player.getY();

        boolean falling   = !mc.player.onGround() && hasPeaked && vy < -0.05;
        boolean farEnough = fallDist >= MIN_FALL_DIST;
        boolean inRange   = mc.player.distanceTo(target) <= 4.5;

        if (falling && farEnough && inRange) {
            aimTick = 0;
            state   = State.ATTACK;
        }

        if (aimTick > 120) { hardReset(mc); }
    }

    private static void tickAttack(Minecraft mc) {
        if (target == null || !target.isAlive()) { hardReset(mc); return; }

        Vec3 aim = aimPoint(target);
        if (!RotationManager.isAligned(mc, aim, YAW_TOL + 2.0f, PITCH_TOL + 2.0f)) {
            RotationManager.snapTo(mc, aim);
        }

        float strength = mc.player.getAttackStrengthScale(0.5f);
        if (strength < 0.8f) { timer = 1; return; }

        mc.gameMode.attack(mc.player, target);
        mc.player.swing(InteractionHand.MAIN_HAND);

        timer = COOLDOWN_BASE + RNG.nextInt(8);
        state = State.COOLDOWN;
    }

    private static LivingEntity pickTarget(Minecraft mc) {
        Vec3 eyes = mc.player.getEyePosition(1.0f);
        List<Entity> list = new ArrayList<>();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == mc.player) continue;
            if (!(e instanceof LivingEntity le)) continue;
            if (!le.isAlive() || le.getHealth() <= 0) continue;
            if (le instanceof Player p && p.isCreative()) continue;
            if (mc.player.distanceTo(e) > AIM_RANGE) continue;
            list.add(e);
        }
        if (list.isEmpty()) return null;
        list.sort(Comparator.comparingDouble(e -> mc.player.distanceTo(e)));
        return (LivingEntity) list.get(0);
    }

    private static Vec3 aimPoint(LivingEntity e) {
        Vec3 pos = e.getEyePosition(1.0f);
        Vec3 vel = e.getDeltaMovement();
        return pos.add(vel.x * 1.0, 0, vel.z * 1.0);
    }

    private static float angularDist(Minecraft mc, Vec3 aim) {
        double dx = aim.x - mc.player.getX();
        double dy = aim.y - mc.player.getEyeY();
        double dz = aim.z - mc.player.getZ();
        double h  = Math.max(1e-9, Math.sqrt(dx * dx + dz * dz));
        float ty  = (float)(Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float tp  = (float)(-Math.toDegrees(Math.atan2(dy, h)));
        float dy2 = Math.abs(Mth.wrapDegrees(mc.player.getYRot() - ty));
        float dp2 = Math.abs(mc.player.getXRot() - tp);
        return (float) Math.sqrt(dy2 * dy2 + dp2 * dp2);
    }

    private static boolean holdingMace(Minecraft mc) {
        ItemStack s = mc.player.getMainHandItem();
        return !s.isEmpty() && s.getItem() == Items.MACE;
    }

    private static void hardReset(Minecraft mc) {
        if (savedSlot >= 0 && savedSlot < 9 && mc.player != null) {
            mc.player.getInventory().setSelectedSlot(savedSlot);
        }
        RotationManager.reset();
        state     = State.IDLE;
        timer     = 0;
        aimTick   = 0;
        target    = null;
        savedSlot = -1;
        peakY     = Double.NEGATIVE_INFINITY;
        hasPeaked = false;
    }
}
