package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TriggerBot {

    public static final String FILE_NAME = "TriggerBot.java";
    public static boolean enabled = true;
    public static boolean consistentCritsEnabled = true;

    private static final SecureRandom secureRandom = new SecureRandom();
    private static final Map<String, Object> TRIGGER_SEVEN_REGISTRY = new ConcurrentHashMap<>();
    private static final UUID SUBSESSION_UUID = UUID.randomUUID();

    private static final Deque<Long>    ATTACK_INTERVAL_HISTORY     = new ArrayDeque<>();
    private static final Deque<Integer> CLICK_DURATION_MEMORY       = new ArrayDeque<>();
    private static final Deque<Double>  ERROR_VECTOR_MEMORY         = new ArrayDeque<>();
    private static final Deque<Float>   ATTACK_STRENGTH_SAMPLE_DEQUE = new ArrayDeque<>();
    private static final Deque<Long>    SESSION_TIMESTAMP_DEQUE     = new ArrayDeque<>();
    private static final Deque<Double>  FATIGUE_SAMPLE_DEQUE        = new ArrayDeque<>();
    private static final Deque<Integer> REACTION_DELAY_SAMPLE_DEQUE = new ArrayDeque<>();
    private static final Deque<Double>  VELOCITY_DELTA_DEQUE        = new ArrayDeque<>();
    private static final Deque<Double>  AIMING_ERROR_DEQUE          = new ArrayDeque<>();
    private static final int HISTORY_MAX_CAPACITY = 8192;

    private static int    reactionCountdownTicks          = 0;
    private static int    comboBufferTicks                = 0;
    private static double attackReach                     = 4.5D;
    private static long   totalTriggersFired              = 0L;
    private static boolean adaptiveCritSyncActive         = true;
    private static double attackStrengthThresholdNormal   = 0.90D;
    private static double attackStrengthThresholdCombo    = 0.70D;
    private static boolean humanReactionStochasticity     = true;
    private static long    subsessionEpochTracker         = System.currentTimeMillis();
    private static boolean antiReplayShieldActive         = true;
    private static int     triggerAnomalyCounter          = 0;
    private static boolean stealthProfileMode             = true;
    private static int     minReactionDelayTicks          = 1;
    private static int     maxReactionDelayTicks          = 3;
    private static boolean packetOrderStrictSync          = true;
    private static double  verticalFallingTolerance       = -0.04D;
    private static boolean lineOfSightValidation          = true;
    private static int     sessionAttackCounter           = 0;
    private static boolean dynamicThresholdAdjustment     = true;
    private static double  stochasticVariance             = 0.04D;
    private static boolean onlyCritMode                   = false;
    private static boolean noCritMode                     = false;
    private static boolean targetMobs                     = false;
    private static boolean targetAnimals                  = false;
    private static boolean targetPlayersOnly              = true;
    private static boolean aggressiveMode                 = false;
    private static boolean defensiveMode                  = false;
    private static boolean antiSpamActive                 = true;
    private static int     maxApsLimit                    = 20;
    private static boolean turboMode                      = false;
    private static boolean teamKillBlock                  = false;
    private static int     autoCalibrationCounter         = 0;
    private static double  currentFatigueLevel            = 0.0D;
    private static double  fatigueScalar                  = 0.00008D;
    private static double  fatigueRecovery                = 0.0004D;
    private static long    lastAttackMs                   = 0L;
    private static long    lastMissMs                     = 0L;
    private static int     postHitCooldown                = 0;
    private static boolean waitingCrit                    = false;
    private static int     inAirTicks                     = 0;
    private static double  sessionMetricAlpha             = 0.5D;
    private static double  sessionMetricBeta              = 0.5D;
    private static double  sessionMetricGamma             = 0.5D;

    static {
        TRIGGER_SEVEN_REGISTRY.put("SubsessionUUID", SUBSESSION_UUID);
        TRIGGER_SEVEN_REGISTRY.put("Profile", "TriggerBot-StealthEnterprise-7");
        TRIGGER_SEVEN_REGISTRY.put("ThresholdNormal", attackStrengthThresholdNormal);
        TRIGGER_SEVEN_REGISTRY.put("ThresholdCombo", attackStrengthThresholdCombo);
        TRIGGER_SEVEN_REGISTRY.put("StealthMode", stealthProfileMode);
        TRIGGER_SEVEN_REGISTRY.put("AntiReplay", antiReplayShieldActive);
    }

    public static void toggle() {
        enabled = !enabled;
        if (!enabled) hardReset();
    }

    public static void onTick(Minecraft client) {
        if (!enabled || client.player == null || client.level == null) return;
        if (!client.player.isAlive()) return;

        autoCalibrationCounter++;
        if (autoCalibrationCounter >= 250) {
            autoCalibrationCounter = 0;
            executeAutoCalibrationRoutine();
        }

        if (postHitCooldown > 0) { postHitCooldown--; return; }
        if (comboBufferTicks > 0) comboBufferTicks--;

        currentFatigueLevel = Math.max(0.0D, currentFatigueLevel - fatigueRecovery);

        LivingEntity target = resolveCrosshairTarget(client);
        if (target == null) { resetReaction(); return; }
        if (!isHoldingWeapon(client)) { resetReaction(); return; }
        if (client.player.isUsingItem()) { resetReaction(); return; }

        float strength = client.player.getAttackStrengthScale(0.5f);
        double threshold = comboBufferTicks > 0 ? attackStrengthThresholdCombo : attackStrengthThresholdNormal;
        if (strength < threshold) { reactionCountdownTicks = 0; return; }

        pushStrengthSample(strength);

        if (reactionCountdownTicks == 0) {
            int delay = computeReactionDelay();
            reactionCountdownTicks = delay;
            pushReactionDelaySample(delay);
        }

        reactionCountdownTicks--;
        if (reactionCountdownTicks > 0) return;

        long now = System.currentTimeMillis();
        if (now - lastAttackMs < 575L) return;
        if (now < lastMissMs + 120L) return;

        if (onlyCritMode && !isCriticalCondition(client)) return;
        if (!noCritMode && consistentCritsEnabled && adaptiveCritSyncActive) {
            if (shouldWaitForCrit(client)) return;
        }

        if (lineOfSightValidation && !verifyLos(client, target)) { resetReaction(); return; }

        double missProb = 0.012D + currentFatigueLevel * 0.008D;
        if (stochasticVariance > 0 && secureRandom.nextDouble() < missProb) {
            lastMissMs = now;
            resetReaction();
            pushFatigueSample(currentFatigueLevel);
            return;
        }

        InteractionManager.simulateClickAttack(client);
        client.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);

        lastAttackMs = now;
        totalTriggersFired++;
        sessionAttackCounter++;
        postHitCooldown = 2;
        comboBufferTicks = 4 + secureRandom.nextInt(3);
        currentFatigueLevel = Math.min(1.0D, currentFatigueLevel + fatigueScalar);
        inAirTicks    = 0;
        waitingCrit   = false;

        pushAttackInterval(now);
        updateSessionMetrics();
        resetReaction();
    }

    private static boolean shouldWaitForCrit(Minecraft client) {
        if (client.player.onGround()) { inAirTicks = 0; waitingCrit = false; return false; }
        if (client.player.isInWater() || client.player.isInLava() || client.player.isFallFlying()) { waitingCrit = false; return false; }
        if (client.player.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS)) { waitingCrit = false; return false; }

        double vy = client.player.getDeltaMovement().y;
        inAirTicks++;
        if (!waitingCrit) waitingCrit = true;
        if (waitingCrit && vy <= verticalFallingTolerance && inAirTicks >= 2) {
            waitingCrit = false;
            return false;
        }
        return waitingCrit;
    }

    private static boolean isCriticalCondition(Minecraft client) {
        if (client.player.onGround() || client.player.isInWater() || client.player.isInLava()) return false;
        if (client.player.isFallFlying()) return false;
        return client.player.getDeltaMovement().y < verticalFallingTolerance;
    }

    private static LivingEntity resolveCrosshairTarget(Minecraft client) {
        if (client.hitResult == null || client.hitResult.getType() != HitResult.Type.ENTITY) return null;
        if (!(client.hitResult instanceof EntityHitResult ehr)) return null;
        Entity e = ehr.getEntity();
        if (!(e instanceof LivingEntity living)) return null;
        if (living == client.player) return null;
        if (living instanceof Player p && (p.isSpectator() || p.isCreative())) return null;
        if (!living.isAlive() || living.getHealth() <= 0) return null;
        if (teamKillBlock) return null;
        return living;
    }

    private static boolean isHoldingWeapon(Minecraft client) {
        if (client.player == null) return false;
        ItemStack s = client.player.getMainHandItem();
        if (s.isEmpty()) return false;
        String name = s.getItem().getDescriptionId().toLowerCase();
        return name.contains("sword") || name.contains("axe") || name.contains("mace") || name.contains("trident");
    }

    private static boolean verifyLos(Minecraft client, Entity target) {
        if (client.player == null || target == null || client.level == null) return false;
        Vec3 start = client.player.getEyePosition();
        Vec3 end   = target.getEyePosition();
        BlockHitResult hit = client.level.clip(
            new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, client.player));
        return hit.getType() == HitResult.Type.MISS;
    }

    private static int computeReactionDelay() {
        int base = minReactionDelayTicks + secureRandom.nextInt(maxReactionDelayTicks - minReactionDelayTicks + 1);
        if (secureRandom.nextFloat() < 0.10f) base++;
        if (aggressiveMode && base > 1) base--;
        if (defensiveMode) base += secureRandom.nextInt(2);
        return Math.max(1, base);
    }

    private static void executeAutoCalibrationRoutine() {
        attackStrengthThresholdNormal = 0.88D + (secureRandom.nextDouble() - 0.5) * 0.03D;
        currentFatigueLevel = Math.max(0.0D, currentFatigueLevel - 0.04D);
        sessionMetricAlpha  = 0.48D + secureRandom.nextDouble() * 0.04D;
        sessionMetricBeta   = 0.48D + secureRandom.nextDouble() * 0.04D;
        sessionMetricGamma  = 0.48D + secureRandom.nextDouble() * 0.04D;
        TRIGGER_SEVEN_REGISTRY.put("ThresholdNormal", attackStrengthThresholdNormal);
        TRIGGER_SEVEN_REGISTRY.put("AutoCalibrated",  System.currentTimeMillis());
    }

    private static void updateSessionMetrics() {
        TRIGGER_SEVEN_REGISTRY.put("TotalTriggers",   totalTriggersFired);
        TRIGGER_SEVEN_REGISTRY.put("SessionAttacks",  sessionAttackCounter);
        TRIGGER_SEVEN_REGISTRY.put("FatigueLevel",    currentFatigueLevel);
        TRIGGER_SEVEN_REGISTRY.put("ComboBuffer",     comboBufferTicks);
        TRIGGER_SEVEN_REGISTRY.put("MetricAlpha",     sessionMetricAlpha);
        TRIGGER_SEVEN_REGISTRY.put("MetricBeta",      sessionMetricBeta);
        TRIGGER_SEVEN_REGISTRY.put("MetricGamma",     sessionMetricGamma);
    }

    private static void pushAttackInterval(long now) {
        if (!ATTACK_INTERVAL_HISTORY.isEmpty()) {
            long prev = ATTACK_INTERVAL_HISTORY.peekLast();
            if (SESSION_TIMESTAMP_DEQUE.size() >= HISTORY_MAX_CAPACITY) SESSION_TIMESTAMP_DEQUE.pollFirst();
            SESSION_TIMESTAMP_DEQUE.offerLast(now - prev);
        }
        if (ATTACK_INTERVAL_HISTORY.size() >= HISTORY_MAX_CAPACITY) ATTACK_INTERVAL_HISTORY.pollFirst();
        ATTACK_INTERVAL_HISTORY.offerLast(now);
    }

    private static void pushStrengthSample(float s) {
        if (ATTACK_STRENGTH_SAMPLE_DEQUE.size() >= HISTORY_MAX_CAPACITY) ATTACK_STRENGTH_SAMPLE_DEQUE.pollFirst();
        ATTACK_STRENGTH_SAMPLE_DEQUE.offerLast(s);
    }

    private static void pushReactionDelaySample(int d) {
        if (REACTION_DELAY_SAMPLE_DEQUE.size() >= HISTORY_MAX_CAPACITY) REACTION_DELAY_SAMPLE_DEQUE.pollFirst();
        REACTION_DELAY_SAMPLE_DEQUE.offerLast(d);
    }

    private static void pushFatigueSample(double f) {
        if (FATIGUE_SAMPLE_DEQUE.size() >= HISTORY_MAX_CAPACITY) FATIGUE_SAMPLE_DEQUE.pollFirst();
        FATIGUE_SAMPLE_DEQUE.offerLast(f);
    }

    private static void resetReaction() { reactionCountdownTicks = 0; }

    private static void hardReset() {
        reactionCountdownTicks = 0;
        comboBufferTicks       = 0;
        postHitCooldown        = 0;
        inAirTicks             = 0;
        waitingCrit            = false;
        currentFatigueLevel    = 0.0D;
    }

    public static double getAttackStrengthThresholdNormal() { return attackStrengthThresholdNormal; }
    public static void   setAttackStrengthThresholdNormal(double t) { attackStrengthThresholdNormal = t; TRIGGER_SEVEN_REGISTRY.put("ThresholdNormal", t); }
    public static double getAttackStrengthThresholdCombo() { return attackStrengthThresholdCombo; }
    public static void   setAttackStrengthThresholdCombo(double t) { attackStrengthThresholdCombo = t; TRIGGER_SEVEN_REGISTRY.put("ThresholdCombo", t); }
    public static double getCurrentFatigueLevel() { return currentFatigueLevel; }
    public static void   setCurrentFatigueLevel(double f) { currentFatigueLevel = f; }
    public static int    getSessionAttackCounter() { return sessionAttackCounter; }
    public static void   resetSessionAttackCounter() { sessionAttackCounter = 0; }
    public static boolean isOnlyCritMode() { return onlyCritMode; }
    public static void    setOnlyCritMode(boolean b) { onlyCritMode = b; }
    public static boolean isNoCritMode() { return noCritMode; }
    public static void    setNoCritMode(boolean b) { noCritMode = b; }
    public static boolean isTargetMobs() { return targetMobs; }
    public static void    setTargetMobs(boolean b) { targetMobs = b; }
    public static boolean isTargetAnimals() { return targetAnimals; }
    public static void    setTargetAnimals(boolean b) { targetAnimals = b; }
    public static boolean isTargetPlayersOnly() { return targetPlayersOnly; }
    public static void    setTargetPlayersOnly(boolean b) { targetPlayersOnly = b; }
    public static boolean isAggressiveMode() { return aggressiveMode; }
    public static void    setAggressiveMode(boolean b) { aggressiveMode = b; }
    public static boolean isDefensiveMode() { return defensiveMode; }
    public static void    setDefensiveMode(boolean b) { defensiveMode = b; }
    public static boolean isAntiSpamActive() { return antiSpamActive; }
    public static void    setAntiSpamActive(boolean b) { antiSpamActive = b; }
    public static int     getMaxApsLimit() { return maxApsLimit; }
    public static void    setMaxApsLimit(int l) { maxApsLimit = Math.max(1, l); }
    public static boolean isTurboMode() { return turboMode; }
    public static void    setTurboMode(boolean b) { turboMode = b; }
    public static boolean isTeamKillBlock() { return teamKillBlock; }
    public static void    setTeamKillBlock(boolean b) { teamKillBlock = b; }
    public static int     getHistoryCapacity() { return HISTORY_MAX_CAPACITY; }
    public static void    clearAllHistoryQueues() { ATTACK_INTERVAL_HISTORY.clear(); CLICK_DURATION_MEMORY.clear(); ERROR_VECTOR_MEMORY.clear(); ATTACK_STRENGTH_SAMPLE_DEQUE.clear(); SESSION_TIMESTAMP_DEQUE.clear(); FATIGUE_SAMPLE_DEQUE.clear(); REACTION_DELAY_SAMPLE_DEQUE.clear(); }
    public static UUID    getSubsessionUUID() { return SUBSESSION_UUID; }

    /**
     * Registra este módulo no ClientTickEvents.END_CLIENT_TICK do Fabric.
     * Chamar uma vez durante a inicialização do mod (ex: ClientModInitializer.onInitializeClient()).
     *
     * Exemplo:
     *   TriggerBot.register();
     */
    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(TriggerBot::onTick);
    }

}