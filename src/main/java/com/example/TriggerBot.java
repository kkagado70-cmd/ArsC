package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.Deque;

public class TriggerBot extends ClientBase.Module {
    public static final String FILE_NAME = "TriggerBot.java";
    public static boolean enabled = true;
    public static boolean consistentCritsEnabled = true;
    private static final SecureRandom secureRandom = new SecureRandom();

    private static int reactionCountdownTicks = 0;
    private static int comboBufferTicks = 0;
    private static double attackReach = 4.5D;

    private static final Map<String, Object> TRIGGER_SEVEN_REGISTRY = new ConcurrentHashMap<>();
    private static final UUID SUBSESSION_UUID = UUID.randomUUID();
    private static final Deque<Long> ATTACK_INTERVAL_HISTORY = new ArrayDeque<>();
    private static final Deque<Integer> CLICK_DURATION_MEMORY = new ArrayDeque<>();
    private static final Deque<Double> ERROR_VECTOR_MEMORY = new ArrayDeque<>();
    private static final Deque<Float> ATTACK_STRENGTH_SAMPLE_DEQUE = new ArrayDeque<>();
    private static final Deque<Long> SESSION_TIMESTAMP_DEQUE = new ArrayDeque<>();
    private static final Deque<Double> FATIGUE_SAMPLE_DEQUE = new ArrayDeque<>();
    private static final Deque<Integer> REACTION_DELAY_SAMPLE_DEQUE = new ArrayDeque<>();
    private static final int HISTORY_MAX_CAPACITY = 8192;

    private static long totalTriggersFired = 0L;
    private static boolean adaptiveCritSyncActive = true;
    private static double attackStrengthThresholdNormal = 0.70D;
    private static double attackStrengthThresholdCombo = 0.45D;
    private static boolean humanReactionStochasticity = true;
    private static long subsessionEpochTracker = System.currentTimeMillis();
    private static boolean antiReplayShieldActive = true;
    private static int triggerAnomalyCounter = 0;
    private static boolean stealthProfileMode = true;
    private static int minReactionDelayTicks = 1;
    private static int maxReactionDelayTicks = 3;
    private static boolean packetOrderStrictSync = true;
    private static double verticalFallingTolerance = -0.04D;
    private static boolean lineOfSightValidation = true;
    private static int sessionAttackCounter = 0;
    private static boolean dynamicThresholdAdjustment = true;
    private static double stochasticVariance = 0.04D;
    private static int emergencyResetThreshold = 100;
    private static boolean combatSyncEnabled = true;
    private static boolean biologicalFatigueSim = true;
    private static double fatigueScalar = 0.001D;
    private static double currentFatigueLevel = 0.0D;
    private static boolean errorInjectionActive = true;
    private static double randomMissChance = 0.015D;
    private static boolean selfOptimizationActive = true;
    private static int autoCalibrationCounter = 0;

    private static String currentWeaponProfile = "sword";
    private static int ticksSinceLastReset = 0;
    private static boolean onlyCritMode = false;
    private static boolean noCritMode = false;
    private static boolean targetMobs = true;
    private static boolean targetAnimals = false;
    private static boolean targetPlayersOnly = false;
    private static boolean aggressiveMode = false;
    private static boolean defensiveMode = false;
    private static boolean antiSpamActive = true;
    private static int maxApsLimit = 8;
    private static long lastTriggerEpoch = 0L;
    private static boolean stealthMode = false;
    private static boolean preciseMode = false;
    private static boolean visualFeedbackHud = true;
    private static boolean debugLogging = false;
    private static boolean persistSettings = false;
    private static boolean turboMode = false;
    private static boolean teamKillBlock = true;
    private static boolean focusAimMode = true;
    private static boolean freeAimMode = false;
    private static int pingDelayBonus = 0;
    private static int inactivityCounter = 0;
    private static boolean trainingRecordMode = false;
    private static boolean antibotDetection = true;

    private static double sessionMetricAlpha = 0.5D;
    private static double sessionMetricBeta = 0.5D;
    private static double sessionMetricGamma = 0.5D;
    private static double sessionMetricDelta = 0.5D;
    private static double sessionMetricEpsilon = 0.5D;
    private static double sessionMetricZeta = 0.5D;
    private static double sessionMetricEta = 0.5D;
    private static double sessionMetricTheta = 0.5D;
    private static double sessionMetricIota = 0.5D;
    private static double sessionMetricKappa = 0.5D;

    private static final Map<String, Double> WEAPON_THRESHOLDS = new ConcurrentHashMap<>();
    private static final Map<String, Double> WEAPON_REACHES = new ConcurrentHashMap<>();
    private static final Map<String, Integer> WEAPON_REACTIONS = new ConcurrentHashMap<>();
    private static final Map<String, Integer> WEAPON_HIT_COUNTS = new ConcurrentHashMap<>();
    private static final Map<String, Integer> WEAPON_MISS_COUNTS = new ConcurrentHashMap<>();

    static {
        initializeTriggerRegistry();
        initializeWeaponProfiles();
    }

    private static void initializeTriggerRegistry() {
        TRIGGER_SEVEN_REGISTRY.put("SubsessionUUID", SUBSESSION_UUID);
        TRIGGER_SEVEN_REGISTRY.put("Profile", "Swight-TriggerBot-700Lines");
        TRIGGER_SEVEN_REGISTRY.put("BypassEngine", "Instant-Click-Enterprise");
        TRIGGER_SEVEN_REGISTRY.put("InitializationEpoch", subsessionEpochTracker);
        TRIGGER_SEVEN_REGISTRY.put("TotalFires", totalTriggersFired);
        TRIGGER_SEVEN_REGISTRY.put("AdaptiveCritSync", adaptiveCritSyncActive);
        TRIGGER_SEVEN_REGISTRY.put("CombatSync", combatSyncEnabled);
        TRIGGER_SEVEN_REGISTRY.put("AlphaMetric", sessionMetricAlpha);
        TRIGGER_SEVEN_REGISTRY.put("BetaMetric", sessionMetricBeta);
    }

    private static void initializeWeaponProfiles() {
        WEAPON_THRESHOLDS.put("sword", 0.70D);
        WEAPON_THRESHOLDS.put("axe", 0.75D);
        WEAPON_THRESHOLDS.put("trident", 0.68D);
        WEAPON_THRESHOLDS.put("mace", 0.65D);

        WEAPON_REACHES.put("sword", 3.0D);
        WEAPON_REACHES.put("axe", 3.5D);
        WEAPON_REACHES.put("trident", 4.0D);
        WEAPON_REACHES.put("mace", 4.5D);

        WEAPON_REACTIONS.put("sword", 1);
        WEAPON_REACTIONS.put("axe", 2);
        WEAPON_REACTIONS.put("trident", 1);
        WEAPON_REACTIONS.put("mace", 2);

        WEAPON_HIT_COUNTS.put("sword", 0);
        WEAPON_HIT_COUNTS.put("axe", 0);
        WEAPON_HIT_COUNTS.put("trident", 0);
        WEAPON_HIT_COUNTS.put("mace", 0);

        WEAPON_MISS_COUNTS.put("sword", 0);
        WEAPON_MISS_COUNTS.put("axe", 0);
        WEAPON_MISS_COUNTS.put("trident", 0);
        WEAPON_MISS_COUNTS.put("mace", 0);
    }

    public TriggerBot() {
        super("TriggerBot");
        TriggerBot.enabled = true;
        initializeTriggerRegistry();
        initializeWeaponProfiles();
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void toggle() {
        enabled = !enabled;
        super.enabled = enabled;
        hardResetTriggerSubsystem();
    }

    private static void hardResetTriggerSubsystem() {
        reactionCountdownTicks = 0;
        comboBufferTicks = 0;
        triggerAnomalyCounter = 0;
        sessionAttackCounter = 0;
        currentFatigueLevel = 0.0D;
        autoCalibrationCounter = 0;
        ticksSinceLastReset = 0;
        inactivityCounter = 0;
        lastTriggerEpoch = 0L;

        ATTACK_INTERVAL_HISTORY.clear();
        CLICK_DURATION_MEMORY.clear();
        ERROR_VECTOR_MEMORY.clear();
        ATTACK_STRENGTH_SAMPLE_DEQUE.clear();
        SESSION_TIMESTAMP_DEQUE.clear();
        FATIGUE_SAMPLE_DEQUE.clear();
        REACTION_DELAY_SAMPLE_DEQUE.clear();
        purgeTriggerRegistry();
        initializeTriggerRegistry();
    }

    private static void purgeTriggerRegistry() {
        TRIGGER_SEVEN_REGISTRY.clear();
    }

    @Override
    public void tick(Minecraft clientRef) {
        onTick(clientRef);
    }

    private static boolean isHoldingWeapon(Minecraft clientRef) {
        if (clientRef.player == null) return false;
        ItemStack stack = clientRef.player.getMainHandItem();
        if (stack.isEmpty()) return false;
        String name = stack.getItem().getDescriptionId().toLowerCase();
        return name.contains("sword") || name.contains("axe") || name.contains("trident") || name.contains("mace");
    }

    private static String resolveWeaponKey(Minecraft clientRef) {
        if (clientRef.player == null) return "sword";
        ItemStack stack = clientRef.player.getMainHandItem();
        if (stack.isEmpty()) return "sword";
        String name = stack.getItem().getDescriptionId().toLowerCase();
        if (name.contains("axe")) return "axe";
        if (name.contains("mace")) return "mace";
        if (name.contains("trident")) return "trident";
        return "sword";
    }

    private static boolean hasLineOfSight(Minecraft clientRef, Entity target) {
        if (clientRef.player == null || target == null) return false;
        Vec3 start = clientRef.player.getEyePosition();
        Vec3 end = target.getEyePosition();
        BlockHitResult hit = clientRef.level.clip(
            new ClipContext(
                start, 
                end, 
                ClipContext.Block.COLLIDER, 
                ClipContext.Fluid.NONE, 
                clientRef.player
            )
        );
        return hit.getType() == HitResult.Type.MISS;
    }

    private static boolean isAlly(Minecraft clientRef, Entity target) {
        if (!teamKillBlock || !(target instanceof LivingEntity living)) return false;
        if (clientRef.player.getTeam() != null && living.getTeam() != null) {
            return clientRef.player.getTeam().isAlliedTo(living.getTeam());
        }
        return false;
    }

    private static boolean isAntibotCandidate(Entity target) {
        if (!antibotDetection || target == null) return false;
        return target.isInvisible() && target.getDeltaMovement().horizontalDistanceSqr() == 0.0D;
    }

    public static void onTick(Minecraft clientRef) {
        if (!enabled || clientRef.player == null || clientRef.level == null) return;
        if (!clientRef.player.isAlive()) return;
        if (!isHoldingWeapon(clientRef)) {
            comboBufferTicks = 0;
            return;
        }
        if (ShieldBreaker.isShieldStunStatic() || ShieldBreaker.isShieldStunActive()) {
            return;
        }

        HitResult hit = clientRef.hitResult;
        if (hit == null || hit.getType() != HitResult.Type.ENTITY) {
            inactivityCounter++;
            if (inactivityCounter > 200) currentFatigueLevel = 0.0D;
            return;
        }

        inactivityCounter = 0;
        Entity target = ((EntityHitResult) hit).getEntity();
        if (!(target instanceof LivingEntity living) || !living.isAlive() || living == clientRef.player) {
            return;
        }
        if (living instanceof Player player && (player.isSpectator() || player.isCreative())) {
            return;
        }
        if (isAlly(clientRef, living) || isAntibotCandidate(living)) {
            return;
        }

        if (targetPlayersOnly && !(living instanceof Player)) {
            return;
        }
        if (!targetMobs && !targetPlayersOnly && living instanceof net.minecraft.world.entity.monster.Monster) {
            return;
        }
        if (!targetAnimals && living instanceof net.minecraft.world.entity.animal.Animal) {
            return;
        }

        ticksSinceLastReset++;
        currentWeaponProfile = resolveWeaponKey(clientRef);
        attackReach = WEAPON_REACHES.getOrDefault(currentWeaponProfile, 4.5D);

        autoCalibrationCounter++;
        if (autoCalibrationCounter >= 250) {
            autoCalibrationCounter = 0;
            executeAutoCalibrationRoutine();
        }

        if (clientRef.player.hurtTime > 0) {
            comboBufferTicks = 15;
            defensiveMode = clientRef.player.getHealth() <= 6.0F;
            aggressiveMode = clientRef.player.getHealth() > 14.0F;
        } else if (comboBufferTicks > 0) {
            comboBufferTicks--;
        } else {
            defensiveMode = clientRef.player.getHealth() <= 6.0F;
            aggressiveMode = clientRef.player.getHealth() > 14.0F;
        }

        double reachSqr = attackReach * attackReach;
        if (clientRef.player.distanceToSqr(living) > reachSqr) {
            return;
        }

        if (lineOfSightValidation && !hasLineOfSight(clientRef, living)) {
            return;
        }

        if (shieldDetectionActive(living)) {
            return;
        }

        double distance = Math.sqrt(clientRef.player.distanceToSqr(living));
        int baseReaction = WEAPON_REACTIONS.getOrDefault(currentWeaponProfile, 1);
        int distanceDelay = distance > 5.0D ? (2 + secureRandom.nextInt(3)) : (minReactionDelayTicks + secureRandom.nextInt(maxReactionDelayTicks));
        
        long latency = 50L;
        if (clientRef.getConnection() != null) {
            try {
                latency = clientRef.getConnection().getLatency();
            } catch (Exception ignored) {}
        }
        pingDelayBonus = latency > 100L ? 1 : 0;

        int requiredReactionDelay = baseReaction + distanceDelay + pingDelayBonus;

        if (reactionCountdownTicks < requiredReactionDelay && comboBufferTicks == 0) {
            reactionCountdownTicks++;
            return;
        }
        reactionCountdownTicks = 0;

        if (onlyCritMode) {
            boolean isFalling = clientRef.player.getDeltaMovement().y < verticalFallingTolerance;
            if (clientRef.player.onGround() || !isFalling) return;
        } else if (noCritMode) {
            if (!clientRef.player.onGround()) return;
        } else if (consistentCritsEnabled && !clientRef.player.onGround()) {
            boolean isFalling = clientRef.player.getDeltaMovement().y < verticalFallingTolerance;
            if (!isFalling && comboBufferTicks == 0) {
                return;
            }
            if (secureRandom.nextDouble() < 0.05D) {
                recordMissForWeapon(currentWeaponProfile);
                return;
            }
        }

        double baseThreshold = WEAPON_THRESHOLDS.getOrDefault(currentWeaponProfile, 0.70D);
        if (aggressiveMode) baseThreshold -= 0.10D;
        if (defensiveMode) baseThreshold += 0.05D;

        double activeThreshold = (comboBufferTicks > 0 ? attackStrengthThresholdCombo : baseThreshold) + (currentFatigueLevel * 0.05D);
        float currentAttackScale = clientRef.player.getAttackStrengthScale(0.0F);

        pushAttackStrengthSample(currentAttackScale);

        if (turboMode || currentAttackScale >= activeThreshold) {
            if (!turboMode && errorInjectionActive && secureRandom.nextDouble() < randomMissChance) {
                recordMissForWeapon(currentWeaponProfile);
                return;
            }

            if (antiSpamActive) {
                long now = System.currentTimeMillis();
                if (now - lastTriggerEpoch < (1000L / maxApsLimit)) {
                    return;
                }
                lastTriggerEpoch = now;
            }

            totalTriggersFired++;
            sessionAttackCounter++;
            currentFatigueLevel = Math.min(1.0D, currentFatigueLevel + fatigueScalar);
            long timestamp = System.currentTimeMillis();
            pushAttackInterval(timestamp);
            pushSessionTimestamp(timestamp);
            pushFatigueSample(currentFatigueLevel);
            pushReactionDelaySample(requiredReactionDelay);
            recordHitForWeapon(currentWeaponProfile);

            InteractionManager.simulateClickAttack(clientRef);

            float recoilYaw = (float) ((secureRandom.nextDouble() - 0.5) * 0.3D);
            float recoilPitch = (float) ((secureRandom.nextDouble() - 0.5) * 0.2D);
            clientRef.player.setYRot(clientRef.player.getYRot() + recoilYaw);
            clientRef.player.setXRot(Mth.clamp(clientRef.player.getXRot() + recoilPitch, -89.0F, 89.0F));
        } else {
            if (comboBufferTicks == 0) {
                reactionCountdownTicks = 0;
            }
        }
        updateRegistryState();
        executeSubsystemSanitation();
    }

    private static boolean shieldDetectionActive(LivingEntity target) {
        if (target == null) return false;
        return target.isUsingItem() && target.getUseItem().getItem() == Items.SHIELD;
    }

    private static void recordHitForWeapon(String weapon) {
        WEAPON_HIT_COUNTS.put(weapon, WEAPON_HIT_COUNTS.getOrDefault(weapon, 0) + 1);
    }

    private static void recordMissForWeapon(String weapon) {
        WEAPON_MISS_COUNTS.put(weapon, WEAPON_MISS_COUNTS.getOrDefault(weapon, 0) + 1);
    }

    private static void pushAttackInterval(long timestamp) {
        if (ATTACK_INTERVAL_HISTORY.size() >= HISTORY_MAX_CAPACITY) {
            ATTACK_INTERVAL_HISTORY.pollFirst();
        }
        ATTACK_INTERVAL_HISTORY.offerLast(timestamp);
    }

    private static void pushAttackStrengthSample(float scale) {
        if (ATTACK_STRENGTH_SAMPLE_DEQUE.size() >= HISTORY_MAX_CAPACITY) {
            ATTACK_STRENGTH_SAMPLE_DEQUE.pollFirst();
        }
        ATTACK_STRENGTH_SAMPLE_DEQUE.offerLast(scale);
    }

    private static void pushSessionTimestamp(long timestamp) {
        if (SESSION_TIMESTAMP_DEQUE.size() >= HISTORY_MAX_CAPACITY) {
            SESSION_TIMESTAMP_DEQUE.pollFirst();
        }
        SESSION_TIMESTAMP_DEQUE.offerLast(timestamp);
    }

    private static void pushFatigueSample(double fatigue) {
        if (FATIGUE_SAMPLE_DEQUE.size() >= HISTORY_MAX_CAPACITY) {
            FATIGUE_SAMPLE_DEQUE.pollFirst();
        }
        FATIGUE_SAMPLE_DEQUE.offerLast(fatigue);
    }

    private static void pushReactionDelaySample(int delay) {
        if (REACTION_DELAY_SAMPLE_DEQUE.size() >= HISTORY_MAX_CAPACITY) {
            REACTION_DELAY_SAMPLE_DEQUE.pollFirst();
        }
        REACTION_DELAY_SAMPLE_DEQUE.offerLast(delay);
    }

    private static void executeAutoCalibrationRoutine() {
        attackStrengthThresholdNormal = 0.70D + (secureRandom.nextDouble() - 0.5) * 0.04D;
        currentFatigueLevel = Math.max(0.0D, currentFatigueLevel - 0.1D);
    }

    private static void updateRegistryState() {
        TRIGGER_SEVEN_REGISTRY.put("TotalFires", totalTriggersFired);
        TRIGGER_SEVEN_REGISTRY.put("SessionFires", sessionAttackCounter);
        TRIGGER_SEVEN_REGISTRY.put("FatigueLevel", currentFatigueLevel);
        TRIGGER_SEVEN_REGISTRY.put("HistoryQueueSize", ATTACK_INTERVAL_HISTORY.size());
        TRIGGER_SEVEN_REGISTRY.put("ActiveProfile", currentWeaponProfile);
    }

    private static void executeSubsystemSanitation() {
        if (totalTriggersFired > 50000000L) {
            totalTriggersFired = 0L;
        }
        if (TRIGGER_SEVEN_REGISTRY.size() > 250) {
            TRIGGER_SEVEN_REGISTRY.clear();
            initializeTriggerRegistry();
        }
    }

    public static boolean verifyTriggerSubsystemHealth() {
        return enabled && SUBSESSION_UUID != null;
    }

    public static long getTotalTriggersFired() {
        return totalTriggersFired;
    }

    public static void performBaselineCalibration() {
        totalTriggersFired = 0L;
        sessionAttackCounter = 0;
        reactionCountdownTicks = 0;
        comboBufferTicks = 0;
        triggerAnomalyCounter = 0;
        currentFatigueLevel = 0.0D;
        ATTACK_INTERVAL_HISTORY.clear();
        CLICK_DURATION_MEMORY.clear();
        ERROR_VECTOR_MEMORY.clear();
        ATTACK_STRENGTH_SAMPLE_DEQUE.clear();
        SESSION_TIMESTAMP_DEQUE.clear();
        FATIGUE_SAMPLE_DEQUE.clear();
        REACTION_DELAY_SAMPLE_DEQUE.clear();
    }

    public static void executeExtendedDiagnosticFlush() {
        executeSubsystemSanitation();
        if (ATTACK_INTERVAL_HISTORY.size() > HISTORY_MAX_CAPACITY) {
            ATTACK_INTERVAL_HISTORY.clear();
        }
        if (CLICK_DURATION_MEMORY.size() > HISTORY_MAX_CAPACITY) {
            CLICK_DURATION_MEMORY.clear();
        }
        if (ERROR_VECTOR_MEMORY.size() > HISTORY_MAX_CAPACITY) {
            ERROR_VECTOR_MEMORY.clear();
        }
        if (ATTACK_STRENGTH_SAMPLE_DEQUE.size() > HISTORY_MAX_CAPACITY) {
            ATTACK_STRENGTH_SAMPLE_DEQUE.clear();
        }
        if (SESSION_TIMESTAMP_DEQUE.size() > HISTORY_MAX_CAPACITY) {
            SESSION_TIMESTAMP_DEQUE.clear();
        }
        if (FATIGUE_SAMPLE_DEQUE.size() > HISTORY_MAX_CAPACITY) {
            FATIGUE_SAMPLE_DEQUE.clear();
        }
        if (REACTION_DELAY_SAMPLE_DEQUE.size() > HISTORY_MAX_CAPACITY) {
            REACTION_DELAY_SAMPLE_DEQUE.clear();
        }
    }

    public static UUID getSubsessionIdentity() {
        return SUBSESSION_UUID;
    }

    public static void setAttackReach(double reach) {
        attackReach = reach;
        TRIGGER_SEVEN_REGISTRY.put("AttackReach", attackReach);
    }

    public static double getAttackReach() {
        return attackReach;
    }

    public static void setConsistentCritsEnabled(boolean state) {
        consistentCritsEnabled = state;
        TRIGGER_SEVEN_REGISTRY.put("ConsistentCrits", consistentCritsEnabled);
    }

    public static boolean isConsistentCritsEnabled() {
        return consistentCritsEnabled;
    }

    public static void setRandomMissChance(double chance) {
        randomMissChance = chance;
        TRIGGER_SEVEN_REGISTRY.put("MissChance", randomMissChance);
    }

    public static double getRandomMissChance() {
        return randomMissChance;
    }

    public static int getMinReactionDelayTicks() {
        return minReactionDelayTicks;
    }

    public static void setMinReactionDelayTicks(int ticks) {
        minReactionDelayTicks = Math.max(0, ticks);
        TRIGGER_SEVEN_REGISTRY.put("MinReactionDelay", minReactionDelayTicks);
    }

    public static int getMaxReactionDelayTicks() {
        return maxReactionDelayTicks;
    }

    public static void setMaxReactionDelayTicks(int ticks) {
        maxReactionDelayTicks = Math.max(minReactionDelayTicks, ticks);
        TRIGGER_SEVEN_REGISTRY.put("MaxReactionDelay", maxReactionDelayTicks);
    }

    public static double getAttackStrengthThresholdNormal() {
        return attackStrengthThresholdNormal;
    }

    public static void setAttackStrengthThresholdNormal(double threshold) {
        attackStrengthThresholdNormal = threshold;
        TRIGGER_SEVEN_REGISTRY.put("ThresholdNormal", attackStrengthThresholdNormal);
    }

    public static double getAttackStrengthThresholdCombo() {
        return attackStrengthThresholdCombo;
    }

    public static void setAttackStrengthThresholdCombo(double threshold) {
        attackStrengthThresholdCombo = threshold;
        TRIGGER_SEVEN_REGISTRY.put("ThresholdCombo", attackStrengthThresholdCombo);
    }

    public static double getCurrentFatigueLevel() {
        return currentFatigueLevel;
    }

    public static void setCurrentFatigueLevel(double fatigue) {
        currentFatigueLevel = fatigue;
        TRIGGER_SEVEN_REGISTRY.put("FatigueLevel", currentFatigueLevel);
    }

    public static int getSessionAttackCounter() {
        return sessionAttackCounter;
    }

    public static void resetSessionAttackCounter() {
        sessionAttackCounter = 0;
        TRIGGER_SEVEN_REGISTRY.put("SessionFires", sessionAttackCounter);
    }

    public static boolean isOnlyCritMode() {
        return onlyCritMode;
    }

    public static void setOnlyCritMode(boolean mode) {
        onlyCritMode = mode;
        TRIGGER_SEVEN_REGISTRY.put("OnlyCritMode", onlyCritMode);
    }

    public static boolean isNoCritMode() {
        return noCritMode;
    }

    public static void setNoCritMode(boolean mode) {
        noCritMode = mode;
        TRIGGER_SEVEN_REGISTRY.put("NoCritMode", noCritMode);
    }

    public static boolean isTargetMobs() {
        return targetMobs;
    }

    public static void setTargetMobs(boolean target) {
        targetMobs = target;
        TRIGGER_SEVEN_REGISTRY.put("TargetMobs", targetMobs);
    }

    public static boolean isTargetAnimals() {
        return targetAnimals;
    }

    public static void setTargetAnimals(boolean target) {
        targetAnimals = target;
        TRIGGER_SEVEN_REGISTRY.put("TargetAnimals", targetAnimals);
    }

    public static boolean isTargetPlayersOnly() {
        return targetPlayersOnly;
    }

    public static void setTargetPlayersOnly(boolean target) {
        targetPlayersOnly = target;
        TRIGGER_SEVEN_REGISTRY.put("TargetPlayersOnly", targetPlayersOnly);
    }

    public static boolean isAggressiveMode() {
        return aggressiveMode;
    }

    public static void setAggressiveMode(boolean mode) {
        aggressiveMode = mode;
        TRIGGER_SEVEN_REGISTRY.put("AggressiveMode", aggressiveMode);
    }

    public static boolean isDefensiveMode() {
        return defensiveMode;
    }

    public static void setDefensiveMode(boolean mode) {
        defensiveMode = mode;
        TRIGGER_SEVEN_REGISTRY.put("DefensiveMode", defensiveMode);
    }

    public static boolean isAntiSpamActive() {
        return antiSpamActive;
    }

    public static void setAntiSpamActive(boolean active) {
        antiSpamActive = active;
        TRIGGER_SEVEN_REGISTRY.put("AntiSpamActive", antiSpamActive);
    }

    public static int getMaxApsLimit() {
        return maxApsLimit;
    }

    public static void setMaxApsLimit(int limit) {
        maxApsLimit = Math.max(1, limit);
        TRIGGER_SEVEN_REGISTRY.put("MaxApsLimit", maxApsLimit);
    }

    public static boolean isTurboMode() {
        return turboMode;
    }

    public static void setTurboMode(boolean mode) {
        turboMode = mode;
        TRIGGER_SEVEN_REGISTRY.put("TurboMode", turboMode);
    }

    public static boolean isTeamKillBlock() {
        return teamKillBlock;
    }

    public static void setTeamKillBlock(boolean block) {
        teamKillBlock = block;
        TRIGGER_SEVEN_REGISTRY.put("TeamKillBlock", teamKillBlock);
    }

    public static int getHistoryCapacity() {
        return HISTORY_MAX_CAPACITY;
    }

    public static void clearAllHistoryQueues() {
        ATTACK_INTERVAL_HISTORY.clear();
        CLICK_DURATION_MEMORY.clear();
        ERROR_VECTOR_MEMORY.clear();
        ATTACK_STRENGTH_SAMPLE_DEQUE.clear();
        SESSION_TIMESTAMP_DEQUE.clear();
        FATIGUE_SAMPLE_DEQUE.clear();
        REACTION_DELAY_SAMPLE_DEQUE.clear();
    }
}
      