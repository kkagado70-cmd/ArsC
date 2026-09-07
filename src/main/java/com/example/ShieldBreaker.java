package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.Deque;

public class ShieldBreaker extends ClientBase.Module {
    public static final String FILE_NAME = "ShieldBreaker.java";
    public static boolean enabled = true;

    private enum ShieldState { IDLE, SPRINT_APPROACH, AXE_SWING_PREP, EXECUTE_SWIGHT_STUN, SWORD_CRIT_FOLLOWUP, AUTO_HIT_DEFENSE }

    private static ShieldState currentShieldState = ShieldState.IDLE;
    private static int actionStateCountdown = 0;
    private static int shieldDisableCooldownTimer = 0;
    private static LivingEntity lockedShieldTarget = null;
    private static final SecureRandom secureRandom = new SecureRandom();

    private static final Map<String, Object> SHIELD_REGISTRY = new ConcurrentHashMap<>();
    private static final UUID SUBSESSION_ID = UUID.randomUUID();
    private static final Deque<Long> STUN_HISTORY = new ArrayDeque<>();
    private static final Deque<Double> VELOCITY_HISTORY = new ArrayDeque<>();
    private static final int HISTORY_CAP = 512;

    private static long globalTicks = 0L;
    private static boolean autoAxe = true;
    private static boolean swightStun = true;
    private static boolean doubleClickBurst = true;
    private static double maxReach = 5.0D;
    private static boolean shieldDelayComp = true;
    private static int microBurstDelay = 1;
    private static boolean autoHitDef = true;
    private static boolean stealthProfile = true;
    private static int anomalyTracker = 0;
    private static boolean strictSync = true;
    private static double jitterFactor = 0.006D;
    private static boolean instantSwordSwap = true;
    private static int successiveStuns = 0;
    private static long lastStunEpoch = 0L;
    private static boolean momentumReset = false;
    private static double spacingBuffer = 3.0D;
    private static boolean antiReplay = true;
    private static boolean pCritMomentum = true;
    private static boolean threeBlockStun = true;
    private static boolean autoCritDefense = true;
    private static double reactionCompMs = 250.0D;
    private static boolean targetPrediction = true;
    private static boolean spacingOptimization = true;
    private static int sessionStunCount = 0;
    private static boolean hardwareBypass = true;
    private static boolean profileLocked = false;
    private static double stochasticVariance = 0.03D;
    private static int emergencyResetThreshold = 100;
    private static boolean shieldStunActiveSync = false;

    static {
        initializeRegistry();
    }

    private static void initializeRegistry() {
        SHIELD_REGISTRY.put("SubsessionUUID", SUBSESSION_ID);
        SHIELD_REGISTRY.put("Profile", "Swight-Tier1-ShieldBreaker-FullEnterprise");
        SHIELD_REGISTRY.put("SwightStun", swightStun);
        SHIELD_REGISTRY.put("DoubleClickBurst", doubleClickBurst);
        SHIELD_REGISTRY.put("AutoAxe", autoAxe);
        SHIELD_REGISTRY.put("SuccessiveStuns", successiveStuns);
    }

    public ShieldBreaker() {
        super("ShieldBreaker");
        ShieldBreaker.enabled = true;
    }

    @Override
    public boolean isEnabled() { return enabled; }

    @Override
    public void toggle() {
        enabled = !enabled;
        super.enabled = enabled;
        hardReset();
    }

    private static void hardReset() {
        currentShieldState = ShieldState.IDLE;
        actionStateCountdown = 0;
        shieldDisableCooldownTimer = 0;
        lockedShieldTarget = null;
        anomalyTracker = 0;
        successiveStuns = 0;
        sessionStunCount = 0;
        momentumReset = false;
        shieldStunActiveSync = false;
        STUN_HISTORY.clear();
        VELOCITY_HISTORY.clear();
        SHIELD_REGISTRY.clear();
        initializeRegistry();
    }

    @Override
    public void tick(Minecraft client) { onTick(client); }

    private static boolean validateWeaponContext(Minecraft client) {
        if (client.player == null) return false;
        ItemStack stack = client.player.getMainHandItem();
        if (stack.isEmpty()) return false;
        String name = stack.getItem().getDescriptionId().toLowerCase();
        return name.contains("sword") || name.contains("axe") || name.contains("trident") || name.contains("mace");
    }

    private static boolean isBlocking(LivingEntity target) {
        if (target == null) return false;
        return target.isUsingItem() && target.getUseItem().getItem() == Items.SHIELD;
    }

    private static boolean verifyLos(Minecraft client, Entity target) {
        if (client.player == null || target == null) return false;
        Vec3 start = client.player.getEyePosition();
        Vec3 end = target.getEyePosition();
        BlockHitResult hit = client.level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, client.player));
        return hit.getType() == HitResult.Type.MISS;
    }

    public static boolean isShieldStunActive() {
        return shieldStunActiveSync;
    }

    public static void onTick(Minecraft client) {
        if (!enabled || client.player == null || client.level == null || !client.player.isAlive()) return;
        if (!validateWeaponContext(client)) {
            shieldStunActiveSync = false;
            currentShieldState = ShieldState.IDLE;
            return;
        }

        globalTicks++;
        if (shieldDisableCooldownTimer > 0) shieldDisableCooldownTimer--;
        if (actionStateCountdown > 0) { actionStateCountdown--; return; }

        LivingEntity target = evaluateTarget(client);
        if (target != null) {
            lockedShieldTarget = target;
            executePipeline(client, target);
        } else {
            lockedShieldTarget = null;
            shieldStunActiveSync = false;
            currentShieldState = ShieldState.IDLE;
        }
        updateRegistry();
    }

    private static LivingEntity evaluateTarget(Minecraft client) {
        if (lockedShieldTarget != null) {
            if (lockedShieldTarget.isAlive() && client.player.distanceToSqr(lockedShieldTarget) <= (maxReach * maxReach)) {
                return lockedShieldTarget;
            }
            lockedShieldTarget = null;
        }
        LivingEntity best = null;
        double minDst = (maxReach * maxReach) + 1.0D;
        for (Player p : client.level.players()) {
            if (p == client.player || !p.isAlive() || p.isSpectator() || p.isCreative()) continue;
            double dst = client.player.distanceToSqr(p);
            if (dst > (maxReach * maxReach)) continue;
            if (!verifyLos(client, p)) continue;
            if (dst < minDst) { minDst = dst; best = p; }
        }
        return best;
    }

    private static void executePipeline(Minecraft client, LivingEntity target) {
        double dist = client.player.distanceTo(target);
        boolean blocking = isBlocking(target);

        VELOCITY_HISTORY.offerLast(client.player.getDeltaMovement().horizontalDistance());
        if (VELOCITY_HISTORY.size() > HISTORY_CAP) VELOCITY_HISTORY.pollFirst();

        switch (currentShieldState) {
            case IDLE:
                if (blocking && autoAxe) {
                    int slot = findItem(client, Items.NETHERITE_AXE);
                    if (slot == -1) slot = findItem(client, Items.DIAMOND_AXE);
                    if (slot == -1) slot = findItem(client, Items.IRON_AXE);
                    if (slot != -1) {
                        selectSlot(client, slot);
                        shieldStunActiveSync = true;
                        currentShieldState = ShieldState.AXE_SWING_PREP;
                        actionStateCountdown = 1;
                    }
                } else if (autoHitDef && target.isUsingItem() && dist <= 3.5D) {
                    shieldStunActiveSync = true;
                    currentShieldState = ShieldState.AUTO_HIT_DEFENSE;
                } else {
                    shieldStunActiveSync = false;
                    currentShieldState = ShieldState.SPRINT_APPROACH;
                }
                break;
            case SPRINT_APPROACH:
                if (blocking) {
                    shieldStunActiveSync = false;
                    currentShieldState = ShieldState.IDLE;
                } else if (dist <= 3.0D) {
                    if (instantSwordSwap) {
                        int sSlot = findItem(client, Items.NETHERITE_SWORD);
                        if (sSlot == -1) sSlot = findItem(client, Items.DIAMOND_SWORD);
                        if (sSlot != -1) selectSlot(client, sSlot);
                    }
                    shieldStunActiveSync = false;
                    currentShieldState = ShieldState.SWORD_CRIT_FOLLOWUP;
                }
                break;
            case AXE_SWING_PREP:
                Vec3 center = target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D);
                RotationManager.smoothTo(client, center.add(secureRandom.nextDouble() * jitterFactor, secureRandom.nextDouble() * jitterFactor, secureRandom.nextDouble() * jitterFactor), 0.97F);
                if (dist <= 3.5D && shieldDisableCooldownTimer == 0) {
                    if (client.player.getAttackStrengthScale(0.0F) >= 0.80F) {
                        InteractionManager.simulateClickAttack(client);
                        if (doubleClickBurst) {
                            actionStateCountdown = microBurstDelay;
                            currentShieldState = ShieldState.EXECUTE_SWIGHT_STUN;
                        } else {
                            successiveStuns++;
                            sessionStunCount++;
                            lastStunEpoch = System.currentTimeMillis();
                            STUN_HISTORY.offerLast(lastStunEpoch);
                            shieldDisableCooldownTimer = 80;
                            shieldStunActiveSync = false;
                            currentShieldState = ShieldState.SWORD_CRIT_FOLLOWUP;
                        }
                    }
                }
                break;
            case EXECUTE_SWIGHT_STUN:
                InteractionManager.simulateClickAttack(client);
                successiveStuns++;
                sessionStunCount++;
                lastStunEpoch = System.currentTimeMillis();
                STUN_HISTORY.offerLast(lastStunEpoch);
                shieldDisableCooldownTimer = 80;
                if (instantSwordSwap) {
                    int sSlot = findItem(client, Items.NETHERITE_SWORD);
                    if (sSlot == -1) sSlot = findItem(client, Items.DIAMOND_SWORD);
                    if (sSlot != -1) selectSlot(client, sSlot);
                }
                shieldStunActiveSync = false;
                currentShieldState = ShieldState.SWORD_CRIT_FOLLOWUP;
                actionStateCountdown = 1;
                break;
            case SWORD_CRIT_FOLLOWUP:
                Vec3 sCenter = target.position().add(0.0D, target.getBbHeight() * 0.4D, 0.0D);
                RotationManager.smoothTo(client, sCenter.add(secureRandom.nextDouble() * jitterFactor, secureRandom.nextDouble() * jitterFactor, secureRandom.nextDouble() * jitterFactor), 0.98F);
                if (dist <= 3.5D && client.player.getAttackStrengthScale(0.0F) >= 0.85F) {
                    InteractionManager.simulateClickAttack(client);
                    actionStateCountdown = 2 + secureRandom.nextInt(2);
                    shieldStunActiveSync = false;
                    currentShieldState = ShieldState.IDLE;
                }
                break;
            case AUTO_HIT_DEFENSE:
                if (dist <= 3.5D && client.player.getAttackStrengthScale(0.0F) >= 0.70F) {
                    InteractionManager.simulateClickAttack(client);
                    actionStateCountdown = 2;
                }
                if (!target.isUsingItem()) {
                    shieldStunActiveSync = false;
                    currentShieldState = ShieldState.IDLE;
                }
                break;
        }
    }

    private static void selectSlot(Minecraft client, int slot) {
        if (client.player == null || slot < 0 || slot > 8) return;
        client.player.getInventory().setSelectedSlot(slot);
        if (client.options.keyHotbarSlots[slot] != null) {
            client.options.keyHotbarSlots[slot].setDown(true);
            client.options.keyHotbarSlots[slot].setDown(false);
        }
    }

    private static int findItem(Minecraft client, Item item) {
        if (client.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            if (client.player.getInventory().getItem(i).getItem() == item) return i;
        }
        return -1;
    }

    private static void updateRegistry() {
        SHIELD_REGISTRY.put("Ticks", globalTicks);
        SHIELD_REGISTRY.put("State", currentShieldState.name());
        SHIELD_REGISTRY.put("SuccessiveStuns", successiveStuns);
        SHIELD_REGISTRY.put("SessionStuns", sessionStunCount);
    }

    public static boolean verifySubsystemHealth() { return enabled && SUBSESSION_ID != null; }
    public static long getGlobalTicks() { return globalTicks; }
    public static UUID getSubsessionIdentity() { return SUBSESSION_ID; }
}