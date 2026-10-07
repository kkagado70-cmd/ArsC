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

public class AimAssist {

    public static boolean enabled = false;

    private static final SecureRandom RNG = new SecureRandom();
    private static final UUID SESSION_ID  = UUID.randomUUID();

    // ── Target ────────────────────────────────────────────────────────────
    private static LivingEntity lockedTarget  = null;
    private static int          lockTicks     = 0;
    private static int          switchCD      = 0;

    // ── Prediction (1-tick, sem lag de Kalman) ────────────────────────────
    private static Vec3 prevVel = Vec3.ZERO;

    // ── Config ────────────────────────────────────────────────────────────
    // aimRange: distância de DETECÇÃO (início do aimbot)
    // reach:    distância de ATAQUE (limite real do servidor)
    private static float   fovDeg      = 120.0f;  // B13: >120 flags AimDuplicateLook
    private static double  aimRange    = 6.0;   // detecta alvo com antecedência
    private static double  reach       = 3.0;   // reach de ataque vanilla (Grim: 3.0)
    private static float   smClose     = 0.32f; // dist < 3 m
    private static float   smMid       = 0.48f; // 3–6 m
    private static float   smFar       = 0.65f; // > 6 m
    private static boolean pingComp    = true;
    private static boolean losCheck    = true;
    private static boolean microAdjust = true;
    private static double  microNoise  = 0.00030;
    private static double  fatigue     = 0.0;
    private static final double F_INC  = 0.00008;
    private static final double F_DEC  = 0.0006;

    // ── Session ───────────────────────────────────────────────────────────
    private static long   execTicks = 0L;
    private static long   hits      = 0L;
    private static long   misses    = 0L;
    private static final Deque<Double> YAW_ERR   = new ArrayDeque<>(64);
    private static final Deque<Double> PITCH_ERR = new ArrayDeque<>(64);

    // ── Lifecycle ─────────────────────────────────────────────────────────
    public AimAssist() {}
    public static void toggle()    { enabled = !enabled; if (!enabled) reset(); }
    public static void register()  { ClientTickEvents.END_CLIENT_TICK.register(AimAssist::onTick); }

    // ── Tick principal ────────────────────────────────────────────────────
    public static void onTick(Minecraft mc) {
        if (!enabled || mc.player == null || mc.level == null) return;
        if (!mc.player.isAlive()) return;
        if (!holdingWeapon(mc)) { release(); return; }
        // B1: AutoMace owns rotation while diving — yield to avoid conflict
        if (AutoMace.isTracking()) return;
        if (ShieldBreaker.isShieldStunActive()) return;

        execTicks++;
        fatigue = Math.max(0, fatigue - F_DEC);
        RotationManager.samplePlayerGcd(mc);
        GrimBypassCore.tickDrift(0.06f);
        if (switchCD > 0) switchCD--;

        LivingEntity target = pickTarget(mc);

        // Alvo morreu ou saiu: limpa imediatamente
        if (target != lockedTarget) {
            reset();
            lockedTarget = target;
            switchCD = 3 + RNG.nextInt(4);
        }

        if (target == null) return;
        // Garante que o alvo ainda respira antes de mirar
        if (!target.isAlive()) { release(); return; }

        aimAt(mc, target);
        fatigue = Math.min(1.0, fatigue + F_INC);
    }

    // ── Seleção de alvo ───────────────────────────────────────────────────
    private static LivingEntity pickTarget(Minecraft mc) {
        double rangeSq = aimRange * aimRange;

        // Mantém alvo locked enquanto ainda válido
        if (lockedTarget != null && switchCD > 0) {
            if (lockedTarget.isAlive() && mc.player.distanceToSqr(lockedTarget) <= rangeSq) {
                if (!losCheck || hasLos(mc, lockedTarget)) { lockTicks++; return lockedTarget; }
            }
            // Alvo inválido — libera agora, não no próximo tick
            lockedTarget = null;
            lockTicks = 0;
        }

        LivingEntity best     = null;
        double       minAngle = fovDeg / 2.0;

        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity lv)) continue;
            if (lv == mc.player || !lv.isAlive()) continue;
            if (lv instanceof Player p && (p.isSpectator() || p.isCreative())) continue;
            if (mc.player.distanceToSqr(lv) > rangeSq) continue;
            if (losCheck && !hasLos(mc, lv)) continue;
            double ang = fovAngle(mc, bodyPoint(lv));
            if (ang < minAngle) { minAngle = ang; best = lv; }
        }

        if (best != null) { lockedTarget = best; lockTicks = 0; }
        return lockedTarget;
    }

    // ── Aim ───────────────────────────────────────────────────────────────
    private static void aimAt(Minecraft mc, LivingEntity target) {
        Vec3 aim = aimPoint(target);

        // Predição de posição (1 tick, sem lag de filtro)
        Vec3 vel = target.getDeltaMovement();
        Vec3 acc = vel.subtract(prevVel);
        prevVel  = vel;

        // Ping compensation: lê latência do ALVO, não do player local
        long latency = 50L;
        if (pingComp && mc.getConnection() != null) {
            try {
                PlayerInfo info = (target instanceof Player tp)
                    ? mc.getConnection().getPlayerInfo(tp.getUUID())
                    : null;
                if (info != null) latency = info.getLatency();
            } catch (Exception ignored) {}
        }
        // B14: clamp 2 ticks + damping — evita overshoot quando alvo strafar
        double lead = Math.min(latency / 50.0, 2.0);
        Vec3 predicted = aim
            .add(vel.scale(lead * 0.7))
            .add(acc.scale(0.3 * lead));

        if (microAdjust) {
            // Gaussian por tick = tremor biológico real, não padrão fixo
            predicted = predicted.add(
                RNG.nextGaussian() * microNoise * (0.7 + 0.3 * RNG.nextDouble()),
                RNG.nextGaussian() * microNoise * 0.45 * (0.7 + 0.3 * RNG.nextDouble()),
                0
            );
        }

        // Factor: erro GRANDE → snap rápido; erro pequeno → settle suave (lógica correta)
        double yawErr   = Math.abs(RotationManager.computeYawError(mc, predicted));
        double pitchErr = Math.abs(RotationManager.computePitchError(mc, predicted));
        double totalErr = Math.hypot(yawErr, pitchErr);

        double dist  = mc.player.distanceTo(target);
        double wMul  = weaponSmooth(mc);

        // Factor sobe com o erro — quanto mais longe da mira, mais rápido corrige
        float base   = (dist < 3.0) ? smClose : (dist < 6.0) ? smMid : smFar;
        float factor = (float) Math.min(0.92, base + (totalErr / 45.0) * (1.0 - base));
        factor       = (float) Math.max(0.05, factor * wMul - fatigue * 0.08);

        // Quando player está no ar (pulo/queda): KINEMATIC_SPRING deixa a mira
        // seguir o sweep natural do corpo inteiro — evita travar no peito
        RotationManager.EasingMode mode;
        if (!mc.player.onGround()) {
            // Boost sutil no factor para acompanhar a mudança de ângulo na queda
            double vy = Math.abs(mc.player.getDeltaMovement().y);
            factor = (float) Math.min(0.92, factor + vy * 0.22);
            mode = RotationManager.EasingMode.KINEMATIC_SPRING;
        } else if (totalErr > 25.0 || dist < 3.0) {
            mode = RotationManager.EasingMode.SWIGHT_HIGH_SENS;
        } else {
            mode = RotationManager.EasingMode.EASE_OUT_EXPO;
        }
        RotationManager.setEasingMode(mode);
        RotationManager.smoothTo(mc, predicted, factor);

        if (yawErr < 2.0 && pitchErr < 2.0) hits++; else misses++;
        if (YAW_ERR.size()   >= 64) YAW_ERR.pollFirst();
        if (PITCH_ERR.size() >= 64) PITCH_ERR.pollFirst();
        YAW_ERR.addLast(yawErr);
        PITCH_ERR.addLast(pitchErr);
    }

    /**
     * Ponto de mira: 75% da altura do alvo (massa corporal, não cabeça nem pé).
     * NÃO clampeia no eye.y do player — isso causava a mira travada ao pular.
     * Ao pular, o player sobe e o ponto de mira (fixo no alvo) fica naturalmente
     * mais baixo no campo de visão, criando o sweep suave de peito→cabeça.
     */
    private static Vec3 aimPoint(LivingEntity target) {
        return new Vec3(
            target.getX(),
            target.getY() + target.getBbHeight() * 0.75,
            target.getZ()
        );
    }

    // Ponto usado para seleção de FOV (centro do hitbox)
    private static Vec3 bodyPoint(LivingEntity target) {
        return new Vec3(target.getX(), target.getY() + target.getBbHeight() * 0.5, target.getZ());
    }

    // ── Utilitários ───────────────────────────────────────────────────────
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
            || n.contains("mace")  || n.contains("bow") || n.contains("crossbow");
    }

    private static double weaponSmooth(Minecraft mc) {
        if (mc.player == null) return 1.0;
        String n = mc.player.getMainHandItem().getItem().getDescriptionId().toLowerCase();
        if (n.contains("crossbow")) return 1.30;
        if (n.contains("bow"))      return 1.30;
        if (n.contains("mace"))     return 0.92;
        if (n.contains("axe"))      return 1.05;
        return 1.00;
    }

    // Libera o lock mas não zera tudo (sessão continua)
    private static void release() {
        lockedTarget = null;
        lockTicks    = 0;
        prevVel      = Vec3.ZERO;
    }

    public static void reset() {
        lockedTarget = null;
        lockTicks    = 0;
        switchCD     = 0;
        prevVel      = Vec3.ZERO;
    }

    // ── Getters / Setters ─────────────────────────────────────────────────
    public static boolean isLockedOnTarget()   { return lockedTarget != null && lockedTarget.isAlive(); }
    public static Entity  getLockedTarget()    { return lockedTarget; }
    public static int     getTargetLockTicks() { return lockTicks; }
    public static long    getExecTicks()       { return execTicks; }
    public static double  getFatigueLevel()    { return fatigue; }
    public static UUID    getSessionId()       { return SESSION_ID; }
    public static double  getAccuracy()        { long t = hits + misses; return t > 0 ? (double) hits / t : 1.0; }
    public static double  getLastYawErr()      { return YAW_ERR.isEmpty()   ? 0 : YAW_ERR.peekLast(); }
    public static double  getLastPitchErr()    { return PITCH_ERR.isEmpty() ? 0 : PITCH_ERR.peekLast(); }

    public static void setFov(float f)                      { fovDeg     = f; }
    public static void setAimRange(double r)                { aimRange   = r; }
    public static void setReach(double r)                   { reach      = Math.max(r, 3.0); }
    public static void setSmoothClose(float s)              { smClose    = s; }
    public static void setSmoothMid(float s)                { smMid      = s; }
    public static void setSmoothFar(float s)                { smFar      = s; }
    public static void setPingComp(boolean b)               { pingComp   = b; }
    public static void setLosCheck(boolean b)               { losCheck   = b; }
    public static void setMicroAdjust(boolean b, double m)  { microAdjust = b; microNoise = m; }

    // compat
    public static void setMaximumFov(float f)           { fovDeg = f; }
    public static void setMaximumReach(double r)        { setReach(r); }
    public static void setKinematicSmoothing(double s)  { smMid = (float) s; }
    public static void setAdaptiveSmoothing(boolean b)  {}
    public static void setPingCompensation(boolean b)   { pingComp = b; }
    public static void setLosValidation(boolean b)      { losCheck = b; }
    public static void resetFilters()                   { reset(); }
    public static double getSessionAccuracy()           { return getAccuracy(); }
    public static UUID   getSubsessionIdentity()        { return SESSION_ID; }
    public static long   getGlobalExecCount()           { return execTicks; }
}
