package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class AimAssist {

    public static boolean enabled = false;

    private static final Random RNG = new Random();

    private static LivingEntity target    = null;
    private static int          lockTicks = 0;
    private static int          switchCooldown = 0;

    private static final float  FOV_DEG        = 110.0f;
    private static final double RANGE          = 6.0;
    private static final float  SWITCH_COST_DEG = 40.0f;
    private static final float  SPEED_FAR      = 0.88f;
    private static final float  SPEED_MID      = 0.62f;
    private static final float  SPEED_NEAR     = 0.28f;
    private static final float  NEAR_THRESH    = 8.0f;
    private static final float  MID_THRESH     = 22.0f;
    private static final float  STOP_THRESH    = 2.8f;

    public static void onTick(Minecraft mc) {
        if (!enabled || mc.player == null || mc.level == null) return;
        if (mc.screen != null) { clearTarget(); return; }
        if (!holdingWeapon(mc)) { clearTarget(); return; }

        if (switchCooldown > 0) switchCooldown--;

        LivingEntity newTarget = resolveTarget(mc);
        if (newTarget != target) {
            if (target != null && switchCooldown > 0) {
                newTarget = target;
            } else {
                target = newTarget;
                lockTicks = 0;
                switchCooldown = 4 + RNG.nextInt(3);
            }
        }

        if (target == null) return;

        lockTicks++;
        Vec3 aim = predictAim(target, lockTicks);
        float ang = angularDist(mc, aim);

        if (ang <= STOP_THRESH) return;

        float speed;
        if (ang > MID_THRESH)  speed = SPEED_FAR;
        else if (ang > NEAR_THRESH) speed = SPEED_MID + (SPEED_FAR - SPEED_MID) * ((ang - NEAR_THRESH) / (MID_THRESH - NEAR_THRESH));
        else                   speed = SPEED_NEAR + (SPEED_MID - SPEED_NEAR) * (ang / NEAR_THRESH);

        if (lockTicks < 3) speed *= (lockTicks / 3.0f);

        RotationManager.setEasingMode(RotationManager.EasingMode.KINEMATIC_SPRING);
        RotationManager.smoothTo(mc, aim, speed);
    }

    private static LivingEntity resolveTarget(Minecraft mc) {
        Vec3 eyes = mc.player.getEyePosition(1.0f);
        Vec3 look = mc.player.getLookAngle();
        List<LivingEntity> candidates = new ArrayList<>();

        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity le)) continue;
            if (le == mc.player) continue;
            if (!le.isAlive() || le.getHealth() <= 0) continue;
            if (le instanceof Player p && p.isCreative()) continue;
            if (mc.player.distanceTo(le) > RANGE) continue;
            if (fovAngle(mc, le.getEyePosition()) > FOV_DEG / 2.0f) continue;
            candidates.add(le);
        }

        if (candidates.isEmpty()) return null;

        LivingEntity best = null;
        float bestAng = Float.MAX_VALUE;
        for (LivingEntity le : candidates) {
            float a = fovAngle(mc, le.getEyePosition());
            if (a < bestAng) { bestAng = a; best = le; }
        }

        if (target != null && target.isAlive() && candidates.contains(target)) {
            float curAng = fovAngle(mc, target.getEyePosition());
            if (curAng - bestAng < SWITCH_COST_DEG) return target;
        }

        return best;
    }

    private static Vec3 predictAim(LivingEntity e, int ticks) {
        Vec3 pos = e.getEyePosition(1.0f);
        Vec3 vel = e.getDeltaMovement();
        float lead = 1.1f + RNG.nextFloat() * 0.25f;
        double offsetY = (RNG.nextFloat() - 0.5f) * 0.08;
        return pos.add(vel.x * lead, vel.y * lead * 0.3 + offsetY, vel.z * lead);
    }

    private static float fovAngle(Minecraft mc, Vec3 target) {
        Vec3 look = mc.player.getLookAngle();
        Vec3 dir  = target.subtract(mc.player.getEyePosition(1.0f)).normalize();
        double dot = Mth.clamp(look.dot(dir), -1.0, 1.0);
        return (float) Math.toDegrees(Math.acos(dot));
    }

    private static float angularDist(Minecraft mc, Vec3 aim) {
        float yawErr   = RotationManager.computeYawError(mc, aim);
        float pitchErr = RotationManager.computePitchError(mc, aim);
        return (float) Math.sqrt(yawErr * yawErr + pitchErr * pitchErr);
    }

    private static void clearTarget() {
        target    = null;
        lockTicks = 0;
    }

    private static boolean holdingWeapon(Minecraft mc) {
        ItemStack s = mc.player.getMainHandItem();
        if (s.isEmpty()) return false;
        return s.getItem() instanceof SwordItem
            || s.getItem() instanceof AxeItem
            || s.getItem() == Items.MACE
            || s.getItem() == Items.BOW
            || s.getItem() == Items.CROSSBOW
            || s.getItem() == Items.TRIDENT;
    }
}
