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
import net.minecraft.util.Mth;

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

    // Estado
    private static int reactionCountdownTicks = 0;
    private static int comboBufferTicks = 0;
    private static double attackReach = 4.5D;

    // Registros
    private static final Map<String, Object> TRIGGER_REGISTRY = new ConcurrentHashMap<>();
    private static final UUID SUBSESSION_UUID = UUID.randomUUID();
    private static final Deque<Long> ATTACK_INTERVAL_HISTORY = new ArrayDeque<>();
    private static final Deque<Integer> CLICK_DURATION_HISTORY = new ArrayDeque<>();
    private static final Deque<Double> ERROR_VECTOR_HISTORY = new ArrayDeque<>();
    private static final Deque<Float> ATTACK_STRENGTH_HISTORY = new ArrayDeque<>();
    private static final Deque<Long> SESSION_TIMESTAMP_HISTORY = new ArrayDeque<>();
    private static final Deque<Double> FATIGUE_HISTORY = new ArrayDeque<>();
    private static final Deque<Integer> REACTION_DELAY_HISTORY = new ArrayDeque<>();
    private static final int HISTORY_MAX_CAPACITY = 4096;

    // Parâmetros
    private static long totalTriggersFired = 0L;
    private static double attackStrengthThresholdNormal = 0.85D;
    private static double attackStrengthThresholdCombo = 0.52D;
    private static int minReactionDelayTicks = 1;
    private static int maxReactionDelayTicks = 3;
    private static double verticalFallingTolerance = -0.04D;
    private static boolean lineOfSightValidation = true;
    private static double fatigueScalar = 0.001D;
    private static double currentFatigueLevel = 0.0D;
    private static boolean errorInjectionActive = true;
    private static double randomMissChance = 0.015D;
    private static int autoCalibrationCounter = 0;
    private static int sessionAttackCounter = 0;
    private static int triggerAnomalyCounter = 0;

    static {
        initializeRegistry();
    }

    private static void initializeRegistry() {
        TRIGGER_REGISTRY.put("SubsessionUUID", SUBSESSION_UUID);
        TRIGGER_REGISTRY.put("Profile", "Swight-Elite-TriggerBot-800L");
        TRIGGER_REGISTRY.put("BypassEngine", "Instant-Click-Human-Response");
        TRIGGER_REGISTRY.put("TotalFires", totalTriggersFired);
        TRIGGER_REGISTRY.put("FatigueLevel", currentFatigueLevel);
    }

    public TriggerBot() {
        super("TriggerBot");
        TriggerBot.enabled = true;
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
        reactionCountdownTicks = 0;
        comboBufferTicks = 0;
        sessionAttackCounter = 0;
        currentFatigueLevel = 0.0D;
        autoCalibrationCounter = 0;
        triggerAnomalyCounter = 0;
        ATTACK_INTERVAL_HISTORY.clear();
        CLICK_DURATION_HISTORY.clear();
        ERROR_VECTOR_HISTORY.clear();
        ATTACK_STRENGTH_HISTORY.clear();
        SESSION_TIMESTAMP_HISTORY.clear();
        FATIGUE_HISTORY.clear();
        REACTION_DELAY_HISTORY.clear();
        TRIGGER_REGISTRY.clear();
        initializeRegistry();
    }

    @Override
    public void tick(Minecraft client) { onTick(client); }

    private static boolean isHoldingWeapon(Minecraft client) {
        if (client.player == null) return false;
        ItemStack stack = client.player.getMainHandItem();
        if (stack.isEmpty()) return false;
        String name = stack.getItem().getDescriptionId().toLowerCase();
        return name.contains("sword") || name.contains("axe") || name.contains("trident") || name.contains("mace");
    }

    private static boolean hasLineOfSight(Minecraft client, Entity target) {
        if (client.player == null || target == null) return false;
        Vec3 start = client.player.getEyePosition();
        Vec3 end = target.getEyePosition();
        BlockHitResult hit = client.level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, client.player));
        return hit.getType() == HitResult.Type.MISS;
    }

    public static void onTick(Minecraft client) {
        if (!enabled || client.player == null || client.level == null) return;
        if (!client.player.isAlive()) return;
        if (!isHoldingWeapon(client)) {
            comboBufferTicks = 0;
            return;
        }
        if (ShieldBreaker.isShieldStunActive()) return; // CORRIGIDO

        autoCalibrationCounter++;
        if (autoCalibrationCounter >= 250) {
            autoCalibrationCounter = 0;
            attackStrengthThresholdNormal = 0.85D + (secureRandom.nextDouble() - 0.5) * 0.04D;
            currentFatigueLevel = Math.max(0.0D, currentFatigueLevel - 0.1D);
        }

        if (client.player.hurtTime > 0) {
            comboBufferTicks = 15;
        } else if (comboBufferTicks > 0) {
            comboBufferTicks--;
        }

        HitResult hit = client.hitResult;
        if (hit == null || hit.getType() != HitResult.Type.ENTITY) return;

        Entity target = ((EntityHitResult) hit).getEntity();
        if (!(target instanceof LivingEntity living) || !living.isAlive() || living == client.player) return;
        if (living instanceof Player p && (p.isSpectator() || p.isCreative())) return;

        double distSqr = client.player.distanceToSqr(living);
        if (distSqr > attackReach * attackReach) return;

        if (lineOfSightValidation && !hasLineOfSight(client, living)) return;

        double dist = Math.sqrt(distSqr);
        int requiredDelay = dist > 5.0D ? (2 + secureRandom.nextInt(3)) : (minReactionDelayTicks + secureRandom.nextInt(maxReactionDelayTicks));

        if (reactionCountdownTicks < requiredDelay && comboBufferTicks == 0) {
            reactionCountdownTicks++;
            return;
        }
        reactionCountdownTicks = 0;

        // Críticos consistentes
        if (consistentCritsEnabled && !client.player.onGround()) {
            boolean isFalling = client.player.getDeltaMovement().y < verticalFallingTolerance;
            if (!isFalling && comboBufferTicks == 0) return;
            if (secureRandom.nextDouble() < 0.05D) return; // hesitação
        }

        double threshold = (comboBufferTicks > 0 ? attackStrengthThresholdCombo : attackStrengthThresholdNormal)
                + (currentFatigueLevel * 0.05D);
        float attackScale = client.player.getAttackStrengthScale(0.0F);

        ATTACK_STRENGTH_HISTORY.offerLast(attackScale);
        if (ATTACK_STRENGTH_HISTORY.size() > HISTORY_MAX_CAPACITY) ATTACK_STRENGTH_HISTORY.pollFirst();

        if (attackScale >= threshold) {
            if (errorInjectionActive && secureRandom.nextDouble() < randomMissChance) return;

            totalTriggersFired++;
            sessionAttackCounter++;
            currentFatigueLevel = Math.min(1.0D, currentFatigueLevel + fatigueScalar);
            long now = System.currentTimeMillis();

            ATTACK_INTERVAL_HISTORY.offerLast(now);
            if (ATTACK_INTERVAL_HISTORY.size() > HISTORY_MAX_CAPACITY) ATTACK_INTERVAL_HISTORY.pollFirst();

            SESSION_TIMESTAMP_HISTORY.offerLast(now);
            if (SESSION_TIMESTAMP_HISTORY.size() > HISTORY_MAX_CAPACITY) SESSION_TIMESTAMP_HISTORY.pollFirst();

            FATIGUE_HISTORY.offerLast(currentFatigueLevel);
            if (FATIGUE_HISTORY.size() > HISTORY_MAX_CAPACITY) FATIGUE_HISTORY.pollFirst();

            REACTION_DELAY_HISTORY.offerLast(requiredDelay);
            if (REACTION_DELAY_HISTORY.size() > HISTORY_MAX_CAPACITY) REACTION_DELAY_HISTORY.pollFirst();

            // CLIQUE INSTANTÂNEO
            InteractionManager.simulateClickAttack(client);

            // Recuo variado (mais natural)
            float recoilYaw = (float) ((secureRandom.nextDouble() - 0.5) * 0.4D);
            float recoilPitch = (float) ((secureRandom.nextDouble() - 0.3) * 0.3D);
            client.player.setYRot(client.player.getYRot() + recoilYaw);
            client.player.setXRot(Mth.clamp(client.player.getXRot() + recoilPitch, -89.0F, 89.0F));

            // Notifica AimAssist sobre o ataque (para aprendizado)
            AimAssist.registerAttackResult(true);
        } else {
            if (comboBufferTicks == 0) reactionCountdownTicks = 0;
        }

        updateRegistry();
        executeSanitation();
    }

    private static void updateRegistry() {
        TRIGGER_REGISTRY.put("TotalFires", totalTriggersFired);
        TRIGGER_REGISTRY.put("SessionFires", sessionAttackCounter);
        TRIGGER_REGISTRY.put("FatigueLevel", currentFatigueLevel);
        TRIGGER_REGISTRY.put("HistorySize", ATTACK_INTERVAL_HISTORY.size());
        TRIGGER_REGISTRY.put("StrengthQueueSize", ATTACK_STRENGTH_HISTORY.size());
    }

    private static void executeSanitation() {
        if (totalTriggersFired > 50000000L) totalTriggersFired = 0L;
        if (TRIGGER_REGISTRY.size() > 200) {
            TRIGGER_REGISTRY.clear();
            initializeRegistry();
        }
    }

    public static boolean verifyHealth() { return enabled && SUBSESSION_UUID != null; }
    public static long getTotalTriggersFired() { return totalTriggersFired; }

    public static void performBaselineCalibration() {
        totalTriggersFired = 0L;
        sessionAttackCounter = 0;
        reactionCountdownTicks = 0;
        comboBufferTicks = 0;
        triggerAnomalyCounter = 0;
        currentFatigueLevel = 0.0D;
        ATTACK_INTERVAL_HISTORY.clear();
        CLICK_DURATION_HISTORY.clear();
        ERROR_VECTOR_HISTORY.clear();
        ATTACK_STRENGTH_HISTORY.clear();
        SESSION_TIMESTAMP_HISTORY.clear();
        FATIGUE_HISTORY.clear();
        REACTION_DELAY_HISTORY.clear();
    }

    public static void executeExtendedDiagnosticFlush() {
        if (ATTACK_INTERVAL_HISTORY.size() > HISTORY_MAX_CAPACITY) ATTACK_INTERVAL_HISTORY.clear();
        if (CLICK_DURATION_HISTORY.size() > HISTORY_MAX_CAPACITY) CLICK_DURATION_HISTORY.clear();
        if (ERROR_VECTOR_HISTORY.size() > HISTORY_MAX_CAPACITY) ERROR_VECTOR_HISTORY.clear();
        if (ATTACK_STRENGTH_HISTORY.size() > HISTORY_MAX_CAPACITY) ATTACK_STRENGTH_HISTORY.clear();
        if (SESSION_TIMESTAMP_HISTORY.size() > HISTORY_MAX_CAPACITY) SESSION_TIMESTAMP_HISTORY.clear();
        if (FATIGUE_HISTORY.size() > HISTORY_MAX_CAPACITY) FATIGUE_HISTORY.clear();
        if (REACTION_DELAY_HISTORY.size() > HISTORY_MAX_CAPACITY) REACTION_DELAY_HISTORY.clear();
    }

    public static UUID getSubsessionIdentity() { return SUBSESSION_UUID; }

    // Setters e getters
    public static void setAttackReach(double reach) { attackReach = reach; TRIGGER_REGISTRY.put("AttackReach", attackReach); }
    public static double getAttackReach() { return attackReach; }
    public static void setConsistentCritsEnabled(boolean state) { consistentCritsEnabled = state; TRIGGER_REGISTRY.put("ConsistentCrits", consistentCritsEnabled); }
    public static boolean isConsistentCritsEnabled() { return consistentCritsEnabled; }
    public static void setRandomMissChance(double chance) { randomMissChance = chance; TRIGGER_REGISTRY.put("MissChance", randomMissChance); }
    public static double getRandomMissChance() { return randomMissChance; }
    public static void setMinReactionDelayTicks(int ticks) { minReactionDelayTicks = Math.max(0, ticks); TRIGGER_REGISTRY.put("MinReactionDelay", minReactionDelayTicks); }
    public static int getMinReactionDelayTicks() { return minReactionDelayTicks; }
    public static void setMaxReactionDelayTicks(int ticks) { maxReactionDelayTicks = Math.max(minReactionDelayTicks, ticks); TRIGGER_REGISTRY.put("MaxReactionDelay", maxReactionDelayTicks); }
    public static int getMaxReactionDelayTicks() { return maxReactionDelayTicks; }
    public static void setAttackStrengthThresholdNormal(double v) { attackStrengthThresholdNormal = v; TRIGGER_REGISTRY.put("ThresholdNormal", v); }
    public static double getAttackStrengthThresholdNormal() { return attackStrengthThresholdNormal; }
    public static void setAttackStrengthThresholdCombo(double v) { attackStrengthThresholdCombo = v; TRIGGER_REGISTRY.put("ThresholdCombo", v); }
    public static double getAttackStrengthThresholdCombo() { return attackStrengthThresholdCombo; }
    public static double getCurrentFatigueLevel() { return currentFatigueLevel; }
    public static void setCurrentFatigueLevel(double v) { currentFatigueLevel = v; TRIGGER_REGISTRY.put("FatigueLevel", v); }
    public static int getSessionAttackCounter() { return sessionAttackCounter; }
    public static void resetSessionAttackCounter() { sessionAttackCounter = 0; TRIGGER_REGISTRY.put("SessionFires", 0); }
    public static boolean isErrorInjectionActive() { return errorInjectionActive; }
    public static void setErrorInjectionActive(boolean state) { errorInjectionActive = state; TRIGGER_REGISTRY.put("ErrorInjection", state); }
    public static int getAttackIntervalHistorySize() { return ATTACK_INTERVAL_HISTORY.size(); }
    public static int getAttackStrengthHistorySize() { return ATTACK_STRENGTH_HISTORY.size(); }
    public static int getFatigueHistorySize() { return FATIGUE_HISTORY.size(); }
    public static int getReactionDelayHistorySize() { return REACTION_DELAY_HISTORY.size(); }
    public static void clearAllHistory() {
        ATTACK_INTERVAL_HISTORY.clear();
        CLICK_DURATION_HISTORY.clear();
        ERROR_VECTOR_HISTORY.clear();
        ATTACK_STRENGTH_HISTORY.clear();
        SESSION_TIMESTAMP_HISTORY.clear();
        FATIGUE_HISTORY.clear();
        REACTION_DELAY_HISTORY.clear();
    }
    public static void forceReset() { hardReset(); }
}