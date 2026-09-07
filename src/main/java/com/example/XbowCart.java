package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.Deque;

public class XbowCart extends ClientBase.Module {
    public static final String FILE_NAME = "XbowCart.java";
    public static boolean enabled = false;

    private enum PipelinePhase { VOID, RAIL_ACTION, CART_ACTION, FLINT_ACTION, XBOW_ACTION, CLEANUP }

    private static PipelinePhase currentPhase = PipelinePhase.VOID;
    private static int actionTicks = 0;
    private static BlockPos refPos = null;
    private static Direction refFace = Direction.UP;
    private static Vec3 refVec = null;
    private static final SafetyWatchdog watchdog = new SafetyWatchdog();
    private static final SecureRandom secureRandom = new SecureRandom();

    private static final Map<String, Object> XBOW_REGISTRY = new ConcurrentHashMap<>();
    private static final UUID SUBSESSION_ID = UUID.randomUUID();
    private static final Deque<Long> HISTORY = new ArrayDeque<>();
    private static final int HISTORY_CAP = 512;

    private static long executionCount = 0L;
    private static boolean strictCompliance = true;
    private static int maxRetries = 7;
    private static int retryAttempt = 0;
    private static boolean towerMode = true;
    private static boolean divebombMode = true;
    private static double jitterFactor = 0.012D;
    private static int tickOffset = 2;
    private static boolean emergencyHalt = false;
    private static int anomalyCount = 0;
    private static boolean stealthActive = true;
    private static long lastEpoch = 0L;
    private static boolean packetStrict = true;
    private static double elevationOffset = 0.2D;
    private static boolean dynamicAngles = true;
    private static int sessionExecCount = 0;
    private static boolean antiReplayShield = true;
    private static boolean hardwareBypass = true;
    private static boolean profileLocked = false;
    private static double stochasticVariance = 0.05D;
    private static int emergencyResetThreshold = 100;

    static {
        initializeRegistry();
    }

    private static void initializeRegistry() {
        XBOW_REGISTRY.put("SubsessionUUID", SUBSESSION_ID);
        XBOW_REGISTRY.put("Profile", "Swight-Tier1-XbowCart-FullEnterprise");
        XBOW_REGISTRY.put("TowerMode", towerMode);
        XBOW_REGISTRY.put("DivebombMode", divebombMode);
        XBOW_REGISTRY.put("ExecutionCount", executionCount);
    }

    public XbowCart() {
        super("XbowCart");
        XbowCart.enabled = false;
    }

    @Override
    public boolean isEnabled() { return enabled; }

    @Override
    public void toggle() {
        enabled = !enabled;
        super.enabled = enabled;
        if (!enabled) resetPipeline();
        else resetState();
    }

    private static void resetState() {
        currentPhase = PipelinePhase.VOID;
        actionTicks = 0;
        refPos = null;
        refFace = Direction.UP;
        refVec = null;
        retryAttempt = 0;
        emergencyHalt = false;
        anomalyCount = 0;
        sessionExecCount = 0;
        HISTORY.clear();
        XBOW_REGISTRY.clear();
        initializeRegistry();
    }

    private static void resetPipeline() {
        currentPhase = PipelinePhase.VOID;
        refPos = null;
        refFace = Direction.UP;
        refVec = null;
        actionTicks = 0;
        retryAttempt = 0;
        watchdog.disarm();
    }

    @Override
    public void tick(Minecraft client) { onTick(client); }

    private static boolean isRail(Item item) {
        return item == Items.RAIL || item == Items.POWERED_RAIL || item == Items.DETECTOR_RAIL || item == Items.ACTIVATOR_RAIL;
    }

    public static void onTick(Minecraft client) {
        if (!enabled || client.player == null || client.level == null || !client.player.isAlive()) return;
        if (emergencyHalt || watchdog.isWatchdogLockout()) { resetPipeline(); return; }

        executionCount++;
        sessionExecCount++;

        if (actionTicks > 0) {
            actionTicks--;
            if (actionTicks == 0) advancePhase(client);
            return;
        }

        if (watchdog.isTimedOut()) {
            handleFailure();
            return;
        }

        switch (currentPhase) {
            case VOID:
                HitResult raw = client.hitResult;
                if (raw == null || raw.getType() != HitResult.Type.BLOCK) return;
                if (!(raw instanceof BlockHitResult bHit)) return;
                ItemStack main = client.player.getMainHandItem();
                if (!isRail(main.getItem())) return;
                if (findChargedCrossbow(client) == -1) return;
                if (bHit.getDirection() != Direction.UP) return;

                refPos = bHit.getBlockPos();
                refFace = bHit.getDirection();
                refVec = bHit.getLocation();
                watchdog.arm();
                retryAttempt = 0;
                lastEpoch = System.currentTimeMillis();
                currentPhase = PipelinePhase.RAIL_ACTION;
                break;
            case RAIL_ACTION:
                int rSlot = findRailSlot(client);
                if (rSlot == -1) { handleFailure(); return; }
                Vec3 targetR = refVec != null ? refVec : Vec3.atCenterOf(refPos);
                RotationManager.smoothTo(client, targetR.add(secureRandom.nextDouble() * jitterFactor, secureRandom.nextDouble() * jitterFactor, secureRandom.nextDouble() * jitterFactor), 0.98F);
                selectSlot(client, rSlot);
                InteractionManager.simulateClickUse(client);
                actionTicks = tickOffset + secureRandom.nextInt(2);
                break;
            case CART_ACTION:
                int cSlot = findItem(client, Items.TNT_MINECART);
                if (cSlot == -1) { handleFailure(); return; }
                BlockPos cPos = refFace == Direction.UP ? refPos : refPos.relative(refFace);
                if (towerMode && refFace != Direction.UP) cPos = refPos.above();
                Vec3 targetC = Vec3.atCenterOf(cPos);
                RotationManager.smoothTo(client, targetC.add(secureRandom.nextDouble() * jitterFactor, secureRandom.nextDouble() * jitterFactor, secureRandom.nextDouble() * jitterFactor), 0.98F);
                selectSlot(client, cSlot);
                InteractionManager.simulateClickUse(client);
                actionTicks = tickOffset + secureRandom.nextInt(2);
                break;
            case FLINT_ACTION:
                int fSlot = findItem(client, Items.FLINT_AND_STEEL);
                if (fSlot == -1) fSlot = findItem(client, Items.FIRE_CHARGE);
                if (fSlot == -1) { handleFailure(); return; }
                BlockPos fPos = refFace == Direction.UP ? refPos.relative(client.player.getDirection().getOpposite()) : refPos;
                Vec3 targetF = Vec3.atCenterOf(fPos);
                RotationManager.smoothTo(client, targetF.add(secureRandom.nextDouble() * jitterFactor, secureRandom.nextDouble() * jitterFactor, secureRandom.nextDouble() * jitterFactor), 0.98F);
                selectSlot(client, fSlot);
                InteractionManager.simulateClickUse(client);
                actionTicks = tickOffset + secureRandom.nextInt(2);
                break;
            case XBOW_ACTION:
                int xSlot = findChargedCrossbow(client);
                if (xSlot == -1) { handleFailure(); return; }
                BlockPos sPos = refFace == Direction.UP ? refPos : refPos.relative(refFace);
                Vec3 targetS = Vec3.atCenterOf(sPos).add(0.0D, elevationOffset, 0.0D);
                RotationManager.smoothTo(client, targetS.add(secureRandom.nextDouble() * jitterFactor, secureRandom.nextDouble() * jitterFactor, secureRandom.nextDouble() * jitterFactor), 0.98F);
                selectSlot(client, xSlot);
                InteractionManager.simulateClickUse(client);
                actionTicks = tickOffset + secureRandom.nextInt(2);
                break;
            case CLEANUP:
                resetPipeline();
                break;
        }
        updateRegistry();
    }

    private static void handleFailure() {
        retryAttempt++;
        anomalyCount++;
        if (retryAttempt <= maxRetries) actionTicks = 3;
        else { emergencyHalt = true; resetPipeline(); }
    }

    private static void advancePhase(Minecraft client) {
        if (client != null && client.options != null) client.options.keyUse.setDown(false);
        switch (currentPhase) {
            case RAIL_ACTION: currentPhase = PipelinePhase.CART_ACTION; break;
            case CART_ACTION: currentPhase = PipelinePhase.FLINT_ACTION; break;
            case FLINT_ACTION: currentPhase = PipelinePhase.XBOW_ACTION; break;
            case XBOW_ACTION: currentPhase = PipelinePhase.CLEANUP; break;
            default: resetPipeline(); break;
        }
        if (HISTORY.size() >= HISTORY_CAP) HISTORY.pollFirst();
        HISTORY.offerLast(System.currentTimeMillis());
        updateRegistry();
    }

    private static int findRailSlot(Minecraft client) {
        if (client.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            if (isRail(client.player.getInventory().getItem(i).getItem())) return i;
        }
        return -1;
    }

    private static int findItem(Minecraft client, Item item) {
        if (client.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            if (client.player.getInventory().getItem(i).getItem() == item) return i;
        }
        return -1;
    }

    private static int findChargedCrossbow(Minecraft client) {
        if (client.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            ItemStack s = client.player.getInventory().getItem(i);
            if (s.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(s)) return i;
        }
        return -1;
    }

    private static void selectSlot(Minecraft client, int slot) {
        if (client.player == null || slot < 0 || slot > 8) return;
        client.player.getInventory().setSelectedSlot(slot);
        if (client.options.keyHotbarSlots[slot] != null) {
            client.options.keyHotbarSlots[slot].setDown(true);
            client.options.keyHotbarSlots[slot].setDown(false);
        }
    }

    private static void updateRegistry() {
        XBOW_REGISTRY.put("ExecutionCount", executionCount);
        XBOW_REGISTRY.put("Stage", currentPhase.name());
        XBOW_REGISTRY.put("RetryAttempt", retryAttempt);
        XBOW_REGISTRY.put("SessionExec", sessionExecCount);
    }

    public static boolean verifySubsystemHealth() { return enabled && SUBSESSION_ID != null; }
    public static long getExecutionCount() { return executionCount; }
    public static UUID getSubsessionIdentity() { return SUBSESSION_ID; }
}