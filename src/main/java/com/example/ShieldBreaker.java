package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import java.security.SecureRandom;
import java.util.UUID;

public class ShieldBreaker {

    public static final String FILE_NAME = "ShieldBreaker.java";
    public static boolean enabled = false;

    // WAIT_AXE_SYNC: aguarda o servidor confirmar a troca antes de atacar
    private enum State { IDLE, REACTING, SWAPPING, WAIT_AXE_SYNC, AIMING, SWINGING, COOLDOWN, FOLLOWUP }

    private static State         currentState       = State.IDLE;
    private static int           reactionDelay      = 0;
    private static int           cooldownTicks      = 0;
    private static int           followUpRemaining  = 0;
    private static int           aimTicks           = 0;
    private static int           axeSyncWait        = 0;
    private static int           savedSlot          = -1;
    private static LivingEntity  lockedTarget       = null;

    private static final SecureRandom RNG = new SecureRandom();
    private static final UUID SESSION_ID  = UUID.randomUUID();

    private static long   globalTicks          = 0L;
    private static double maxReach             = 3.0D;
    private static boolean shieldStunActiveSync = false; // true SOMENTE durante o swing real
    public  static volatile boolean justStunned = false;   // AutoMace lê para Stun Slam no mesmo tick
    private static int    sessionStunCount     = 0;
    private static double fatigueLevel         = 0.0D;
    private static final double F_INC          = 0.001D;
    private static final double F_DEC          = 0.0005D;
    private static double missChance           = 0.008D;
    private static float  overshootYaw         = 0.0f;
    private static float  overshootPitch       = 0.0f;
    private static int    saccadeTimer         = 0;
    private static int    maxAimTicks          = 8;
    private static float  aimYawTol            = 3.5f;
    private static float  aimPitchTol          = 4.0f;
    private static boolean followUpEnabled     = true;
    private static int    followUpCount        = 2;

    public static void toggle() { enabled = !enabled; hardReset(); }

    public static void register() { /* no-op: ClientBase.ModuleManager handles tick */ }

    public static void onTick(Minecraft mc) {
        if (!enabled || mc.player == null || mc.level == null) return;
        if (!mc.player.isAlive()) return;
        if (mc.player.isFallFlying() || mc.player.fallDistance > 1.5F) {
            shieldStunActiveSync = false;
            currentState = State.IDLE;
            return;
        }
        if (!hasAxeOrSword(mc)) {
            shieldStunActiveSync = false;
            currentState = State.IDLE;
            return;
        }

        globalTicks++;
        fatigueLevel = Math.max(0.0D, fatigueLevel - F_DEC);
        RotationManager.samplePlayerGcd(mc);

        switch (currentState) {
            case IDLE          -> tickIdle(mc);
            case REACTING      -> tickReacting(mc);
            case SWAPPING      -> tickSwapping(mc);
            case WAIT_AXE_SYNC -> tickWaitAxeSync(mc);
            case AIMING        -> tickAiming(mc);
            case SWINGING      -> tickSwinging(mc);
            case COOLDOWN      -> tickCooldown(mc);
            case FOLLOWUP      -> tickFollowUp(mc);
        }
    }

    private static void tickIdle(Minecraft mc) {
        LivingEntity target = findShieldTarget(mc);
        if (target == null) return;
        lockedTarget = target;
        reactionDelay = 1 + RNG.nextInt(3);
        currentState = State.REACTING;
        // shieldStunActiveSync permanece FALSE aqui — só sobe no swing real
    }

    private static void tickReacting(Minecraft mc) {
        if (!validateTarget(mc)) { resetToIdle(); return; }
        if (--reactionDelay > 0) return;
        int axeSlot = findBestAxe(mc);
        if (axeSlot < 0) { resetToIdle(); return; }
        savedSlot = SlotAccessor.get(mc);
        InventoryManager.saveCurrentSlot(mc);
        InventoryManager.selectSlot(mc, axeSlot);
        axeSyncWait  = 0;
        currentState = State.SWAPPING;
    }

    private static void tickSwapping(Minecraft mc) {
        if (!validateTarget(mc)) { hardReset(); return; }
        // Aguarda servidor confirmar troca (WAIT_AXE_SYNC)
        currentState = State.WAIT_AXE_SYNC;
    }

    private static void tickWaitAxeSync(Minecraft mc) {
        if (!validateTarget(mc)) { hardReset(); return; }
        axeSyncWait++;

        // Verifica se o slot atual realmente contém um machado
        ItemStack held = mc.player.getMainHandItem();
        String heldName = held.isEmpty() ? "" : held.getItem().getDescriptionId().toLowerCase();
        boolean axeReady = heldName.contains("axe");

        // Aguarda pelo menos 2 ticks para servidor confirmar a troca de slot
        if (axeSyncWait < 2) return;
        // Se axeReady OU excedeu 3 ticks, avança
        if (!axeReady && axeSyncWait < 3) return;
        Vec3 center = targetCenter(lockedTarget);
        if (RotationManager.isAligned(mc, center, aimYawTol, aimPitchTol)) {
            aimTicks     = 0;
            currentState = State.SWINGING;
        } else {
            aimTicks = 0;
            saccadeTimer = 0;
            overshootYaw   = (float)((RNG.nextDouble() - 0.5) * 0.9D);
            overshootPitch = (float)((RNG.nextDouble() - 0.5) * 0.7D);
            currentState = State.AIMING;
        }
    }

    private static void tickAiming(Minecraft mc) {
        if (!validateTarget(mc)) { hardReset(); return; }
        aimTicks++;

        // Saccade só em AIMING, não em outros estados
        updateSaccade();
        Vec3 center = targetCenter(lockedTarget)
            .add(overshootYaw * 0.01, overshootPitch * 0.01, 0);

        double dist   = mc.player.distanceTo(lockedTarget);
        float  factor = dist < 2.5D ? 0.88f : 0.95f;

        RotationManager.setEasingMode(RotationManager.EasingMode.SWIGHT_HIGH_SENS);
        RotationManager.smoothTo(mc, center, factor);

        boolean aligned = RotationManager.isAligned(mc, center, aimYawTol, aimPitchTol);
        if (aligned || aimTicks >= maxAimTicks) {
            if (!aligned) RotationManager.snapTo(mc, center);
            aimTicks     = 0;
            currentState = State.SWINGING;
        }
    }

    private static void tickSwinging(Minecraft mc) {
        if (!validateTarget(mc, false)) { hardReset(); return; }

        // Usa 1.0f (partial tick padrão), não 0.5f
        float strength = mc.player.getAttackStrengthScale(1.0f);
        if (strength < 0.80f) return;

        if (RNG.nextDouble() < missChance) {
            cooldownTicks = 6 + RNG.nextInt(5);
            currentState  = State.COOLDOWN;
            hardResetInventory(mc);
            return;
        }

        // shieldStunActiveSync = true SOMENTE no momento do swing real
        shieldStunActiveSync = true;
        // justStunned: AutoMace lê para Stun Slam (mace smash no mesmo tick)
        justStunned = true;

        // Um único simulateClickAttack — não chamar swing() separado (double swing)
        InteractionManager.simulateClickAttack(mc);

        sessionStunCount++;
        fatigueLevel = Math.min(1.0D, fatigueLevel + F_INC);

        if (followUpEnabled && followUpCount > 0) {
            followUpRemaining = followUpCount;
            int swordSlot = findBestSword(mc);
            if (swordSlot >= 0) InventoryManager.selectSlot(mc, swordSlot);
            currentState = State.FOLLOWUP;
        } else {
            int baseCd = 14 + RNG.nextInt(6);
            cooldownTicks = (int)(baseCd + fatigueLevel * 4.0D);
            currentState  = State.COOLDOWN;
            hardResetInventory(mc);
            shieldStunActiveSync = false;
            justStunned = false;
        }
    }

    private static void tickFollowUp(Minecraft mc) {
        // validateTarget sem requireShield — alvo pode ter baixado escudo após stun
        if (followUpRemaining <= 0) {
            int baseCd = 14 + RNG.nextInt(6);
            cooldownTicks = (int)(baseCd + fatigueLevel * 4.0D);
            currentState  = State.COOLDOWN;
            hardResetInventory(mc);
            shieldStunActiveSync = false;
            return;
        }
        if (!mc.player.isAlive()) { hardReset(); return; }
        if (lockedTarget != null && lockedTarget.isAlive() && mc.player.distanceTo(lockedTarget) <= maxReach) {
            float strength = mc.player.getAttackStrengthScale(1.0f);
            if (strength >= 0.75f) {
                InteractionManager.simulateClickAttack(mc);
                followUpRemaining--;
            }
        } else {
            followUpRemaining = 0;
        }
    }

    private static void tickCooldown(Minecraft mc) {
        if (--cooldownTicks <= 0) resetToIdle();
    }

    private static void updateSaccade() {
        saccadeTimer++;
        if (saccadeTimer > 18 + RNG.nextInt(12)) {
            saccadeTimer   = 0;
            overshootYaw   = (float)((RNG.nextDouble() - 0.5) * 0.8D);
            overshootPitch = (float)((RNG.nextDouble() - 0.5) * 0.6D);
        } else {
            overshootYaw   *= 0.92f;
            overshootPitch *= 0.92f;
            if (Math.abs(overshootYaw)   < 0.02f) overshootYaw   = 0.0f;
            if (Math.abs(overshootPitch) < 0.02f) overshootPitch = 0.0f;
        }
    }

    private static Vec3 targetCenter(LivingEntity t) {
        return t.position().add(0.0D, t.getBbHeight() * 0.45D, 0.0D);
    }

    private static boolean validateTarget(Minecraft mc) {
        return validateTarget(mc, true);
    }

    /** requireShield=false para SWINGING/FOLLOWUP: alvo pode ter baixado o escudo após o stun. */
    private static boolean validateTarget(Minecraft mc, boolean requireShield) {
        if (lockedTarget == null || !lockedTarget.isAlive()) return false;
        if (requireShield && !isBlocking(lockedTarget)) return false;
        if (mc.player.distanceTo(lockedTarget) > maxReach + 1.0D) return false;
        return true;
    }

    private static LivingEntity findShieldTarget(Minecraft mc) {
        LivingEntity best = null;
        double minSq = maxReach * maxReach;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity lv) || lv == mc.player || !lv.isAlive()) continue;
            if (lv instanceof Player p && (p.isSpectator() || p.isCreative())) continue;
            double sq = mc.player.distanceToSqr(lv);
            if (sq > minSq) continue;
            if (!verifyLos(mc, lv)) continue;
            if (!isBlocking(lv)) continue;
            if (best == null || sq < mc.player.distanceToSqr(best)) best = lv;
        }
        return best;
    }

    private static boolean isBlocking(LivingEntity t) {
        return t != null && t.isUsingItem() && t.getUseItem().getItem() == Items.SHIELD;
    }

    private static boolean verifyLos(Minecraft mc, Entity target) {
        if (mc.player == null || target == null || mc.level == null) return false;
        Vec3 start = mc.player.getEyePosition();
        Vec3 end   = target.getEyePosition();
        BlockHitResult hit = mc.level.clip(
            new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        return hit.getType() == HitResult.Type.MISS;
    }

    private static boolean hasAxeOrSword(Minecraft mc) {
        if (mc.player == null) return false;
        // Não bloqueia se o inventário tiver machado disponível
        for (int i = 0; i < 9; i++) {
            String n = mc.player.getInventory().getItem(i).getItem().getDescriptionId().toLowerCase();
            if (n.contains("axe") || n.contains("sword")) return true;
        }
        return false;
    }

    private static int findBestAxe(Minecraft mc) {
        Item[] axes = { Items.NETHERITE_AXE, Items.DIAMOND_AXE, Items.IRON_AXE, Items.GOLDEN_AXE, Items.STONE_AXE, Items.WOODEN_AXE };
        for (Item ax : axes) { int s = findSlot(mc, ax); if (s >= 0) return s; }
        return -1;
    }

    private static int findBestSword(Minecraft mc) {
        Item[] swords = { Items.NETHERITE_SWORD, Items.DIAMOND_SWORD, Items.IRON_SWORD, Items.GOLDEN_SWORD, Items.STONE_SWORD, Items.WOODEN_SWORD };
        for (Item sw : swords) { int s = findSlot(mc, sw); if (s >= 0) return s; }
        return -1;
    }

    private static int findSlot(Minecraft mc, Item item) {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++)
            if (mc.player.getInventory().getItem(i).getItem() == item) return i;
        return -1;
    }

    private static void hardResetInventory(Minecraft mc) {
        if (savedSlot >= 0 && mc != null && mc.player != null)
            InventoryManager.restoreSavedSlot(mc);
        savedSlot = -1;
    }

    private static void resetToIdle() {
        currentState         = State.IDLE;
        reactionDelay        = 0;
        cooldownTicks        = 0;
        followUpRemaining    = 0;
        aimTicks             = 0;
        axeSyncWait          = 0;
        lockedTarget         = null;
        shieldStunActiveSync = false;
        justStunned          = false;
        savedSlot            = -1;
    }

    public static void hardReset() {
        resetToIdle();
        fatigueLevel   = 0.0D;
        sessionStunCount = 0;
        overshootYaw   = 0.0f;
        overshootPitch = 0.0f;
        saccadeTimer   = 0;
    }

    // ── API ───────────────────────────────────────────────────────────────
    public static boolean isShieldStunActive()  { return shieldStunActiveSync; }
    public static int     getSessionStunCount() { return sessionStunCount; }
    public static double  getFatigueLevel()     { return fatigueLevel; }
    public static String  getCurrentStateName() { return currentState.name(); }
    public static void    setMaxReach(double r) { maxReach = Math.min(r, 3.0); }
    public static void    setFollowUp(boolean b, int count) { followUpEnabled = b; followUpCount = count; }
    public static void    setAimTolerances(float yt, float pt) { aimYawTol = yt; aimPitchTol = pt; }
    public static UUID    getSubsessionIdentity()              { return SESSION_ID; }
}
