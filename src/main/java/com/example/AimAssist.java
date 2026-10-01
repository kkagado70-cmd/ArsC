package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import java.security.SecureRandom;
import java.util.UUID;

public class AimAssist {

    public static boolean enabled = false;

    private static final SecureRandom RNG = new SecureRandom();
    private static final UUID SESSION_ID  = UUID.randomUUID();

    private static Entity lockedTarget         = null;
    private static Entity previousLockedTarget = null;
    private static int    switchCooldown       = 0;

    private static Vec3 prevTargetVel = Vec3.ZERO;

    // Config
    private static float  fovDegrees  = 180.0f;
    private static double reach       = 4.2;
    private static boolean pingComp   = true;
    private static boolean losCheck   = true;

    // Smooth factors — lower = smoother, higher = snappier
    // 0.18 close / 0.28 mid / 0.42 far gives natural Swight feel
    // (old code had 0.36/0.50/0.68 — too snappy, caused stiff feeling)
    private static float smoothClose = 0.18f;
    private static float smoothMid   = 0.28f;
    private static float smoothFar   = 0.42f;

    private static long execTicks = 0L;

    public AimAssist() {}

    public static void toggle() {
        enabled = !enabled;
        if (!enabled) reset();
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(AimAssist::onTick);
    }

    public static void onTick(Minecraft mc) {
        if (!enabled || mc.player == null || mc.level == null) return;
        if (!mc.player.isAlive()) { reset(); return; }
        if (!holdingWeapon(mc)) {
            if (lockedTarget != null) { lockedTarget = null; RotationManager.reset(); }
            return;
        }
        if (ShieldBreaker.isShieldStunActive()) return;

        execTicks++;
        RotationManager.samplePlayerGcd(mc);
        GrimBypassCore.tickDrift(0.06f);

        if (switchCooldown > 0) switchCooldown--;

        Entity target = pickTarget(mc);

        if (target == null) {
            // Target gone — stop rotating immediately
            if (lockedTarget != null) {
                lockedTarget = null;
                RotationManager.reset();
            }
            return;
        }

        if (target != previousLockedTarget) {
            prevTargetVel = Vec3.ZERO;
            previousLockedTarget = target;
            switchCooldown = 2 + RNG.nextInt(3);
        }

        lockedTarget = target;
        aimAt(mc, target);
    }

    private static Entity pickTarget(Minecraft mc) {
        double maxReachSq = reach * reach;
        if (mc.player == null) return null;

        // Re-validate current locked target first
        if (lockedTarget instanceof LivingEntity lv) {
            boolean dead    = !lv.isAlive() || lv.isRemoved() || lv.getHealth() <= 0;
            boolean tooFar  = mc.player.distanceToSqr(lv) > maxReachSq;
            boolean blocked = losCheck && !hasLos(mc, lv);
            if (dead || tooFar || blocked) {
                lockedTarget = null;
            }
        }

        // If still valid, keep it (don't jitter between targets)
        if (lockedTarget instanceof LivingEntity lv && switchCooldown > 0) {
            return lv;
        }

        Entity best     = null;
        double minAngle = fovDegrees / 2.0;

        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity lv)) continue;
            if (lv == mc.player || !lv.isAlive() || lv.isRemoved()) continue;
            if (lv.getHealth() <= 0) continue;
            if (lv instanceof Player p && (p.isSpectator() || p.isCreative())) continue;
            if (mc.player.distanceToSqr(lv) > maxReachSq) continue;
            if (losCheck && !hasLos(mc, lv)) continue;
            double angle = fovAngle(mc, aimPoint(mc, lv));
            if (angle < minAngle) { minAngle = angle; best = lv; }
        }

        return best;
    }

    private static void aimAt(Minecraft mc, Entity target) {
        Vec3 point = aimPoint(mc, target);

        // Ping compensation — lightweight, single-tick velocity
        Vec3 vel = target.getDeltaMovement();
        Vec3 acc = vel.subtract(prevTargetVel);
        prevTargetVel = vel;

        long latency = 50L;
        if (pingComp && mc.getConnection() != null) {
            try {
                PlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
                if (info != null) latency = Math.min(info.getLatency(), 250);
            } catch (Exception ignored) {}
        }
        double pingFrac = (latency / 50.0) * 0.022;
        Vec3 predicted = point.add(vel.scale(pingFrac)).add(acc.scale(0.5 * 0.001));

        double dist = mc.player.distanceTo(target);

        // Weapon-based speed multiplier
        double wMul = switch (weaponKey(mc)) {
            case "bow", "crossbow" -> 1.3;
            case "mace"            -> 0.95;
            default                -> 1.0;
        };

        // Smooth factor: lower = more human, higher = snappier
        // Settling: when very close to target (< 1.5°), reduce factor sharply
        // so the aim "settles in" instead of oscillating
        double aimErr = Math.hypot(
            RotationManager.computeYawError(mc, predicted),
            RotationManager.computePitchError(mc, predicted)
        );

        float base = (dist < 3.0) ? smoothClose
                   : (dist < 6.0) ? smoothMid
                   :                smoothFar;

        // Settling: if almost on target, use tiny factor to avoid vibration
        float factor = (aimErr < 1.5) ? (float)(base * 0.3 * wMul)
                     : (aimErr < 5.0) ? (float)(base * 0.7 * wMul)
                     :                  (float)(base * wMul);

        // Always use EASE_OUT_EXPO — it feels natural and never overshoots
        RotationManager.setEasingMode(RotationManager.EasingMode.EASE_OUT_EXPO);
        RotationManager.smoothTo(mc, predicted, factor);
    }

    /**
     * Closest hitbox point to player eye Y.
     * When you jump, aimY rises with you toward the target's head.
     * When you're below, aimY goes to target's feet.
     * Creates the natural body sweep instead of locking to fixed chest position.
     */
    private static Vec3 aimPoint(Minecraft mc, Entity target) {
        Vec3 eye     = mc.player.getEyePosition();
        double minY  = target.getY() + 0.1;
        double maxY  = target.getY() + target.getBbHeight() - 0.1;
        double aimY  = Mth.clamp(eye.y, minY, maxY);
        // Soft 15% pull toward body center so hits land on torso, not edges
        double midY  = target.getY() + target.getBbHeight() * 0.5;
        aimY += (midY - aimY) * 0.15;
        return new Vec3(target.getX(), aimY, target.getZ());
    }

    private static boolean hasLos(Minecraft mc, Entity target) {
        Vec3 start = mc.player.getEyePosition();
        Vec3 end   = target.getEyePosition();
        BlockHitResult hit = mc.level.clip(
            new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        return hit.getType() == HitResult.Type.MISS;
    }

    private static double fovAngle(Minecraft mc, Vec3 point) {
        Vec3 look = mc.player.getLookAngle();
        Vec3 dir  = point.subtract(mc.player.getEyePosition()).normalize();
        return Math.toDegrees(Math.acos(Mth.clamp(look.dot(dir), -1.0, 1.0)));
    }

    private static boolean holdingWeapon(Minecraft mc) {
        if (mc.player == null) return false;
        ItemStack s = mc.player.getMainHandItem();
        if (s.isEmpty()) return false;
        String n = s.getItem().getDescriptionId().toLowerCase();
        return n.contains("sword") || n.contains("axe") || n.contains("trident")
            || n.contains("mace") || n.contains("bow") || n.contains("crossbow");
    }

    private static String weaponKey(Minecraft mc) {
        if (mc.player == null) return "sword";
        String n = mc.player.getMainHandItem().getItem().getDescriptionId().toLowerCase();
        if (n.contains("crossbow")) return "crossbow";
        if (n.contains("bow"))      return "bow";
        if (n.contains("mace"))     return "mace";
        if (n.contains("axe"))      return "axe";
        return "sword";
    }

    public static void reset() {
        lockedTarget         = null;
        previousLockedTarget = null;
        switchCooldown       = 0;
        prevTargetVel        = Vec3.ZERO;
        RotationManager.reset();
    }

    // Getters / setters / back-compat
    public static boolean isLockedOnTarget()  { return lockedTarget != null; }
    public static Entity  getLockedTarget()   { return lockedTarget; }
    public static long    getExecTicks()      { return execTicks; }
    public static UUID    getSessionId()      { return SESSION_ID; }
    public static UUID    getSubsessionIdentity() { return SESSION_ID; }
    public static long    getGlobalExecCount() { return execTicks; }
    public static double  getSessionAccuracy() { return 1.0; }

    public static void setFov(float f)             { fovDegrees = f; }
    public static void setMaximumFov(float f)      { fovDegrees = f; }
    public static void setReach(double r)          { reach = r; }
    public static void setMaximumReach(double r)   { reach = r; }
    public static void setPingComp(boolean b)      { pingComp = b; }
    public static void setPingCompensation(boolean b) { pingComp = b; }
    public static void setLosCheck(boolean b)      { losCheck = b; }
    public static void setLosValidation(boolean b) { losCheck = b; }
    public static void setSmoothClose(float s)     { smoothClose = s; }
    public static void setSmoothMid(float s)       { smoothMid = s; }
    public static void setSmoothFar(float s)       { smoothFar = s; }
    public static void setKinematicSmoothing(double s) { smoothMid = (float) s; }
    public static void setAdaptiveSmoothing(boolean b) {}
    public static void setMicroAdjust(boolean b, double m) {}
    public static void resetFilters() { reset(); }
    public static int  getTargetLockTicks() { return 0; }
    public static double getFatigueLevel()  { return 0.0; }
    public static double getLastYawErr()    { return 0.0; }
    public static double getLastPitchErr()  { return 0.0; }
    public static double getAccuracy()      { return 1.0; }
}
