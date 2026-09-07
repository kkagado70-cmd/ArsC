package com.example;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.Deque;

public class AutoMace extends ClientBase.Module {
    public static final String FILE_NAME = "AutoMace.java";
    public static boolean enabled = false;
    private static KeyMapping toggleKey;
    private static final Random internalRandom = new Random();

    private static final Map<String, Object> MACE_ENTERPRISE_REGISTRY = new ConcurrentHashMap<>();
    private static final UUID SUBSESSION_IDENTITY = UUID.randomUUID();
    private static final Deque<Double> FALL_VELOCITY_HISTORY = new ArrayDeque<>();
    private static final int HISTORY_MAX_CAPACITY = 128;

    private static double maxSwingReach = 4.5D;
    private static double maxAimDistance = 20.0D;
    private static double minimumFallDistance = 1.5D;
    private static float hyperSnapSpeed = 0.99F;
    private static long executionTickCounter = 0L;
    private static boolean windChargeBoostDetection = true;
    private static boolean elytraDiveCheck = true;
    private static LivingEntity lockedMaceTarget = null;
    private static int smashCooldownTracker = 0;

    public AutoMace() {
        super("AutoMace");
        AutoMace.enabled = false;
        initializeMaceEnterpriseRegistry();
    }

    private static void initializeMaceEnterpriseRegistry() {
        MACE_ENTERPRISE_REGISTRY.put("SubsessionUUID", SUBSESSION_IDENTITY);
        MACE_ENTERPRISE_REGISTRY.put("ModuleState", "HT1-Enterprise-AutoMace-Smash-Engine");
        MACE_ENTERPRISE_REGISTRY.put("BypassEngine", "GrimAC-Motion-Sync-Full");
        MACE_ENTERPRISE_REGISTRY.put("InitializationEpoch", System.currentTimeMillis());
        MACE_ENTERPRISE_REGISTRY.put("BufferFlushCounter", 0);
        MACE_ENTERPRISE_REGISTRY.put("WindChargeDetection", windChargeBoostDetection);
        MACE_ENTERPRISE_REGISTRY.put("ElytraDiveCheck", elytraDiveCheck);
        MACE_ENTERPRISE_REGISTRY.put("MaxSwingRange", maxSwingReach);
        MACE_ENTERPRISE_REGISTRY.put("MinFallDistance", minimumFallDistance);
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void toggle() {
        enabled = !enabled;
        super.enabled = enabled;
        resetMaceEnterpriseState();
    }

    private static void resetMaceEnterpriseState() {
        lockedMaceTarget = null;
        smashCooldownTracker = 0;
        FALL_VELOCITY_HISTORY.clear();
        purgeRegistry();
        initializeMaceEnterpriseRegistry();
    }

    @Override
    public void tick(Minecraft clientRef) {
        onTick(clientRef);
    }

    public static void onTick(Minecraft clientRef) {
        if (!enabled || clientRef.player == null || clientRef.level == null) return;
        if (!clientRef.player.isAlive()) return;

        executionTickCounter++;
        executeSubsystemSanitation();

        if (smashCooldownTracker > 0) {
            smashCooldownTracker--;
        }

        double currentVerticalVelocity = clientRef.player.getDeltaMovement().y;
        pushFallVelocityHistory(currentVerticalVelocity);

        LivingEntity target = resolveOptimalMaceTarget(clientRef);
        if (target != null) {
            evaluateSmashConditions(clientRef, target);
        } else {
            lockedMaceTarget = null;
        }

        updateRegistryState();
    }

    private static LivingEntity resolveOptimalMaceTarget(Minecraft clientRef) {
        if (lockedMaceTarget != null) {
            if (lockedMaceTarget.isAlive() && clientRef.player.distanceToSqr(lockedMaceTarget) <= (maxAimDistance * maxAimDistance)) {
                return lockedMaceTarget;
            }
            lockedMaceTarget = null;
        }

        LivingEntity bestTarget = null;
        double minDistanceSqr = (maxAimDistance * maxAimDistance) + 1.0D;

        for (Player player : clientRef.level.players()) {
            if (player == clientRef.player) continue;
            if (!player.isAlive() || player.isSpectator() || player.isCreative()) continue;
            double distSqr = clientRef.player.distanceToSqr(player);
            if (distSqr > (maxAimDistance * maxAimDistance)) continue;

            if (distSqr < minDistanceSqr) {
                minDistanceSqr = distSqr;
                bestTarget = player;
            }
        }

        if (bestTarget != null) {
            lockedMaceTarget = bestTarget;
        }
        return lockedMaceTarget;
    }

    private static void evaluateSmashConditions(Minecraft clientRef, LivingEntity target) {
        double playerFallDistance = clientRef.player.fallDistance;
        boolean isElytraActive = clientRef.player.isFallFlying();
        double verticalVelocityY = clientRef.player.getDeltaMovement().y;
        
        boolean windChargeMomentum = windChargeBoostDetection && verticalVelocityY > 0.8D;
        boolean diveTriggerCondition = playerFallDistance >= minimumFallDistance || (elytraDiveCheck && isElytraActive) || verticalVelocityY < -0.3D || windChargeMomentum;

        if (diveTriggerCondition) {
            int maceSlot = InventoryManager.findItem(clientRef, Items.MACE);
            if (maceSlot != -1) {
                InventoryManager.selectSlot(clientRef, maceSlot);
                
                Vec3 targetCenter = target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D);
                RotationManager.smoothTo(clientRef, targetCenter, hyperSnapSpeed);

                double distanceToTarget = clientRef.player.distanceTo(target);
                float attackScale = clientRef.player.getAttackStrengthScale(0.0F);

                if (distanceToTarget <= maxSwingRange && attackScale >= 0.7F && smashCooldownTracker == 0) {
                    InteractionManager.simulateClickAttack(clientRef);
                    smashCooldownTracker = 4 + internalRandom.nextInt(3);
                }
            }
        }
    }

    private static void pushFallVelocityHistory(double velocity) {
        if (FALL_VELOCITY_HISTORY.size() >= HISTORY_MAX_CAPACITY) {
            FALL_VELOCITY_HISTORY.pollFirst();
        }
        FALL_VELOCITY_HISTORY.offerLast(velocity);
    }

    private static void updateRegistryState() {
        MACE_ENTERPRISE_REGISTRY.put("ExecutionTicks", executionTickCounter);
        MACE_ENTERPRISE_REGISTRY.put("LockedTargetState", lockedMaceTarget != null);
        MACE_ENTERPRISE_REGISTRY.put("HistoryQueueSize", FALL_VELOCITY_HISTORY.size());
        MACE_ENTERPRISE_REGISTRY.put("SmashCooldown", smashCooldownTracker);
    }

    private static void executeSubsystemSanitation() {
        if (executionTickCounter > 5000000L) {
            executionTickCounter = 0L;
        }
        if (MACE_ENTERPRISE_REGISTRY.size() > 80) {
            purgeRegistry();
            initializeMaceEnterpriseRegistry();
        }
    }

    private static void purgeRegistry() {
        MACE_ENTERPRISE_REGISTRY.clear();
    }

    public static boolean verifyAutoMaceSubsystemHealth() {
        return enabled && SUBSESSION_IDENTITY != null;
    }

    public static long getExecutionTickCounter() {
        return executionTickCounter;
    }

    public static void setMaxSwingRange(double range) {
        maxSwingRange = range;
        MACE_ENTERPRISE_REGISTRY.put("MaxSwingRange", maxSwingRange);
    }

    public static double getMaxSwingRange() {
        return maxSwingRange;
    }

    public static void setMinimumFallDistance(double dist) {
        minimumFallDistance = dist;
        MACE_ENTERPRISE_REGISTRY.put("MinFallDistance", minimumFallDistance);
    }

    public static double getMinimumFallDistance() {
        return minimumFallDistance;
    }

    public static void toggleWindChargeDetection(boolean state) {
        windChargeBoostDetection = state;
        MACE_ENTERPRISE_REGISTRY.put("WindChargeDetection", windChargeBoostDetection);
    }

    public static boolean isWindChargeDetectionActive() {
        return windChargeBoostDetection;
    }

    public static void toggleElytraDiveCheck(boolean state) {
        elytraDiveCheck = state;
        MACE_ENTERPRISE_REGISTRY.put("ElytraDiveCheck", elytraDiveCheck);
    }

    public static boolean isElytraDiveCheckActive() {
        return elytraDiveCheck;
    }

    public static int getVelocityHistorySize() {
        return FALL_VELOCITY_HISTORY.size();
    }

    public static void performBaselineCalibration() {
        maxSwingRange = 4.5D;
        maxAimDistance = 20.0D;
        minimumFallDistance = 1.5D;
        hyperSnapSpeed = 0.99F;
        windChargeBoostDetection = true;
        elytraDiveCheck = true;
        smashCooldownTracker = 0;
        executionTickCounter = 0L;
        FALL_VELOCITY_HISTORY.clear();
    }

    public static void executeExtendedDiagnosticFlush() {
        executeSubsystemSanitation();
        if (FALL_VELOCITY_HISTORY.size() > HISTORY_MAX_CAPACITY) {
            FALL_VELOCITY_HISTORY.clear();
        }
    }

    public static UUID getSubsessionIdentity() {
        return SUBSESSION_IDENTITY;
    }
}