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
    public static boolean onlyCrits = false;
    public static boolean noCrits = false;
    public static boolean holdMode = false;
    public static boolean toggleMode = false;
    public static boolean aggressiveMode = false;
    public static boolean defensiveMode = false;
    public static boolean stealthMode = false;
    public static boolean preciseMode = false;
    public static boolean turboMode = false;
    public static boolean focusMode = false;
    public static boolean freeMode = false;
    public static boolean antiTeam = true;
    public static boolean attackMobs = false;
    public static boolean attackAnimals = false;
    public static boolean onlyPvP = false;
    public static boolean onlyPvE = false;
    public static boolean antiSpam = true;
    public static int maxAttacksPerSecond = 8;

    private static final SecureRandom secureRandom = new SecureRandom();

    private static int reactionCountdownTicks = 0;
    private static int comboBufferTicks = 0;
    private static double attackReach = 4.5D;
    private static long totalTriggersFired = 0L;
    private static int sessionAttackCounter = 0;
    private static double currentFatigueLevel = 0.0D;
    private static int autoCalibrationCounter = 0;
    private static int triggerAnomalyCounter = 0;
    private static int missCounter = 0;
    private static int hitCounter = 0;
    private static long lastAttackTime = 0L;
    private static int attacksThisSecond = 0;
    private static int secondCounter = 0;

    private static double attackStrengthThresholdNormal = 0.70D;
    private static double attackStrengthThresholdCombo = 0.45D;
    private static int minReactionDelayTicks = 1;
    private static int maxReactionDelayTicks = 3;
    private static double verticalFallingTolerance = -0.04D;
    private static boolean lineOfSightValidation = true;
    private static double fatigueScalar = 0.001D;
    private static double randomMissChance = 0.015D;
    private static boolean errorInjectionActive = true;
    private static double weaponReachSword = 3.0D;
    private static double weaponReachAxe = 3.5D;
    private static double weaponReachTrident = 4.0D;
    private static double weaponReachMace = 4.5D;
    private static double thresholdSword = 0.70D;
    private static double thresholdAxe = 0.75D;
    private static double thresholdTrident = 0.65D;
    private static double thresholdMace = 0.80D;

    private static final Map<String, Object> TRIGGER_900_REGISTRY = new ConcurrentHashMap<>();
    private static final UUID SUBSESSION_UUID = UUID.randomUUID();
    private static final Deque<Long> ATTACK_INTERVAL_HISTORY = new ArrayDeque<>();
    private static final Deque<Integer> CLICK_DURATION_HISTORY = new ArrayDeque<>();
    private static final Deque<Double> ERROR_VECTOR_HISTORY = new ArrayDeque<>();
    private static final Deque<Float> ATTACK_STRENGTH_HISTORY = new ArrayDeque<>();
    private static final Deque<Long> SESSION_TIMESTAMP_HISTORY = new ArrayDeque<>();
    private static final Deque<Double> FATIGUE_HISTORY = new ArrayDeque<>();
    private static final Deque<Integer> REACTION_DELAY_HISTORY = new ArrayDeque<>();
    private static final Deque<String> WEAPON_USAGE_HISTORY = new ArrayDeque<>();
    private static final int HISTORY_MAX_CAPACITY = 4096;

    static {
        TRIGGER_900_REGISTRY.put("SubsessionUUID", SUBSESSION_UUID);
        TRIGGER_900_REGISTRY.put("Profile", "Swight-TriggerBot-900Lines");
        TRIGGER_900_REGISTRY.put("TotalFires", totalTriggersFired);
        TRIGGER_900_REGISTRY.put("FatigueLevel", currentFatigueLevel);
    }

    public TriggerBot() {
        super("TriggerBot");
        TriggerBot.enabled = true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void toggle() {
        enabled = !enabled;
        super.enabled = enabled;
        if (!enabled) hardReset();
    }

    private static void hardReset() {
        reactionCountdownTicks = 0;
        comboBufferTicks = 0;
        sessionAttackCounter = 0;
        currentFatigueLevel = 0.0D;
        autoCalibrationCounter = 0;
        triggerAnomalyCounter = 0;
        missCounter = 0;
        hitCounter = 0;
        attacksThisSecond = 0;
        secondCounter = 0;
        ATTACK_INTERVAL_HISTORY.clear();
        CLICK_DURATION_HISTORY.clear();
        ERROR_VECTOR_HISTORY.clear();
        ATTACK_STRENGTH_HISTORY.clear();
        SESSION_TIMESTAMP_HISTORY.clear();
        FATIGUE_HISTORY.clear();
        REACTION_DELAY_HISTORY.clear();
        WEAPON_USAGE_HISTORY.clear();
        TRIGGER_900_REGISTRY.clear();
        TRIGGER_900_REGISTRY.put("SubsessionUUID", SUBSESSION_UUID);
        TRIGGER_900_REGISTRY.put("Profile", "Swight-TriggerBot-900Lines");
    }

    @Override
    public void tick(Minecraft client) {
        onTick(client);
    }

    private static String getWeaponKey(Minecraft client) {
        if (client.player == null) return "sword";
        ItemStack stack = client.player.getMainHandItem();
        if (stack.isEmpty()) return "sword";
        String name = stack.getItem().getDescriptionId().toLowerCase();
        if (name.contains("axe")) return "axe";
        if (name.contains("trident")) return "trident";
        if (name.contains("mace")) return "mace";
        return "sword";
    }

    private static double getWeaponReach(Minecraft client) {
        String key = getWeaponKey(client);
        switch (key) {
            case "axe": return weaponReachAxe;
            case "trident": return weaponReachTrident;
            case "mace": return weaponReachMace;
            default: return weaponReachSword;
        }
    }

    private static double getWeaponThreshold(Minecraft client) {
        String key = getWeaponKey(client);
        switch (key) {
            case "axe": return thresholdAxe;
            case "trident": return thresholdTrident;
            case "mace": return thresholdMace;
            default: return thresholdSword;
        }
    }

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

    private static boolean isTeamMate(Player player, Entity target) {
        if (!(target instanceof Player)) return false;
        return player.getTeam() != null && player.getTeam().isAlliedTo(((Player) target).getTeam());
    }

    private static boolean isValidTarget(Minecraft client, Entity entity) {
        if (!(entity instanceof LivingEntity living)) return false;
        if (!living.isAlive()) return false;
        if (living == client.player) return false;
        if (living instanceof Player p && (p.isSpectator() || p.isCreative())) return false;
        if (antiTeam && living instanceof Player p && isTeamMate(client.player, p)) return false;
        if (onlyPvP && !(living instanceof Player)) return false;
        if (onlyPvE && living instanceof Player) return false;
        if (!attackMobs && !(living instanceof Player)) return false;
        if (!attackAnimals && living instanceof Player) return false;
        return true;
    }

    public static void onTick(Minecraft client) {
        if (!enabled || client.player == null || client.level == null) return;
        if (!client.player.isAlive()) return;
        if (!isHoldingWeapon(client)) {
            comboBufferTicks = 0;
            return;
        }
        if (ShieldBreaker.isShieldStunActive()) return;

        autoCalibrationCounter++;
        if (autoCalibrationCounter >= 250) {
            autoCalibrationCounter = 0;
            attackStrengthThresholdNormal = 0.70D + (secureRandom.nextDouble() - 0.5) * 0.04D;
            currentFatigueLevel = Math.max(0.0D, currentFatigueLevel - 0.1D);
        }

        secondCounter++;
        if (secondCounter >= 20) {
            secondCounter = 0;
            attacksThisSecond = 0;
        }

        if (client.player.hurtTime > 0) comboBufferTicks = 15;
        else if (comboBufferTicks > 0) comboBufferTicks--;

        HitResult hit = client.hitResult;
        if (hit == null || hit.getType() != HitResult.Type.ENTITY) return;

        Entity target = ((EntityHitResult) hit).getEntity();
        if (!isValidTarget(client, target)) return;

        double reach = getWeaponReach(client);
        double distSqr = client.player.distanceToSqr(target);
        if (distSqr > reach * reach) return;

        if (lineOfSightValidation && !hasLineOfSight(client, target)) return;

        double dist = Math.sqrt(distSqr);
        int requiredDelay = dist > 5.0D ? (2 + secureRandom.nextInt(3)) : (1 + secureRandom.nextInt(2));

        long ping = 50L;
        if (client.getConnection() != null) {
            try { ping = client.getConnection().getPing(); } catch (Exception ignored) {}
        }
        if (ping > 100) requiredDelay += 1;

        if (reactionCountdownTicks < requiredDelay && comboBufferTicks == 0) {
            reactionCountdownTicks++;
            return;
        }
        reactionCountdownTicks = 0;

        if (onlyCrits && client.player.onGround()) return;
        if (noCrits && !client.player.onGround()) return;

        if (consistentCritsEnabled && !client.player.onGround()) {
            boolean isFalling = client.player.getDeltaMovement().y < verticalFallingTolerance;
            if (!isFalling && comboBufferTicks == 0) return;
            if (secureRandom.nextDouble() < 0.05D) return;
        }

        double threshold = getWeaponThreshold(client);
        if (aggressiveMode) threshold = 0.60D;
        if (defensiveMode) threshold = 0.90D;
        if (client.player.getHealth() <= 6.0F) threshold = Math.min(threshold, 0.55D);
        // REMOVIDO: referência a lockedTarget (não existe no TriggerBot)
        // if (focusMode && lockedTarget != null && target == lockedTarget) threshold = 0.65D;

        threshold += currentFatigueLevel * 0.05D;

        float attackScale = client.player.getAttackStrengthScale(0.0F);
        ATTACK_STRENGTH_HISTORY.offerLast(attackScale);
        if (ATTACK_STRENGTH_HISTORY.size() > HISTORY_MAX_CAPACITY) ATTACK_STRENGTH_HISTORY.pollFirst();

        if (attackScale >= threshold) {
            if (errorInjectionActive && secureRandom.nextDouble() < randomMissChance) {
                missCounter++;
                return;
            }

            if (antiSpam && attacksThisSecond >= maxAttacksPerSecond) return;

            totalTriggersFired++;
            sessionAttackCounter++;
            hitCounter++;
            attacksThisSecond++;
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

            WEAPON_USAGE_HISTORY.offerLast(getWeaponKey(client));
            if (WEAPON_USAGE_HISTORY.size() > HISTORY_MAX_CAPACITY) WEAPON_USAGE_HISTORY.pollFirst();

            InteractionManager.simulateClickAttack(client);

            float recoilYaw = (float) ((secureRandom.nextDouble() - 0.5) * 0.4D);
            float recoilPitch = (float) ((secureRandom.nextDouble() - 0.3) * 0.3D);
            client.player.setYRot(client.player.getYRot() + recoilYaw);
            client.player.setXRot(Mth.clamp(client.player.getXRot() + recoilPitch, -89.0F, 89.0F));

            long spectatorCount = client.level.players().stream().filter(p -> p != client.player && p.isSpectator()).count();
            if (spectatorCount > 0 && stealthMode) {
                float extraRecoilYaw = (float) ((secureRandom.nextDouble() - 0.5) * 0.6D);
                float extraRecoilPitch = (float) ((secureRandom.nextDouble() - 0.5) * 0.4D);
                client.player.setYRot(client.player.getYRot() + extraRecoilYaw);
                client.player.setXRot(Mth.clamp(client.player.getXRot() + extraRecoilPitch, -89.0F, 89.0F));
            }

            if (turboMode && secureRandom.nextDouble() < 0.3D) {
                InteractionManager.simulateClickAttack(client);
            }

            if (currentFatigueLevel > 0.8D) {
                randomMissChance = Math.min(0.08D, randomMissChance + 0.001D);
            } else {
                randomMissChance = Math.max(0.01D, randomMissChance - 0.0005D);
            }

            double hitRate = (double) hitCounter / (hitCounter + missCounter + 1);
            if (hitRate > 0.95D) {
                attackStrengthThresholdNormal = Math.min(0.85D, attackStrengthThresholdNormal + 0.005D);
            } else if (hitRate < 0.60D) {
                attackStrengthThresholdNormal = Math.max(0.50D, attackStrengthThresholdNormal - 0.005D);
            }

            if (holdMode && !client.options.keyAttack.isDown()) {
                return;
            }

        } else {
            if (comboBufferTicks == 0) reactionCountdownTicks = 0;
        }

        updateRegistry();
        executeSanitation();
    }

    private static void updateRegistry() {
        TRIGGER_900_REGISTRY.put("TotalFires", totalTriggersFired);
        TRIGGER_900_REGISTRY.put("SessionFires", sessionAttackCounter);
        TRIGGER_900_REGISTRY.put("FatigueLevel", currentFatigueLevel);
        TRIGGER_900_REGISTRY.put("HistorySize", ATTACK_INTERVAL_HISTORY.size());
        TRIGGER_900_REGISTRY.put("StrengthQueueSize", ATTACK_STRENGTH_HISTORY.size());
        TRIGGER_900_REGISTRY.put("HitRate", (double) hitCounter / (hitCounter + missCounter + 1));
    }

    private static void executeSanitation() {
        if (totalTriggersFired > 50000000L) totalTriggersFired = 0L;
        if (TRIGGER_900_REGISTRY.size() > 200) {
            TRIGGER_900_REGISTRY.clear();
            TRIGGER_900_REGISTRY.put("SubsessionUUID", SUBSESSION_UUID);
            TRIGGER_900_REGISTRY.put("Profile", "Swight-TriggerBot-900Lines");
        }
    }

    public static UUID getSubsessionIdentity() {
        return SUBSESSION_UUID;
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
        missCounter = 0;
        hitCounter = 0;
        attacksThisSecond = 0;
        secondCounter = 0;
        ATTACK_INTERVAL_HISTORY.clear();
        CLICK_DURATION_HISTORY.clear();
        ERROR_VECTOR_HISTORY.clear();
        ATTACK_STRENGTH_HISTORY.clear();
        SESSION_TIMESTAMP_HISTORY.clear();
        FATIGUE_HISTORY.clear();
        REACTION_DELAY_HISTORY.clear();
        WEAPON_USAGE_HISTORY.clear();
    }

    public static void executeExtendedDiagnosticFlush() {
        if (ATTACK_INTERVAL_HISTORY.size() > HISTORY_MAX_CAPACITY) ATTACK_INTERVAL_HISTORY.clear();
        if (CLICK_DURATION_HISTORY.size() > HISTORY_MAX_CAPACITY) CLICK_DURATION_HISTORY.clear();
        if (ERROR_VECTOR_HISTORY.size() > HISTORY_MAX_CAPACITY) ERROR_VECTOR_HISTORY.clear();
        if (ATTACK_STRENGTH_HISTORY.size() > HISTORY_MAX_CAPACITY) ATTACK_STRENGTH_HISTORY.clear();
        if (SESSION_TIMESTAMP_HISTORY.size() > HISTORY_MAX_CAPACITY) SESSION_TIMESTAMP_HISTORY.clear();
        if (FATIGUE_HISTORY.size() > HISTORY_MAX_CAPACITY) FATIGUE_HISTORY.clear();
        if (REACTION_DELAY_HISTORY.size() > HISTORY_MAX_CAPACITY) REACTION_DELAY_HISTORY.clear();
        if (WEAPON_USAGE_HISTORY.size() > HISTORY_MAX_CAPACITY) WEAPON_USAGE_HISTORY.clear();
    }

    public static void setAttackReach(double reach) {
        attackReach = reach;
        TRIGGER_900_REGISTRY.put("AttackReach", attackReach);
    }

    public static double getAttackReach() {
        return attackReach;
    }

    public static void setConsistentCritsEnabled(boolean state) {
        consistentCritsEnabled = state;
        TRIGGER_900_REGISTRY.put("ConsistentCrits", consistentCritsEnabled);
    }

    public static boolean isConsistentCritsEnabled() {
        return consistentCritsEnabled;
    }

    public static void setRandomMissChance(double chance) {
        randomMissChance = chance;
        TRIGGER_900_REGISTRY.put("MissChance", randomMissChance);
    }

    public static double getRandomMissChance() {
        return randomMissChance;
    }

    public static void setMinReactionDelayTicks(int ticks) {
        minReactionDelayTicks = Math.max(0, ticks);
        TRIGGER_900_REGISTRY.put("MinReactionDelay", minReactionDelayTicks);
    }

    public static int getMinReactionDelayTicks() {
        return minReactionDelayTicks;
    }

    public static void setMaxReactionDelayTicks(int ticks) {
        maxReactionDelayTicks = Math.max(minReactionDelayTicks, ticks);
        TRIGGER_900_REGISTRY.put("MaxReactionDelay", maxReactionDelayTicks);
    }

    public static int getMaxReactionDelayTicks() {
        return maxReactionDelayTicks;
    }

    public static void setAttackStrengthThresholdNormal(double threshold) {
        attackStrengthThresholdNormal = threshold;
        TRIGGER_900_REGISTRY.put("ThresholdNormal", attackStrengthThresholdNormal);
    }

    public static double getAttackStrengthThresholdNormal() {
        return attackStrengthThresholdNormal;
    }

    public static void setAttackStrengthThresholdCombo(double threshold) {
        attackStrengthThresholdCombo = threshold;
        TRIGGER_900_REGISTRY.put("ThresholdCombo", attackStrengthThresholdCombo);
    }

    public static double getAttackStrengthThresholdCombo() {
        return attackStrengthThresholdCombo;
    }

    public static double getCurrentFatigueLevel() {
        return currentFatigueLevel;
    }

    public static void setCurrentFatigueLevel(double fatigue) {
        currentFatigueLevel = fatigue;
                TRIGGER_900_REGISTRY.put("FatigueLevel", currentFatigueLevel);
    }

    public TriggerBot() {
        super("TriggerBot");
        TriggerBot.enabled = true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void toggle() {
        enabled = !enabled;
        super.enabled = enabled;
        if (!enabled) hardReset();
    }

    private static void hardReset() {
        reactionCountdownTicks = 0;
        comboBufferTicks = 0;
        sessionAttackCounter = 0;
        currentFatigueLevel = 0.0D;
        autoCalibrationCounter = 0;
        triggerAnomalyCounter = 0;
        missCounter = 0;
        hitCounter = 0;
        attacksThisSecond = 0;
        secondCounter = 0;
        ATTACK_INTERVAL_HISTORY.clear();
        CLICK_DURATION_HISTORY.clear();
        ERROR_VECTOR_HISTORY.clear();
        ATTACK_STRENGTH_HISTORY.clear();
        SESSION_TIMESTAMP_HISTORY.clear();
        FATIGUE_HISTORY.clear();
        REACTION_DELAY_HISTORY.clear();
        WEAPON_USAGE_HISTORY.clear();
        TRIGGER_900_REGISTRY.clear();
        TRIGGER_900_REGISTRY.put("SubsessionUUID", SUBSESSION_UUID);
        TRIGGER_900_REGISTRY.put("Profile", "Swight-TriggerBot-900Lines");
    }

    @Override
    public void tick(Minecraft client) {
        onTick(client);
    }

    private static String getWeaponKey(Minecraft client) {
        if (client.player == null) return "sword";
        ItemStack stack = client.player.getMainHandItem();
        if (stack.isEmpty()) return "sword";
        String name = stack.getItem().getDescriptionId().toLowerCase();
        if (name.contains("axe")) return "axe";
        if (name.contains("trident")) return "trident";
        if (name.contains("mace")) return "mace";
        return "sword";
    }

    private static double getWeaponReach(Minecraft client) {
        String key = getWeaponKey(client);
        switch (key) {
            case "axe": return weaponReachAxe;
            case "trident": return weaponReachTrident;
            case "mace": return weaponReachMace;
            default: return weaponReachSword;
        }
    }

    private static double getWeaponThreshold(Minecraft client) {
        String key = getWeaponKey(client);
        switch (key) {
            case "axe": return thresholdAxe;
            case "trident": return thresholdTrident;
            case "mace": return thresholdMace;
            default: return thresholdSword;
        }
    }

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

    private static boolean isTeamMate(Player player, Entity target) {
        if (!(target instanceof Player)) return false;
        return player.getTeam() != null && player.getTeam().isAlliedTo(((Player) target).getTeam());
    }

    private static boolean isValidTarget(Minecraft client, Entity entity) {
        if (!(entity instanceof LivingEntity living)) return false;
        if (!living.isAlive()) return false;
        if (living == client.player) return false;
        if (living instanceof Player p && (p.isSpectator() || p.isCreative())) return false;
        if (antiTeam && living instanceof Player p && isTeamMate(client.player, p)) return false;
        if (onlyPvP && !(living instanceof Player)) return false;
        if (onlyPvE && living instanceof Player) return false;
        if (!attackMobs && !(living instanceof Player)) return false;
        if (!attackAnimals && living instanceof Player) return false;
        return true;
    }

    public static void onTick(Minecraft client) {
        if (!enabled || client.player == null || client.level == null) return;
        if (!client.player.isAlive()) return;
        if (!isHoldingWeapon(client)) {
            comboBufferTicks = 0;
            return;
        }
        if (ShieldBreaker.isShieldStunActive()) return;

        autoCalibrationCounter++;
        if (autoCalibrationCounter >= 250) {
            autoCalibrationCounter = 0;
            attackStrengthThresholdNormal = 0.70D + (secureRandom.nextDouble() - 0.5) * 0.04D;
            currentFatigueLevel = Math.max(0.0D, currentFatigueLevel - 0.1D);
        }

        secondCounter++;
        if (secondCounter >= 20) {
            secondCounter = 0;
            attacksThisSecond = 0;
        }

        if (client.player.hurtTime > 0) comboBufferTicks = 15;
        else if (comboBufferTicks > 0) comboBufferTicks--;

        HitResult hit = client.hitResult;
        if (hit == null || hit.getType() != HitResult.Type.ENTITY) return;

        Entity target = ((EntityHitResult) hit).getEntity();
        if (!isValidTarget(client, target)) return;

        double reach = getWeaponReach(client);
        double distSqr = client.player.distanceToSqr(target);
        if (distSqr > reach * reach) return;

        if (lineOfSightValidation && !hasLineOfSight(client, target)) return;

        double dist = Math.sqrt(distSqr);
        int requiredDelay = dist > 5.0D ? (2 + secureRandom.nextInt(3)) : (1 + secureRandom.nextInt(2));

        long ping = 50L;
        if (client.getConnection() != null) {
            try { ping = client.getConnection().getPing(); } catch (Exception ignored) {}
        }
        if (ping > 100) requiredDelay += 1;

        if (reactionCountdownTicks < requiredDelay && comboBufferTicks == 0) {
            reactionCountdownTicks++;
            return;
        }
        reactionCountdownTicks = 0;

        if (onlyCrits && client.player.onGround()) return;
        if (noCrits && !client.player.onGround()) return;

        if (consistentCritsEnabled && !client.player.onGround()) {
            boolean isFalling = client.player.getDeltaMovement().y < verticalFallingTolerance;
            if (!isFalling && comboBufferTicks == 0) return;
            if (secureRandom.nextDouble() < 0.05D) return;
        }

        double threshold = getWeaponThreshold(client);
        if (aggressiveMode) threshold = 0.60D;
        if (defensiveMode) threshold = 0.90D;
        if (client.player.getHealth() <= 6.0F) threshold = Math.min(threshold, 0.55D);

        threshold += currentFatigueLevel * 0.05D;

        float attackScale = client.player.getAttackStrengthScale(0.0F);
        ATTACK_STRENGTH_HISTORY.offerLast(attackScale);
        if (ATTACK_STRENGTH_HISTORY.size() > HISTORY_MAX_CAPACITY) ATTACK_STRENGTH_HISTORY.pollFirst();

        if (attackScale >= threshold) {
            if (errorInjectionActive && secureRandom.nextDouble() < randomMissChance) {
                missCounter++;
                return;
            }

            if (antiSpam && attacksThisSecond >= maxAttacksPerSecond) return;

            totalTriggersFired++;
            sessionAttackCounter++;
            hitCounter++;
            attacksThisSecond++;
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

            WEAPON_USAGE_HISTORY.offerLast(getWeaponKey(client));
            if (WEAPON_USAGE_HISTORY.size() > HISTORY_MAX_CAPACITY) WEAPON_USAGE_HISTORY.pollFirst();

            InteractionManager.simulateClickAttack(client);

            float recoilYaw = (float) ((secureRandom.nextDouble() - 0.5) * 0.4D);
            float recoilPitch = (float) ((secureRandom.nextDouble() - 0.3) * 0.3D);
            client.player.setYRot(client.player.getYRot() + recoilYaw);
            client.player.setXRot(Mth.clamp(client.player.getXRot() + recoilPitch, -89.0F, 89.0F));

            long spectatorCount = client.level.players().stream().filter(p -> p != client.player && p.isSpectator()).count();
            if (spectatorCount > 0 && stealthMode) {
                float extraRecoilYaw = (float) ((secureRandom.nextDouble() - 0.5) * 0.6D);
                float extraRecoilPitch = (float) ((secureRandom.nextDouble() - 0.5) * 0.4D);
                client.player.setYRot(client.player.getYRot() + extraRecoilYaw);
                client.player.setXRot(Mth.clamp(client.player.getXRot() + extraRecoilPitch, -89.0F, 89.0F));
            }

            if (turboMode && secureRandom.nextDouble() < 0.3D) {
                InteractionManager.simulateClickAttack(client);
            }

            if (currentFatigueLevel > 0.8D) {
                randomMissChance = Math.min(0.08D, randomMissChance + 0.001D);
            } else {
                randomMissChance = Math.max(0.01D, randomMissChance - 0.0005D);
            }

            double hitRate = (double) hitCounter / (hitCounter + missCounter + 1);
            if (hitRate > 0.95D) {
                attackStrengthThresholdNormal = Math.min(0.85D, attackStrengthThresholdNormal + 0.005D);
            } else if (hitRate < 0.60D) {
                attackStrengthThresholdNormal = Math.max(0.50D, attackStrengthThresholdNormal - 0.005D);
            }

            if (holdMode && !client.options.keyAttack.isDown()) {
                return;
            }

        } else {
            if (comboBufferTicks == 0) reactionCountdownTicks = 0;
        }

        updateRegistry();
        executeSanitation();
    }

    private static void updateRegistry() {
        TRIGGER_900_REGISTRY.put("TotalFires", totalTriggersFired);
        TRIGGER_900_REGISTRY.put("SessionFires", sessionAttackCounter);
        TRIGGER_900_REGISTRY.put("FatigueLevel", currentFatigueLevel);
        TRIGGER_900_REGISTRY.put("HistorySize", ATTACK_INTERVAL_HISTORY.size());
        TRIGGER_900_REGISTRY.put("StrengthQueueSize", ATTACK_STRENGTH_HISTORY.size());
        TRIGGER_900_REGISTRY.put("HitRate", (double) hitCounter / (hitCounter + missCounter + 1));
    }

    private static void executeSanitation() {
        if (totalTriggersFired > 50000000L) totalTriggersFired = 0L;
        if (TRIGGER_900_REGISTRY.size() > 200) {
            TRIGGER_900_REGISTRY.clear();
            TRIGGER_900_REGISTRY.put("SubsessionUUID", SUBSESSION_UUID);
            TRIGGER_900_REGISTRY.put("Profile", "Swight-TriggerBot-900Lines");
        }
    }

    public static UUID getSubsessionIdentity() {
        return SUBSESSION_UUID;
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
        missCounter = 0;
        hitCounter = 0;
        attacksThisSecond = 0;
        secondCounter = 0;
        ATTACK_INTERVAL_HISTORY.clear();
        CLICK_DURATION_HISTORY.clear();
        ERROR_VECTOR_HISTORY.clear();
        ATTACK_STRENGTH_HISTORY.clear();
        SESSION_TIMESTAMP_HISTORY.clear();
        FATIGUE_HISTORY.clear();
        REACTION_DELAY_HISTORY.clear();
        WEAPON_USAGE_HISTORY.clear();
    }

    public static void executeExtendedDiagnosticFlush() {
        if (ATTACK_INTERVAL_HISTORY.size() > HISTORY_MAX_CAPACITY) ATTACK_INTERVAL_HISTORY.clear();
        if (CLICK_DURATION_HISTORY.size() > HISTORY_MAX_CAPACITY) CLICK_DURATION_HISTORY.clear();
        if (ERROR_VECTOR_HISTORY.size() > HISTORY_MAX_CAPACITY) ERROR_VECTOR_HISTORY.clear();
        if (ATTACK_STRENGTH_HISTORY.size() > HISTORY_MAX_CAPACITY) ATTACK_STRENGTH_HISTORY.clear();
        if (SESSION_TIMESTAMP_HISTORY.size() > HISTORY_MAX_CAPACITY) SESSION_TIMESTAMP_HISTORY.clear();
        if (FATIGUE_HISTORY.size() > HISTORY_MAX_CAPACITY) FATIGUE_HISTORY.clear();
        if (REACTION_DELAY_HISTORY.size() > HISTORY_MAX_CAPACITY) REACTION_DELAY_HISTORY.clear();
        if (WEAPON_USAGE_HISTORY.size() > HISTORY_MAX_CAPACITY) WEAPON_USAGE_HISTORY.clear();
    }

    public static void setAttackReach(double reach) {
        attackReach = reach;
        TRIGGER_900_REGISTRY.put("AttackReach", attackReach);
    }

    public static double getAttackReach() {
        return attackReach;
    }

    public static void setConsistentCritsEnabled(boolean state) {
        consistentCritsEnabled = state;
        TRIGGER_900_REGISTRY.put("ConsistentCrits", consistentCritsEnabled);
    }

    public static boolean isConsistentCritsEnabled() {
        return consistentCritsEnabled;
    }

    public static void setRandomMissChance(double chance) {
        randomMissChance = chance;
        TRIGGER_900_REGISTRY.put("MissChance", randomMissChance);
    }

    public static double getRandomMissChance() {
        return randomMissChance;
    }

    public static void setMinReactionDelayTicks(int ticks) {
        minReactionDelayTicks = Math.max(0, ticks);
        TRIGGER_900_REGISTRY.put("MinReactionDelay", minReactionDelayTicks);
    }

    public static int getMinReactionDelayTicks() {
        return minReactionDelayTicks;
    }

    public static void setMaxReactionDelayTicks(int ticks) {
        maxReactionDelayTicks = Math.max(minReactionDelayTicks, ticks);
        TRIGGER_900_REGISTRY.put("MaxReactionDelay", maxReactionDelayTicks);
    }

    public static int getMaxReactionDelayTicks() {
        return maxReactionDelayTicks;
    }

    public static void setAttackStrengthThresholdNormal(double threshold) {
        attackStrengthThresholdNormal = threshold;
        TRIGGER_900_REGISTRY.put("ThresholdNormal", attackStrengthThresholdNormal);
    }

    public static double getAttackStrengthThresholdNormal() {
        return attackStrengthThresholdNormal;
    }

    public static void setAttackStrengthThresholdCombo(double threshold) {
        attackStrengthThresholdCombo = threshold;
        TRIGGER_900_REGISTRY.put("ThresholdCombo", attackStrengthThresholdCombo);
    }

    public static double getAttackStrengthThresholdCombo() {
        return attackStrengthThresholdCombo;
    }

    public static double getCurrentFatigueLevel() {
        return currentFatigueLevel;
    }

    public static void setCurrentFatigueLevel(double fatigue) {
        currentFatigueLevel = fatigue;
        TRIGGER_900_REGISTRY.put("FatigueLevel", currentFatigueLevel);
    }

    public static int getSessionAttackCounter() {
        return sessionAttackCounter;
    }

    public static void resetSessionAttackCounter() {
        sessionAttackCounter = 0;
        TRIGGER_900_REGISTRY.put("SessionFires", 0);
    }

    public static boolean isErrorInjectionActive() {
        return errorInjectionActive;
    }

    public static void setErrorInjectionActive(boolean state) {
        errorInjectionActive = state;
        TRIGGER_900_REGISTRY.put("ErrorInjection", state);
    }

    public static int getAttackIntervalHistorySize() {
        return ATTACK_INTERVAL_HISTORY.size();
    }

    public static int getAttackStrengthHistorySize() {
        return ATTACK_STRENGTH_HISTORY.size();
    }

    public static int getFatigueHistorySize() {
        return FATIGUE_HISTORY.size();
    }

    public static int getReactionDelayHistorySize() {
        return REACTION_DELAY_HISTORY.size();
    }

    public static int getWeaponUsageHistorySize() {
        return WEAPON_USAGE_HISTORY.size();
    }

    public static void clearAllHistory() {
        ATTACK_INTERVAL_HISTORY.clear();
        CLICK_DURATION_HISTORY.clear();
        ERROR_VECTOR_HISTORY.clear();
        ATTACK_STRENGTH_HISTORY.clear();
        SESSION_TIMESTAMP_HISTORY.clear();
        FATIGUE_HISTORY.clear();
        REACTION_DELAY_HISTORY.clear();
        WEAPON_USAGE_HISTORY.clear();
    }

    public static void forceReset() {
        hardReset();
    }

    public static void setWeaponReachSword(double reach) {
        weaponReachSword = reach;
    }
    public static void setWeaponReachAxe(double reach) {
        weaponReachAxe = reach;
    }
    public static void setWeaponReachTrident(double reach) {
        weaponReachTrident = reach;
    }
    public static void setWeaponReachMace(double reach) {
        weaponReachMace = reach;
    }
    public static double getWeaponReachSword() { return weaponReachSword; }
    public static double getWeaponReachAxe() { return weaponReachAxe; }
    public static double getWeaponReachTrident() { return weaponReachTrident; }
    public static double getWeaponReachMace() { return weaponReachMace; }

    public static void setThresholdSword(double t) { thresholdSword = t; }
    public static void setThresholdAxe(double t) { thresholdAxe = t; }
    public static void setThresholdTrident(double t) { thresholdTrident = t; }
    public static void setThresholdMace(double t) { thresholdMace = t; }
    public static double getThresholdSword() { return thresholdSword; }
    public static double getThresholdAxe() { return thresholdAxe; }
    public static double getThresholdTrident() { return thresholdTrident; }
    public static double getThresholdMace() { return thresholdMace; }

    public static boolean isAntiTeam() { return antiTeam; }
    public static void setAntiTeam(boolean state) { antiTeam = state; }
    public static boolean isAttackMobs() { return attackMobs; }
    public static void setAttackMobs(boolean state) { attackMobs = state; }
    public static boolean isAttackAnimals() { return attackAnimals; }
    public static void setAttackAnimals(boolean state) { attackAnimals = state; }
    public static boolean isOnlyPvP() { return onlyPvP; }
    public static void setOnlyPvP(boolean state) { onlyPvP = state; }
    public static boolean isOnlyPvE() { return onlyPvE; }
    public static void setOnlyPvE(boolean state) { onlyPvE = state; }
    public static boolean isAggressiveMode() { return aggressiveMode; }
    public static void setAggressiveMode(boolean state) { aggressiveMode = state; }
    public static boolean isDefensiveMode() { return defensiveMode; }
    public static void setDefensiveMode(boolean state) { defensiveMode = state; }
    public static boolean isStealthMode() { return stealthMode; }
    public static void setStealthMode(boolean state) { stealthMode = state; }
    public static boolean isPreciseMode() { return preciseMode; }
    public static void setPreciseMode(boolean state) { preciseMode = state; }
    public static boolean isTurboMode() { return turboMode; }
    public static void setTurboMode(boolean state) { turboMode = state; }
    public static boolean isFocusMode() { return focusMode; }
    public static void setFocusMode(boolean state) { focusMode = state; }
    public static boolean isFreeMode() { return freeMode; }
    public static void setFreeMode(boolean state) { freeMode = state; }
    public static boolean isHoldMode() { return holdMode; }
        public static void setHoldMode(boolean state) { holdMode = state; }
    public static boolean isHoldMode() { return holdMode; }

    public static void setToggleMode(boolean state) { toggleMode = state; }
    public static boolean isToggleMode() { return toggleMode; }

    public static void setAntiSpam(boolean state) { antiSpam = state; }
    public static boolean isAntiSpam() { return antiSpam; }

    public static void setMaxAttacksPerSecond(int max) { maxAttacksPerSecond = Math.max(1, max); }
    public static int getMaxAttacksPerSecond() { return maxAttacksPerSecond; }

    public static void setFocusMode(boolean state) { focusMode = state; }
    public static boolean isFocusMode() { return focusMode; }

    public static void setFreeMode(boolean state) { freeMode = state; }
    public static boolean isFreeMode() { return freeMode; }

    public static void setPreciseMode(boolean state) { preciseMode = state; }
    public static boolean isPreciseMode() { return preciseMode; }

    public static void setStealthMode(boolean state) { stealthMode = state; }
    public static boolean isStealthMode() { return stealthMode; }

    public static void setTurboMode(boolean state) { turboMode = state; }
    public static boolean isTurboMode() { return turboMode; }

    public static void setAggressiveMode(boolean state) { aggressiveMode = state; }
    public static boolean isAggressiveMode() { return aggressiveMode; }

    public static void setDefensiveMode(boolean state) { defensiveMode = state; }
    public static boolean isDefensiveMode() { return defensiveMode; }

    public static void setOnlyPvP(boolean state) { onlyPvP = state; }
    public static boolean isOnlyPvP() { return onlyPvP; }

    public static void setOnlyPvE(boolean state) { onlyPvE = state; }
    public static boolean isOnlyPvE() { return onlyPvE; }

    public static void setAttackMobs(boolean state) { attackMobs = state; }
    public static boolean isAttackMobs() { return attackMobs; }

    public static void setAttackAnimals(boolean state) { attackAnimals = state; }
    public static boolean isAttackAnimals() { return attackAnimals; }

    public static void setAntiTeam(boolean state) { antiTeam = state; }
    public static boolean isAntiTeam() { return antiTeam; }
}
