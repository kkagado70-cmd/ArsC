package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import java.security.SecureRandom;
import java.util.UUID;

public class AutoMace {

    public static final String FILE_NAME = "AutoMace.java";
    public static boolean enabled = false;

    private static final SecureRandom RNG = new SecureRandom();
    private static final UUID SUBSESSION_IDENTITY = UUID.randomUUID();

    // ── Config ────────────────────────────────────────────────────────────
    // maxAimDistance: detecção/tracking de alvo (pode ser alto)
    // MACE_REACH:     reach real do servidor para mace smash
    // SPEAR_REACH:    reach da lança (attribute swap)
    private static double  maxAimDistance  = 8.0D;  // B19: 30 era flag
    private static double  minFallDistance = 1.5D;
    private static boolean windChargeDetection = true;
    private static boolean elytraDiveCheck     = true;
    private static final double MACE_REACH     = 3.0D;
    private static final double SPEAR_REACH    = 4.5D;

    // ── Estado de voo/pico ────────────────────────────────────────────────
    private static double  peakY    = Double.NEGATIVE_INFINITY;
    private static boolean hasPeaked = false;

    // ── Estado de aiming ─────────────────────────────────────────────────
    private static LivingEntity lockedTarget = null;
    private static int          aimTicks     = 0;
    private static boolean      isTracking   = false;
    private static final int    MAX_AIM_TICKS = 10;
    private static final float  AIM_YAW_TOL   = 4.5f;
    private static final float  AIM_PITCH_TOL  = 5.0f;

    // ── Slot management ───────────────────────────────────────────────────
    private static int  savedSlot    = -1;
    private static int  smashCD      = 0;

    // ── Enum de estado de ataque ──────────────────────────────────────────
    private enum AttackMode { NONE, MACE_SMASH, SPEAR_SWAP }

    // ── Session ───────────────────────────────────────────────────────────
    private static long   executionTicks  = 0L;
    private static double fatigueLevel    = 0.0D;
    private static final double F_INC    = 0.001D;
    private static final double F_DEC    = 0.0003D;

    // ── Lifecycle ─────────────────────────────────────────────────────────
    public static void toggle() {
        enabled = !enabled;
        if (!enabled) hardReset(null);
    }

    public static void register() { /* no-op: ClientBase.ModuleManager handles tick */ }

    // ── Tick principal ────────────────────────────────────────────────────
    public static void onTick(Minecraft mc) {
        if (!enabled || mc.player == null || mc.level == null || !mc.player.isAlive()) return;

        executionTicks++;
        fatigueLevel = Math.max(0.0D, fatigueLevel - F_DEC);
        if (smashCD > 0) smashCD--;

        RotationManager.samplePlayerGcd(mc);

        // Rastreia pico de altura para detectar descida
        trackPeak(mc);

        // ── Seleciona alvo ────────────────────────────────────────────────
        lockedTarget = null;
        double minDstSq = maxAimDistance * maxAimDistance;
        for (Player p : mc.level.players()) {
            if (p == mc.player || !p.isAlive() || p.isSpectator() || p.isCreative()) continue;
            double dstSq = mc.player.distanceToSqr(p);
            if (dstSq > minDstSq) continue;
            if (!verifyLos(mc, p)) continue;
            if (dstSq < minDstSq) { minDstSq = dstSq; lockedTarget = p; }
        }

        if (lockedTarget == null) { isTracking = false; return; }

        // ── Verifica condições de mergulho ────────────────────────────────
        double vy        = mc.player.getDeltaMovement().y;
        double fallDist  = mc.player.fallDistance;
        boolean elytra   = mc.player.isFallFlying();
        // wind (vy > 0.75 = subindo) removido do diving: mace smash exige queda (vy < 0)
        boolean peaked   = hasPeaked || (peakY != Double.NEGATIVE_INFINITY && (peakY - mc.player.getY()) >= minFallDistance);
        boolean diving   = (!mc.player.onGround() && peaked && vy < -0.05D)
                        || fallDist >= minFallDistance
                        || (elytraDiveCheck && elytra);

        if (!diving) { isTracking = false; return; }

        // ── Determina modo de ataque com base na distância ────────────────
        double dist = mc.player.distanceTo(lockedTarget);
        AttackMode mode = resolveAttackMode(mc, dist);

        // ── Prepara slot ──────────────────────────────────────────────────
        if (!prepareSlot(mc, mode)) return;

        // ── Mira no alvo ──────────────────────────────────────────────────
        Vec3 center = new Vec3(
            lockedTarget.getX(),
            lockedTarget.getY() + lockedTarget.getBbHeight() * 0.75,
            lockedTarget.getZ()
        );

        if (!isTracking) { aimTicks = 0; isTracking = true; }
        aimTicks++;

        double aimErr = Math.hypot(
            RotationManager.computeYawError(mc, center),
            RotationManager.computePitchError(mc, center)
        );

        float factor = aimErr > 20.0D ? 0.99f
                     : aimErr > 8.0D  ? 0.78f
                     : Math.max(0.15f, 0.28f + 0.5f * (float)(aimErr / 8.0D));
        if (aimTicks < 4) factor *= (aimTicks / 4.0f);

        RotationManager.setEasingMode(aimErr > 20.0D
            ? RotationManager.EasingMode.SWIGHT_HIGH_SENS
            : RotationManager.EasingMode.EASE_OUT_EXPO);
        RotationManager.smoothTo(mc, center, factor);

        // ── Ataca quando alinhado ─────────────────────────────────────────
        boolean aligned = RotationManager.isAligned(mc, center, AIM_YAW_TOL, AIM_PITCH_TOL);
        float scale = mc.player.getAttackStrengthScale(0.0f);

        double reachForMode = (mode == AttackMode.SPEAR_SWAP) ? SPEAR_REACH : MACE_REACH;
        boolean inReach = dist <= reachForMode;

            // B4: justStunnedTicks — lê e decrementa (funciona independente de ordem de módulos)
        boolean stunThisTick = ShieldBreaker.justStunnedTicks > 0;
        if (stunThisTick) ShieldBreaker.justStunnedTicks--;

        // B6: smash ignora cooldown (MC-270160) — só checar aligned+inReach+falling
        if (aligned && inReach && (smashCD == 0 || stunThisTick)) {
            executeAttack(mc, mode);
        }

        // Timeout — evita ficar preso no tracking
        if (aimTicks > 200) {
            isTracking = false;
            aimTicks   = 0;
            restoreSlot(mc);
        }
    }

    // ── Resolve modo de ataque ────────────────────────────────────────────
    private static AttackMode resolveAttackMode(Minecraft mc, double dist) {
        if (dist <= MACE_REACH) return AttackMode.MACE_SMASH;
        if (dist <= SPEAR_REACH && hasSpear(mc)) return AttackMode.SPEAR_SWAP;
        return AttackMode.NONE;
    }

    // ── Prepara o slot correto para o modo ────────────────────────────────
    private static boolean prepareSlot(Minecraft mc, AttackMode mode) {
        if (mode == AttackMode.NONE) return false;

        int targetSlot = (mode == AttackMode.SPEAR_SWAP) ? findSpear(mc) : findMace(mc);
        if (targetSlot < 0) return false;

        int current = SlotAccessor.get(mc);

        // Guard: se o usuário trocou manualmente (slot diferente do savedSlot), reseta
        if (savedSlot >= 0 && current != targetSlot && current != savedSlot) {
            restoreSlot(mc);
            return false;
        }

        if (current != targetSlot) {
            if (savedSlot < 0) savedSlot = current;
            InventoryManager.selectSlot(mc, targetSlot);
            return false; // espera um tick para o servidor reconhecer a troca
        }

        return true;
    }

    // ── Executa o ataque ──────────────────────────────────────────────────
    // L7: SPEAR_SWAP no mesmo tick:
    //   1. prepareSlot já selecionou a spear (reach 4.5)
    //   2. simulateClickAttack envia o pacote de ataque com a spear na mão
    //   3. restoreSlot imediatamente → servidor vê troca+ataque+restore em 0.05s
    private static void executeAttack(Minecraft mc, AttackMode mode) {
        InteractionManager.simulateClickAttack(mc);
        smashCD = 2 + RNG.nextInt(3);
        fatigueLevel = Math.min(1.0D, fatigueLevel + F_INC);
        GrimBypassCore.onHitLanded(20, 40, 20, 40);

        // Restore imediato — mesmo tick
        restoreSlot(mc);

        isTracking = false;
        aimTicks   = 0;
    }

    // ── Rastreia pico de altura ───────────────────────────────────────────
    private static void trackPeak(Minecraft mc) {
        double y  = mc.player.getY();
        double vy = mc.player.getDeltaMovement().y;
        // Reseta pico em terra, água, lava e escada para evitar smash em terreno falso
        if (mc.player.onGround()
                || mc.player.isInWater()
                || mc.player.isInLava()
                || mc.player.onClimbable()) {
            peakY = y; hasPeaked = false; return;
        }
        if (vy > 0) peakY = peakY == Double.NEGATIVE_INFINITY ? y : Math.max(peakY, y);
        if (vy < 0 && peakY != Double.NEGATIVE_INFINITY) hasPeaked = true;
    }

    // ── Busca de itens no hotbar ──────────────────────────────────────────
    private static int findMace(Minecraft mc) {
        for (int i = 0; i < 9; i++)
            if (mc.player.getInventory().getItem(i).getItem() == Items.MACE) return i;
        return -1;
    }

    private static boolean hasSpear(Minecraft mc) { return findSpear(mc) >= 0; }

    private static int findSpear(Minecraft mc) {
        // Spear = trident (item mais próximo do alcance estendido disponível vanilla)
        // Em mods com spear custom, adicionar aqui
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.player.getInventory().getItem(i);
            String n = s.getItem().getDescriptionId().toLowerCase();
            if (n.contains("trident") || n.contains("spear")) return i;
        }
        return -1;
    }

    private static void restoreSlot(Minecraft mc) {
        if (savedSlot >= 0 && mc != null && mc.player != null) {
            InventoryManager.restoreSavedSlot(mc);
        }
        savedSlot = -1;
    }

    // ── LOS ──────────────────────────────────────────────────────────────
    private static boolean verifyLos(Minecraft mc, Entity target) {
        if (mc.player == null || target == null || mc.level == null) return false;
        Vec3 start = mc.player.getEyePosition();
        Vec3 end   = target.getEyePosition();
        BlockHitResult hit = mc.level.clip(
            new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        return hit.getType() == HitResult.Type.MISS;
    }

    private static void hardReset(Minecraft mc) {
        if (mc != null) restoreSlot(mc);
        lockedTarget = null;
        isTracking   = false;
        aimTicks     = 0;
        peakY        = Double.NEGATIVE_INFINITY;
        hasPeaked    = false;
        smashCD      = 0;
        savedSlot    = -1;
    }

    // ── API pública ───────────────────────────────────────────────────────
    /** B1: AimAssist yields rotation when AutoMace is actively tracking */
    public static boolean     isTracking()                  { return isTracking; }
    public static long        getExecutionTicks()           { return executionTicks; }
    public static double      getFatigueLevel()             { return fatigueLevel; }
    public static LivingEntity getLockedTarget()            { return lockedTarget; }
    public static UUID        getSubsessionIdentity()       { return SUBSESSION_IDENTITY; }
    public static void        setMaxAimDistance(double d)   { maxAimDistance = d; }
    public static double      getMaxAimDistance()           { return maxAimDistance; }
    public static void        setMinFallDistance(double d)  { minFallDistance = d; }
    public static double      getMinFallDistance()          { return minFallDistance; }
    public static void        setWindChargeDetection(boolean b) { windChargeDetection = b; }
    public static void        setElytraDiveCheck(boolean b)    { elytraDiveCheck = b; }
    // compat
    public static void   setMaxSwingRange(double r)  {} // ignorado — reach é fixo no servidor
    public static double getMaxSwingRange()           { return MACE_REACH; }
    public static void   setHyperSnapSpeed(float s)  {}
    public static float  getHyperSnapSpeed()         { return 0.99f; }
    public static boolean isWindChargeDetectionActive()  { return windChargeDetection; }
    public static boolean isElytraDiveCheckActive()      { return elytraDiveCheck; }
    public static boolean verifyMaceSubsystemHealth()    { return enabled; }
    public static void    clearAllMaceQueues()           {}
}
