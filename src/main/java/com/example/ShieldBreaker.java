package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
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

    private static final Map<String, Object> SHIELD_ENTERPRISE_REGISTRY = new ConcurrentHashMap<>();
    private static final UUID SUBSESSION_IDENTITY = UUID.randomUUID();
    private static final Deque<Long> STUN_HISTORY = new ArrayDeque<>();
    private static final Deque<Double> VELOCITY_HISTORY = new ArrayDeque<>();
    private static final Deque<Long> RECOVERY_MEMORY = new ArrayDeque<>();
    private static final int HISTORY_MAX_CAPACITY = 2048;

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
    private static boolean shieldStunActiveSync = false;
    private static boolean biologicalFatigueSim = true;
    private static double fatigueScalar = 0.001D;
    private static double currentFatigueLevel = 0.0D;
    private static boolean errorInjectionActive = true;
    private static double randomMissChance = 0.01D;
    private static boolean selfOptimizationActive = true;
    private static int autoCalibrationCounter = 0;

    static {
        initializeRegistry();
    }

    private static void initializeRegistry() {
        SHIELD_ENTERPRISE_REGISTRY.put("SubsessionUUID", SUBSESSION_IDENTITY);
        SHIELD_ENTERPRISE_REGISTRY.put("Profile", "Swight-Tier1-ShieldBreaker-FullEnterprise");
        SHIELD_ENTERPRISE_REGISTRY.put("SwightStun", swightStun);
        SHIELD_ENTERPRISE_REGISTRY.put("DoubleClickBurst", doubleClickBurst);
        SHIELD_ENTERPRISE_REGISTRY.put("AutoAxe", autoAxe);
        SHIELD_ENTERPRISE_REGISTRY.put("SuccessiveStuns", successiveStuns);
    }

    public ShieldBreaker() {
        super("ShieldBreaker");
        ShieldBreaker.enabled = true;
        initializeRegistry();
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

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
        momentumReset = false;
        shieldStunActiveSync = false;
        autoCalibrationCounter = 0;
        STUN_HISTORY.clear();
        VELOCITY_HISTORY.clear();
        RECOVERY_MEMORY.clear();
        SHIELD_ENTERPRISE_REGISTRY.clear();
        initializeRegistry();
    }

    @Override
    public void tick(Minecraft clientRef) {
        onTick(clientRef);
    }

    private static boolean validateWeaponContext(Minecraft clientRef) {
        if (clientRef.player == null) return false;
        ItemStack stack = clientRef.player.getMainHandItem();
        if (stack.isEmpty()) return false;
        String name = stack.getItem().getDescriptionId().toLowerCase();
        return name.contains("sword") || name.contains("axe") || name.contains("trident") || name.contains("mace");
    }

    private static boolean isBlocking(LivingEntity target) {
        if (target == null) return false;
        return target.isUsingItem() && target.getUseItem().getItem() == Items.SHIELD;
    }

    private static boolean verifyLos(Minecraft clientRef, Entity target) {
        if (clientRef.player == null || target == null) return false;
        Vec3 start = clientRef.player.getEyePosition();
        Vec3 end = target.getEyePosition();
        BlockHitResult hit = clientRef.level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, clientRef.player));
        return hit.getType() == HitResult.Type.MISS;
    }

    public static boolean isShieldStunActive() {
        return shieldStunActiveSync;
    }

    public static void onTick(Minecraft clientRef) {
        if (!enabled || clientRef.player == null || clientRef.level == null) return;
        if (!clientRef.player.isAlive()) return;
        if (!validateWeaponContext(clientRef)) {
            shieldStunActiveSync = false;
            currentShieldState = ShieldState.IDLE;
            return;
        }

        globalTicks++;
        autoCalibrationCounter++;

        if (autoCalibrationCounter >= 250) {
            autoCalibrationCounter = 0;
            executeAutoCalibrationRoutine();
        }

        if (shieldDisableCooldownTimer > 0) shieldDisableCooldownTimer--;
        if (actionStateCountdown > 0) {
            actionStateCountdown--;
            return;
        }

        lockedShieldTarget = null;
        double minDst = (maxReach * maxReach) + 1.0D;
        for (Entity entity : clientRef.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || living == clientRef.player || !living.isAlive()) continue;
            if (living instanceof Player player && (player.isSpectator() || player.isCreative())) continue;
            double dst = clientRef.player.distanceToSqr(living);
            if (dst > (maxReach * maxReach)) continue;
            if (!verifyLos(clientRef, living)) continue;
            if (dst < minDst) {
                minDst = dst;
                lockedShieldTarget = living;
            }
        }

        if (lockedShieldTarget == null) {
            shieldStunActiveSync = false;
            currentShieldState = ShieldState.IDLE;
            return;
        }

        double dist = clientRef.player.distanceTo(lockedShieldTarget);
        boolean blocking = isBlocking(lockedShieldTarget);

        VELOCITY_HISTORY.offerLast(clientRef.player.getDeltaMovement().horizontalDistance());
        if (VELOCITY_HISTORY.size() > HISTORY_MAX_CAPACITY) {
            VELOCITY_HISTORY.pollFirst();
        }

        switch (currentShieldState) {
            case IDLE:
                if (blocking && autoAxe) {
                    int slot = findItem(clientRef, Items.NETHERITE_AXE);
                    if (slot == -1) slot = findItem(clientRef, Items.DIAMOND_AXE);
                    if (slot == -1) slot = findItem(clientRef, Items.IRON_AXE);
                    if (slot != -1) {
                        selectSlot(clientRef, slot);
                        shieldStunActiveSync = true;
                        currentShieldState = ShieldState.AXE_SWING_PREP;
                        actionStateCountdown = 1;
                    }
                } else if (autoHitDef && lockedShieldTarget.isUsingItem() && dist <= 3.5D) {
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
                        int sSlot = findItem(clientRef, Items.NETHERITE_SWORD);
                        if (sSlot == -1) sSlot = findItem(clientRef, Items.DIAMOND_SWORD);
                        if (sSlot != -1) selectSlot(clientRef, sSlot);
                    }
                    shieldStunActiveSync = false;
                    currentShieldState = ShieldState.SWORD_CRIT_FOLLOWUP;
                }
                break;
            case AXE_SWING_PREP:
                Vec3 center = lockedShieldTarget.position().add(0.0D, lockedShieldTarget.getBbHeight() * 0.5D, 0.0D);
                RotationManager.smoothTo(clientRef, center.add(secureRandom.nextDouble() * jitterFactor, secureRandom.nextDouble() * jitterFactor, secureRandom.nextDouble() * jitterFactor), 0.97F);
                if (dist <= 3.5D && shieldDisableCooldownTimer == 0) {
                    if (clientRef.player.getAttackStrengthScale(0.0F) >= (0.80D + currentFatigueLevel)) {
                        if (errorInjectionActive && secureRandom.nextDouble() < randomMissChance) return;
                        InteractionManager.simulateClickAttack(clientRef);
                        if (doubleClickBurst) {
                            actionStateCountdown = microBurstDelay;
                            currentShieldState = ShieldState.EXECUTE_SWIGHT_STUN;
                        } else {
                            successiveStuns++;
                            sessionStunCount++;
                            currentFatigueLevel = Math.min(1.0D, currentFatigueLevel + fatigueScalar);
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
                InteractionManager.simulateClickAttack(clientRef);
                successiveStuns++;
                sessionStunCount++;
                currentFatigueLevel = Math.min(1.0D, currentFatigueLevel + fatigueScalar);
                lastStunEpoch = System.currentTimeMillis();
                STUN_HISTORY.offerLast(lastStunEpoch);
                shieldDisableCooldownTimer = 80;
                if (instantSwordSwap) {
                    int sSlot = findItem(clientRef, Items.NETHERITE_SWORD);
                    if (sSlot == -1) sSlot = findItem(clientRef, Items.DIAMOND_SWORD);
                    if (sSlot != -1) selectSlot(clientRef, sSlot);
                }
                shieldStunActiveSync = false;
                currentShieldState = ShieldState.SWORD_CRIT_FOLLOWUP;
                actionStateCountdown = 1;
                break;
            case SWORD_CRIT_FOLLOWUP:
                Vec3 sCenter = lockedShieldTarget.position().add(0.0D, lockedShieldTarget.getBbHeight() * 0.4D, 0.0D);
                RotationManager.smoothTo(clientRef, sCenter.add(secureRandom.nextDouble() * jitterFactor, secureRandom.nextDouble() * jitterFactor, secureRandom.nextDouble() * jitterFactor), 0.98F);
                if (dist <= 3.5D && clientRef.player.getAttackStrengthScale(0.0F) >= 0.85F) {
                    InteractionManager.simulateClickAttack(clientRef);
                    actionStateCountdown = 2 + secureRandom.nextInt(2);
                    shieldStunActiveSync = false;
                    currentShieldState = ShieldState.IDLE;
                }
                break;
            case AUTO_HIT_DEFENSE:
                if (dist <= 3.5D && clientRef.player.getAttackStrengthScale(0.0F) >= 0.70F) {
                    InteractionManager.simulateClickAttack(clientRef);
                    actionStateCountdown = 2;
                }
                if (!lockedShieldTarget.isUsingItem()) {
                    shieldStunActiveSync = false;
                    currentShieldState = ShieldState.IDLE;
                }
                break;
        }
        updateRegistryState();
    }

    private static void selectSlot(Minecraft clientRef, int slot) {
        if (clientRef.player == null || slot < 0 || slot > 8) return;
        clientRef.player.getInventory().setSelectedSlot(slot);
        if (clientRef.options != null && clientRef.options.keyHotbarSlots[slot] != null) {
            clientRef.options.keyHotbarSlots[slot].setDown(true);
            clientRef.options.keyHotbarSlots[slot].setDown(false);
        }
    }

    private static int findItem(Minecraft clientRef, Item item) {
        if (clientRef.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            if (clientRef.player.getInventory().getItem(i).getItem() == item) return i;
        }
        return -1;
    }

    private static void executeAutoCalibrationRoutine() {
        maxReach = 5.0D + (secureRandom.nextDouble() - 0.5) * 0.1D;
        currentFatigueLevel = Math.max(0.0D, currentFatigueLevel - 0.1D);
    }

    private static void updateRegistryState() {
        SHIELD_ENTERPRISE_REGISTRY.put("Ticks", globalTicks);
        SHIELD_ENTERPRISE_REGISTRY.put("State", currentShieldState.name());
        SHIELD_ENTERPRISE_REGISTRY.put("SuccessiveStuns", successiveStuns);
        SHIELD_ENTERPRISE_REGISTRY.put("SessionStuns", sessionStunCount);
        SHIELD_ENTERPRISE_REGISTRY.put("FatigueLevel", currentFatigueLevel);
    }

    public static boolean verifySubsystemHealth() {
        return enabled && SUBSESSION_IDENTITY != null;
    }

    public static UUID getSubsessionIdentity() {
        return SUBSESSION_IDENTITY;
    }
}