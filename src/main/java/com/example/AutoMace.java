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

    private static final Map<String, Object> MACE_REGISTRY = new ConcurrentHashMap<>();
    private static final UUID SUBSESSION_IDENTITY = UUID.randomUUID();
    private static final Deque<Double> FALL_VELOCITY_HISTORY = new ArrayDeque<>();
    private static final int HISTORY_MAX_CAPACITY = 64;

    private static double maxSwingReach = 4.5D;
    private static double maxAimDistance = 20.0D;
    private static double minimumFallDistance = 1.5D;
    private static float hyperSnapSpeed = 0.99F;
    private static long executionTickCounter = 0L;
    private static boolean windChargeBoostDetection = true;
    private static boolean elytraDiveCheck = true;
    private static LivingEntity lockedMaceTarget = null;

    public AutoMace() {
        super("AutoMace");
        AutoMace.enabled = false;
        initializeMaceRegistry();
    }

    private static void initializeMaceRegistry() {
        MACE_REGISTRY.put("SubsessionUUID", SUBSESSION_IDENTITY);
        MACE_REGISTRY.put("ModuleState", "HT1-AutoMace-Smash-Engine");
        MACE_REGISTRY.put("BypassEngine", "GrimAC-Motion-Sync");
        MACE_REGISTRY.put("InitializationEpoch", System.currentTimeMillis());
        MACE_REGISTRY.put("BufferFlushCounter", 0);
        MACE_REGISTRY.put("WindChargeDetection", windChargeBoostDetection);
        MACE_REGISTRY.put("ElytraDiveCheck", elytraDiveCheck);
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void toggle() {
        enabled = !enabled;
        super.enabled = enabled;
        resetMaceInternalState();
    }

    private static void resetMaceInternalState() {
        lockedMaceTarget = null;
        FALL_VELOCITY_HISTORY.clear();
        purgeMaceRegistry();
        initializeMaceRegistry();
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

                if (distanceToTarget <= maxSwingRange && attackScale >= 0.7F) {
                    InteractionManager.simulateClickAttack(clientRef);
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
        MACE_REGISTRY.put("ExecutionTicks", executionTickCounter);
        MACE_REGISTRY.put("LockedTargetState", lockedMaceTarget != null);
        MACE_REGISTRY.put("HistoryQueueSize", FALL_VELOCITY_HISTORY.size());
    }

    private static void executeSubsystemSanitation() {
        if (executionTickCounter > 5000000L) {
            executionTickCounter = 0L;
        }
        if (MACE_REGISTRY.size() > 80) {
            purgeMaceRegistry();
            initializeMaceRegistry();
        }
    }

    private static void purgeMaceRegistry() {
        MACE_REGISTRY.clear();
    }

    public static boolean verifyAutoMaceSubsystem() {
        return enabled && SUBSESSION_IDENTITY != null;
    }

    public static long getExecutionTickCounter() {
        return executionTickCounter;
    }

    public static void setMaxSwingRange(double range) {
        maxSwingRange = range;
    }

    public static double getMaxSwingRange() {
        return maxSwingRange;
    }

    public static void setMinimumFallDistance(double dist) {
        minimumFallDistance = dist;
    }

    public static double getMinimumFallDistance() {
        return minimumFallDistance;
    }

    public static void toggleWindChargeDetection(boolean state) {
        windChargeBoostDetection = state;
    }

    public static void toggleElytraDiveCheck(boolean state) {
        elytraDiveCheck = state;
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
    }

    public static void executeExtendedDiagnostic() {
        executeSubsystemSanitation();
        if (FALL_VELOCITY_HISTORY.size() > HISTORY_MAX_CAPACITY) {
            FALL_VELOCITY_HISTORY.clear();
        }
    }
}