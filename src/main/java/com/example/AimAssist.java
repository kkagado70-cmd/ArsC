package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

public class AimAssist {

    public static boolean enabled = false;

    private static final Random RNG = new Random();

    private static Entity target    = null;
    private static int    lockTicks = 0;

    private static final float FOV         = 120.0f;
    private static final double REACH      = 6.5;
    private static final float  SWITCH_DEG = 45.0f;

    private static final float SPEED_BASE  = 0.68f;
    private static final float SPEED_FAR   = 0.88f;
    private static final float NEAR_DEG    = 12.0f;
    private static final float NEAR_SCALE  = 0.32f;

    public static void onTick(Minecraft mc) {
        if (!enabled || mc.player == null || mc.level == null) return;
        if (mc.screen != null) { target = null; return; }
        if (!holdingWeapon(mc)) { target = null; return; }

        target = pickTarget(mc);
        if (target == null) return;

        Vec3 aim = aimPoint(target);
        float ang = angularDist(mc, aim);

        float speed;
        if (ang > NEAR_DEG) {
            float t = Math.min(1.0f, (ang - NEAR_DEG) / 60.0f);
            speed = SPEED_BASE + (SPEED_FAR - SPEED_BASE) * t;
        } else {
            speed = SPEED_BASE * (NEAR_SCALE + (1.0f - NEAR_SCALE) * (ang / NEAR_DEG));
        }

        RotationManager.smoothTo(mc, aim, speed);
        lockTicks++;
    }

    private static Entity pickTarget(Minecraft mc) {
        Vec3 eyes = mc.player.getEyePosition(1.0f);
        Vec3 look = mc.player.getLookAngle();

        List<Entity> candidates = new ArrayList<>();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == mc.player) continue;
            if (!(e instanceof LivingEntity le)) continue;
            if (!le.isAlive() || le.getHealth() <= 0) continue;
            if (le instanceof Player p && p.isCreative()) continue;
            if (mc.player.distanceTo(e) > REACH) continue;
            if (angleTo(mc, e.getEyePosition()) > FOV / 2.0f) continue;
            candidates.add(e);
        }

        if (candidates.isEmpty()) { lockTicks = 0; return null; }

        candidates.sort(Comparator.comparingDouble(e -> angleTo(mc, e.getEyePosition())));
        Entity best = candidates.get(0);

        if (target != null && target.isAlive() && candidates.contains(target)) {
            float switchCost = angleTo(mc, target.getEyePosition());
            float bestCost   = angleTo(mc, best.getEyePosition());
            if (switchCost - bestCost < SWITCH_DEG) return target;
        }

        if (best != target) lockTicks = 0;
        return best;
    }

    private static Vec3 aimPoint(Entity e) {
        Vec3 pos = e.getEyePosition(1.0f);
        Vec3 vel = e.getDeltaMovement();
        float ticksAhead = 1.2f + RNG.nextFloat() * 0.3f;
        return pos.add(vel.x * ticksAhead, vel.y * ticksAhead * 0.4, vel.z * ticksAhead);
    }

    private static float angleTo(Minecraft mc, Vec3 target) {
        Vec3 eyes = mc.player.getEyePosition(1.0f);
        Vec3 look = mc.player.getLookAngle();
        Vec3 dir  = target.subtract(eyes).normalize();
        double dot = Mth.clamp(look.dot(dir), -1.0, 1.0);
        return (float) Math.toDegrees(Math.acos(dot));
    }

    private static float angularDist(Minecraft mc, Vec3 aim) {
        Vec3 eyes = mc.player.getEyePosition(1.0f);
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

    private static boolean holdingWeapon(Minecraft mc) {
        ItemStack s = mc.player.getMainHandItem();
        if (s.isEmpty()) return false;
        String id = s.getItem().toString().toLowerCase();
        return id.contains("sword") || id.contains("axe") || id.contains("mace")
            || id.contains("trident") || s.getItem() == Items.BOW || s.getItem() == Items.CROSSBOW;
    }
}
