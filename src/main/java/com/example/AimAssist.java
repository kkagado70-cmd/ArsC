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
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/**
 * AimAssist — rewrite focado em feel natural estilo Swight.
 *
 * Problemas corrigidos vs versão anterior:
 *
 *  1. MIRA TRAVADA NO PEITO AO PULAR
 *     Antes: aimY = target.getY() + height * 0.42  (posição fixa no mundo)
 *     Agora: aimY = closest hitbox Y to player eye  (sobe com você quando pula)
 *     Efeito: quando você pula, a mira naturalmente varre peito→cabeça em vez
 *             de travar no mesmo ponto do mundo.
 *
 *  2. KALMAN FILTER REMOVIDO
 *     O filtro Kalman adicionava ~3 ticks de lag (suavizava demais a posição do
 *     alvo, causando a sensação de mira "grudada"). Substituído por predição
 *     simples de velocidade + aceleração de 1 tick.
 *
 *  3. SACCADE REMOVIDO
 *     Os offsets de saccade adicionavam movimentos artificiais não-humanos.
 *     Removido completamente — o GCD noise do RotationManager já cobre isso.
 *
 *  4. SMOOTHING TUNED para Swight feel
 *     Modo padrão: EASE_OUT_EXPO para distâncias médias/longas.
 *     Close range (< 3m): SWIGHT_HIGH_SENS com factor 0.38 — rápido, limpo.
 *     Factor adapta com distância, não trava em KINEMATIC_SPRING (que causa
 *     o efeito de "mola" que sente artificial).
 *
 *  5. GRIMSBYPASS DRIFT integrado
 *     Depois do snap GCD, aplica micro-drift dentro do grid GCD.
 *     Histograma de rotações fica distribuído → passa no χ² do Grim.
 */
public class AimAssist {

    public static boolean enabled = false;

    private static final SecureRandom RNG = new SecureRandom();
    private static final UUID SESSION_ID  = UUID.randomUUID();

    // ── Target state ────────────────────────────────────────────────────────
    private static Entity lockedTarget         = null;
    private static Entity previousLockedTarget = null;
    private static int    targetLockTicks      = 0;
    private static int    switchCooldown       = 0;

    // ── Velocity prediction (1-tick, no Kalman lag) ──────────────────────
    private static Vec3 prevTargetPos = null;
    private static Vec3 prevTargetVel = Vec3.ZERO;

    // ── Config (tunable via setters) ──────────────────────────────────────
    private static float  fovDegrees         = 180.0f;
    private static double reach              = 4.2;
    private static float  smoothClose        = 0.36f;   // < 3 m
    private static float  smoothMid          = 0.50f;   // 3–6 m
    private static float  smoothFar          = 0.68f;   // > 6 m
    private static boolean pingComp         = true;
    private static boolean losCheck         = true;
    private static boolean microAdjust      = true;
    private static double microNoise        = 0.00035;
    private static double fatigueLevel      = 0.0;
    private static final double FATIGUE_INC  = 0.00008;
    private static final double FATIGUE_DEC  = 0.0006;

    // ── History for debug/registry ────────────────────────────────────────
    private static long execTicks = 0L;
    private static long hitCount  = 0L;
    private static long missCount = 0L;
    private static final Deque<Double> YAW_ERR   = new ArrayDeque<>(64);
    private static final Deque<Double> PITCH_ERR = new ArrayDeque<>(64);

    // ── Weapon tables ──────────────────────────────────────────────────────
    // smooth factor multiplier per weapon  (melee stays snappy, bows get more lead time)
    private static double resolveSmooth(Minecraft client) {
        String w = weaponKey(client);
        return switch (w) {
            case "bow","crossbow" -> 1.35;
            case "mace"           -> 0.90;
            case "axe"            -> 1.05;
            default               -> 1.00;
        };
    }

    private static double resolveReach(Minecraft client) {
        String w = weaponKey(client);
        return switch (w) {
            case "bow","crossbow" -> 64.0;
            default               -> reach;
        };
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────
    public AimAssist() {}

    public static void toggle() {
        enabled = !enabled;
        if (!enabled) reset();
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(AimAssist::onTick);
    }

    // ── Main tick ─────────────────────────────────────────────────────────
    public static void onTick(Minecraft mc) {
        if (!enabled || mc.player == null || mc.level == null) return;
        if (!mc.player.isAlive()) return;
        if (!holdingWeapon(mc)) { lockedTarget = null; return; }
        if (ShieldBreaker.isShieldStunActive()) return;

        execTicks++;
        fatigueLevel = Math.max(0, fatigueLevel - FATIGUE_DEC);

        RotationManager.samplePlayerGcd(mc);
        GrimBypassCore.tickDrift(0.06f);

        if (switchCooldown > 0) switchCooldown--;

        Entity target = pickTarget(mc);

        if (target != previousLockedTarget) {
            reset();
            previousLockedTarget = target;
            switchCooldown = 3 + RNG.nextInt(4);
        }

        if (target == null) { lockedTarget = null; return; }

        aimAt(mc, target);
        fatigueLevel = Math.min(1.0, fatigueLevel + FATIGUE_INC);
    }

    // ── Target selection ──────────────────────────────────────────────────
    private static Entity pickTarget(Minecraft mc) {
        double maxReach = resolveReach(mc);
        double maxReachSq = maxReach * maxReach;

        // Keep locked target while in cooldown
        if (lockedTarget instanceof LivingEntity lv && switchCooldown > 0) {
            if (lv.isAlive() && mc.player.distanceToSqr(lv) <= maxReachSq) {
                if (!losCheck || hasLos(mc, lv)) {
                    targetLockTicks++;
                    return lockedTarget;
                }
            }
            lockedTarget = null;
            targetLockTicks = 0;
        }

        Entity best     = null;
        double minAngle = fovDegrees / 2.0;

        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity lv)) continue;
            if (lv == mc.player || !lv.isAlive()) continue;
            if (lv instanceof Player p && (p.isSpectator() || p.isCreative())) continue;
            if (mc.player.distanceToSqr(lv) > maxReachSq) continue;
            if (losCheck && !hasLos(mc, lv)) continue;
            double angle = fovAngle(mc, closestPoint(mc, lv));
            if (angle < minAngle) { minAngle = angle; best = lv; }
        }

        if (best != null) { lockedTarget = best; targetLockTicks = 0; }
        return lockedTarget;
    }

    // ── Aim ───────────────────────────────────────────────────────────────
    private static void aimAt(Minecraft mc, Entity target) {
        Vec3 aimPoint = aimPoint(mc, target);

        // 1-tick velocity + acceleration prediction (no Kalman lag)
        Vec3 vel = target.getDeltaMovement();
        Vec3 acc = (prevTargetVel != null) ? vel.subtract(prevTargetVel) : Vec3.ZERO;
        prevTargetVel = vel;

        long latency = 50L;
        if (pingComp && mc.getConnection() != null) {
            try {
                PlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
                if (info != null) latency = info.getLatency();
            } catch (Exception ignored) {}
        }
        double pingTicks = latency / 50.0;

        Vec3 predicted = aimPoint
            .add(vel.scale(pingTicks * 0.05 + 0.018))
            .add(acc.scale(0.5 * 0.0025));

        if (microAdjust) {
            predicted = predicted.add(
                RNG.nextGaussian() * microNoise,
                RNG.nextGaussian() * microNoise * 0.55,
                0
            );
        }

        double dist   = mc.player.distanceTo(target);
        double wMul   = resolveSmooth(mc);
        float  factor = (dist < 3.0)  ? (float)(smoothClose * wMul)
                      : (dist < 6.0)  ? (float)(smoothMid   * wMul)
                      :                 (float)(smoothFar    * wMul);
        factor = (float) Math.max(0.05, factor - fatigueLevel * 0.10);

        double yawErr   = Math.abs(RotationManager.computeYawError(mc, predicted));
        double pitchErr = Math.abs(RotationManager.computePitchError(mc, predicted));

        RotationManager.EasingMode mode = (dist < 3.0)
            ? RotationManager.EasingMode.SWIGHT_HIGH_SENS
            : RotationManager.EasingMode.EASE_OUT_EXPO;
        RotationManager.setEasingMode(mode);
        RotationManager.smoothTo(mc, predicted, factor);

        // GrimBypass: micro-drift on top of GCD snap
        // (applied via RotationManager internals after applyGCDRotation)

        if (yawErr < 2.0 && pitchErr < 2.0) hitCount++;
        else { missCount++; }

        if (YAW_ERR.size() >= 64)   YAW_ERR.pollFirst();
        if (PITCH_ERR.size() >= 64) PITCH_ERR.pollFirst();
        YAW_ERR.addLast(yawErr);
        PITCH_ERR.addLast(pitchErr);
    }

    /**
     * The single most important fix:
     *
     * Returns the closest point of the target's bounding box to the player's eye.
     * This makes the aim naturally sweep through the body as the player's Y changes
     * (e.g. when jumping), instead of locking to a fixed world-Y chest position.
     *
     * When you are ABOVE the target: aimY → target head
     * When you are BELOW the target: aimY → target feet
     * When level with target:        aimY → closest body part (chest area naturally)
     */
    private static Vec3 closestPoint(Minecraft mc, Entity target) {
        Vec3 eye = mc.player.getEyePosition();

        double tMinY = target.getY();
        double tMaxY = target.getY() + target.getBbHeight();
        double tX    = target.getX();
        double tZ    = target.getZ();

        // Clamp eye Y to target hitbox Y range — closest point on the capsule
        double aimY = Mth.clamp(eye.y, tMinY + 0.05, tMaxY - 0.05);

        // Horizontal: aim at center of hitbox (X/Z target position)
        return new Vec3(tX, aimY, tZ);
    }

    /**
     * Final aim point: closest hitbox point + slight upward bias so
     * hits land on body mass, not feet (where hits often miss on uneven terrain).
     */
    private static Vec3 aimPoint(Minecraft mc, Entity target) {
        Vec3 closest = closestPoint(mc, target);

        // Bias: nudge aim point slightly toward body center vertically.
        // Amount of nudge decreases as player eye approaches target Y range edges —
        // this preserves the natural sweep feel near the extremes.
        double tMidY  = target.getY() + target.getBbHeight() * 0.5;
        double nudge  = (tMidY - closest.y) * 0.15; // 15% pull toward center
        return new Vec3(closest.x, closest.y + nudge, closest.z);
    }

    // ── Utility ───────────────────────────────────────────────────────────
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
        double dot = Mth.clamp(look.dot(dir), -1.0, 1.0);
        return Math.toDegrees(Math.acos(dot));
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
        if (n.contains("axe"))      return "axe";
        if (n.contains("mace"))     return "mace";
        if (n.contains("trident"))  return "trident";
        return "sword";
    }

    // ── Reset ─────────────────────────────────────────────────────────────
    public static void reset() {
        lockedTarget         = null;
        previousLockedTarget = null;
        targetLockTicks      = 0;
        prevTargetPos        = null;
        prevTargetVel        = Vec3.ZERO;
    }

    // ── Getters ───────────────────────────────────────────────────────────
    public static boolean isLockedOnTarget()  { return lockedTarget != null; }
    public static Entity  getLockedTarget()   { return lockedTarget; }
    public static int     getTargetLockTicks(){ return targetLockTicks; }
    public static long    getExecTicks()      { return execTicks; }
    public static double  getFatigueLevel()   { return fatigueLevel; }
    public static UUID    getSessionId()      { return SESSION_ID; }
    public static double  getAccuracy() {
        long total = hitCount + missCount;
        return total > 0 ? (double) hitCount / total : 1.0;
    }
    public static double getLastYawErr()   { return YAW_ERR.isEmpty()   ? 0 : YAW_ERR.peekLast(); }
    public static double getLastPitchErr() { return PITCH_ERR.isEmpty() ? 0 : PITCH_ERR.peekLast(); }

    // ── Setters ───────────────────────────────────────────────────────────
    public static void setFov(float f)                  { fovDegrees   = f; }
    public static void setReach(double r)               { reach        = r; }
    public static void setSmoothClose(float s)          { smoothClose  = s; }
    public static void setSmoothMid(float s)            { smoothMid    = s; }
    public static void setSmoothFar(float s)            { smoothFar    = s; }
    public static void setPingComp(boolean b)           { pingComp     = b; }
    public static void setLosCheck(boolean b)           { losCheck     = b; }
    public static void setMicroAdjust(boolean b, double m) { microAdjust = b; microNoise = m; }

    // Backwards compat with old AimAssist callers
    public static void setMaximumFov(float f)          { fovDegrees = f; }
    public static void setMaximumReach(double r)       { reach      = r; }
    public static void setKinematicSmoothing(double s) { smoothMid  = (float) s; }
    public static void setAdaptiveSmoothing(boolean b) {}
    public static void setPingCompensation(boolean b)  { pingComp   = b; }
    public static void setLosValidation(boolean b)     { losCheck   = b; }
    public static void resetFilters()                  { reset(); }
    public static double getSessionAccuracy()          { return getAccuracy(); }
    public static UUID getSubsessionIdentity()         { return SESSION_ID; }
    public static long getGlobalExecCount()            { return execTicks; }
}
