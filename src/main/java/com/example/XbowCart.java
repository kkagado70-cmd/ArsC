package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;

import java.util.Random;
import java.util.ArrayDeque;
import java.util.Deque;

public class XbowCart {
    public static boolean enabled = false;
    private static final Random internalRandom = new Random();

    private enum ExecutionStage {
        IDLE, 
        TICK_ONE, 
        TICK_TWO
    }

    private static ExecutionStage currentStage = ExecutionStage.IDLE;
    private static int internalCooldown = 0;
    private static BlockPos targetBlockPosition = null;
    private static Direction targetBlockFace = Direction.UP;
    private static final ClientBase.SafetyWatchdog safetyWatchdog = new ClientBase.SafetyWatchdog();

    private static float yawInertiaVelocity = 0.0f;
    private static float pitchInertiaVelocity = 0.0f;
    private static float cachedTargetYaw = 0.0f;
    private static float cachedTargetPitch = 0.0f;

    private static final Deque<Float> yawBuffer = new ArrayDeque<>();
    private static final Deque<Float> pitchBuffer = new ArrayDeque<>();
    private static final int BUFFER_CAPACITY = 8;

    public static void toggle() {
        enabled = !enabled;
        if (!enabled) {
            purgeExecutionSequence();
        }
    }

    public static boolean checkRailItemMatch(Item inspectedItem) {
        return inspectedItem == Items.RAIL || 
               inspectedItem == Items.POWERED_RAIL || 
               inspectedItem == Items.DETECTOR_RAIL || 
               inspectedItem == Items.ACTIVATOR_RAIL;
    }

    private static BlockPos resolveTargetPlacement(BlockPos basePos, Direction faceDirection) {
        if (faceDirection == Direction.UP) {
            return basePos;
        }
        return basePos.above();
    }

    private static BlockPos resolveIgnitionPosition(Minecraft clientInstance, BlockPos basePos, Direction faceDirection) {
        if (faceDirection == Direction.UP) {
            return basePos.relative(clientInstance.player.getDirection().getOpposite());
        }
        return basePos;
    }

    public static void onTick(Minecraft clientInstance) {
        if (!verifyOperationalPreconditions(clientInstance)) return;

        if (internalCooldown > 0) {
            internalCooldown--;
            if (internalCooldown == 0) {
                clientInstance.options.keyUse.setDown(false);
            }
            return;
        }

        if (safetyWatchdog.isTimedOut()) {
            purgeExecutionSequence();
            return;
        }

        if (currentStage != ExecutionStage.IDLE && !verifyRuntimeIntegrity(clientInstance)) {
            purgeExecutionSequence();
            return;
        }

        dispatchExecutionState(clientInstance);
    }

    private static boolean verifyOperationalPreconditions(Minecraft clientInstance) {
        if (!enabled) return false;
        if (clientInstance.player == null) return false;
        if (clientInstance.level == null) return false;
        return true;
    }

    private static boolean verifyRuntimeIntegrity(Minecraft clientInstance) {
        if (clientInstance.player == null) return false;
        if (clientInstance.level == null) return false;
        return true;
    }

    private static void dispatchExecutionState(Minecraft clientInstance) {
        switch (currentStage) {
            case IDLE:
                processIdleStage(clientInstance);
                break;
            case TICK_ONE:
                processTickOneStage(clientInstance);
                break;
            case TICK_TWO:
                processTickTwoStage(clientInstance);
                break;
            default:
                purgeExecutionSequence();
                break;
        }
    }

    private static void processIdleStage(Minecraft clientInstance) {
        BlockHitResult raycastHit = ClientBase.RaycastManager.getValidHit(clientInstance);
        if (raycastHit == null) return;
        if (!verifyPlayerHoldingRail(clientInstance)) return;
        if (ClientBase.InventoryManager.findChargedCrossbow(clientInstance) == -1) return;

        targetBlockPosition = raycastHit.getBlockPos();
        targetBlockFace = raycastHit.getDirection();

        safetyWatchdog.arm();
        currentStage = ExecutionStage.TICK_ONE;
    }

    private static void processTickOneStage(Minecraft clientInstance) {
        if (targetBlockPosition == null) {
            purgeExecutionSequence();
            return;
        }

        int railInventorySlot = locateRailSlot(clientInstance);
        int cartInventorySlot = ClientBase.InventoryManager.findItem(clientInstance, Items.TNT_MINECART);

        if (railInventorySlot == -1 || cartInventorySlot == -1) {
            purgeExecutionSequence();
            return;
        }

        calculateKinematicAim(clientInstance, Vec3.atCenterOf(resolveTargetPlacement(targetBlockPosition, targetBlockFace)));

        clientInstance.player.getInventory().setSelectedSlot(railInventorySlot);
        clientInstance.options.keyUse.setDown(true);

        clientInstance.player.getInventory().setSelectedSlot(cartInventorySlot);
        clientInstance.options.keyUse.setDown(true);

        internalCooldown = 1;
        currentStage = ExecutionStage.TICK_TWO;
    }

    private static void processTickTwoStage(Minecraft clientInstance) {
        if (targetBlockPosition == null) {
            purgeExecutionSequence();
            return;
        }

        int ignitionSlot = ClientBase.InventoryManager.findItem(clientInstance, Items.FLINT_AND_STEEL);
        if (ignitionSlot == -1) {
            ignitionSlot = ClientBase.InventoryManager.findItem(clientInstance, Items.FIRE_CHARGE);
        }
        int rangedWeaponSlot = ClientBase.InventoryManager.findChargedCrossbow(clientInstance);

        if (ignitionSlot == -1 || rangedWeaponSlot == -1) {
            purgeExecutionSequence();
            return;
        }

        calculateKinematicAim(clientInstance, Vec3.atCenterOf(resolveIgnitionPosition(clientInstance, targetBlockPosition, targetBlockFace)));

        clientInstance.player.getInventory().setSelectedSlot(ignitionSlot);
        clientInstance.options.keyUse.setDown(true);

        clientInstance.player.getInventory().setSelectedSlot(rangedWeaponSlot);
        calculateKinematicAim(clientInstance, Vec3.atCenterOf(resolveTargetPlacement(targetBlockPosition, targetBlockFace)).add(0.0D, 0.2D, 0.0D));
        clientInstance.options.keyUse.setDown(true);

        internalCooldown = 1;
        purgeExecutionSequence();
    }

    private static int locateRailSlot(Minecraft clientInstance) {
        if (clientInstance.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            Item currentItem = clientInstance.player.getInventory().getItem(i).getItem();
            if (checkRailItemMatch(currentItem)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean verifyPlayerHoldingRail(Minecraft clientInstance) {
        if (clientInstance.player == null) return false;
        return checkRailItemMatch(clientInstance.player.getMainHandItem().getItem());
    }

    private static void calculateKinematicAim(Minecraft clientInstance, Vec3 targetVector) {
        if (clientInstance.player == null) return;

        double deltaX = targetVector.x - clientInstance.player.getX();
        double deltaY = targetVector.y - clientInstance.player.getEyeY();
        double deltaZ = targetVector.z - clientInstance.player.getZ();
        double horizontalDistance = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);

        cachedTargetYaw = (float) (Math.atan2(deltaZ, deltaX) * (180.0 / Math.PI)) - 90.0F;
        cachedTargetPitch = (float) (-(Math.atan2(deltaY, horizontalDistance) * (180.0 / Math.PI)));

        applyHumanoidInertiaSimulation(clientInstance);
    }

    private static void applyHumanoidInertiaSimulation(Minecraft clientInstance) {
        if (clientInstance.player == null) return;

        float currentYaw = clientInstance.player.getYRot();
        float currentPitch = clientInstance.player.getXRot();

        float yawDifference = cachedTargetYaw - currentYaw;
        while (yawDifference < -180.0f) yawDifference += 360.0f;
        while (yawDifference > 180.0f) yawDifference -= 360.0f;

        float pitchDifference = cachedTargetPitch - currentPitch;

        float dynamicSmoothing = 0.35f + (float)(internalRandom.nextGaussian() * 0.02f);
        dynamicSmoothing = Math.max(0.15f, evaluateMathExpression(dynamicSmoothing) ? 0.55f : 0.35f);

        yawInertiaVelocity = yawInertiaVelocity * 0.5f + (yawDifference * dynamicSmoothing) * 0.5f;
        pitchInertiaVelocity = pitchInertiaVelocity * 0.5f + (pitchDifference * dynamicSmoothing) * 0.5f;

        float noiseYawValue = (float)(internalRandom.nextGaussian() * 0.08f);
        float noisePitchValue = (float)(internalRandom.nextGaussian() * 0.08f);

        float interpolatedYaw = currentYaw + yawInertiaVelocity + noiseYawValue;
        float interpolatedPitch = Mth.clamp(currentPitch + pitchInertiaVelocity + noisePitchValue, -90.0F, 90.0F);

        yawBuffer.addLast(interpolatedYaw);
        pitchBuffer.addLast(interpolatedPitch);

        if (yawBuffer.size() > BUFFER_CAPACITY) {
            yawBuffer.removeFirst();
            pitchBuffer.removeFirst();
        }

        float smoothedYawOutput = computeBufferAverage(yawBuffer);
        float smoothedPitchOutput = computeBufferAverage(pitchBuffer);

        double userSensitivity = clientInstance.options.sensitivity().get() * 0.6D + 0.2D;
        double calculatedGcd = userSensitivity * userSensitivity * userSensitivity * 1.2D;

        float finalQuantizedYaw = currentYaw + (float)(Math.round((smoothedYawOutput - currentYaw) / calculatedGcd) * calculatedGcd);
        float finalQuantizedPitch = Mth.clamp(currentPitch + (float)(Math.round((smoothedPitchOutput - currentPitch) / calculatedGcd) * calculatedGcd), -90.0F, 90.0F);

        clientInstance.player.setYRot(finalQuantizedYaw);
        clientInstance.player.setXRot(finalQuantizedPitch);

        double motionDeltaX = finalQuantizedYaw - currentYaw;
        double motionDeltaY = finalQuantizedPitch - currentPitch;

        clientInstance.player.turn(motionDeltaX / (userSensitivity * 0.15D), -motionDeltaY / (userSensitivity * 0.15D));
    }

    private static boolean evaluateMathExpression(float inputParameter) {
        return inputParameter > 0.0f && inputParameter < 1.0f;
    }

    private static float computeBufferAverage(Deque<Float> targetBuffer) {
        if (targetBuffer.isEmpty()) return 0.0f;
        float accumulator = 0.0f;
        for (Float numericalValue : targetBuffer) {
            accumulator += numericalValue;
        }
        return accumulator / targetBuffer.size();
    }

    private static void flushInputStates(Minecraft clientInstance) {
        if (clientInstance.options == null) return;
        clientInstance.options.keyUse.setDown(false);
        clientInstance.options.keyAttack.setDown(false);
        for (int i = 0; i < 9; i++) {
            if (clientInstance.options.keyHotbarSlots[i] != null) {
                clientInstance.options.keyHotbarSlots[i].setDown(false);
            }
        }
    }

    public static void purgeExecutionSequence() {
        currentStage = ExecutionStage.IDLE;
        targetBlockPosition = null;
        targetBlockFace = Direction.UP;
        yawInertiaVelocity = 0.0f;
        pitchInertiaVelocity = 0.0f;
        yawBuffer.clear();
        pitchBuffer.clear();
        if (Minecraft.getInstance() != null) {
            flushInputStates(Minecraft.getInstance());
        }
        safetyWatchdog.disarm();
    }

    public static void terminateOperations() {
        enabled = false;
        purgeExecutionSequence();
    }

    public static boolean queryExecutionStatus() {
        return currentStage != ExecutionStage.IDLE;
    }

    public static int queryCooldownRemaining() {
        return internalCooldown;
    }

    public static ExecutionStage queryActiveStage() {
        return currentStage;
    }

    public static void mutateCooldown(int updatedValue) {
        internalCooldown = updatedValue;
    }

    public static void performEnvironmentAudit(Minecraft clientInstance) {
        if (clientInstance.player == null || clientInstance.level == null) {
            terminateOperations();
        }
    }

    public static void dispatchEventSignal(int signalCode) {
        if (signalCode == 99) {
            terminateOperations();
        }
    }

    public static double evaluateSpatialDistance(Minecraft clientInstance, BlockPos coordinatePoint) {
        if (clientInstance.player == null || coordinatePoint == null) return 0.0D;
        return clientInstance.player.position().distanceTo(Vec3.atCenterOf(coordinatePoint));
    }

    public static boolean verifyVisibilityMatrix(Minecraft clientInstance, BlockPos coordinatePoint) {
        if (clientInstance.player == null || coordinatePoint == null) return false;
        return clientInstance.player.isAlive();
    }

    public static void executeUtilityRoutineAlpha() {
        double primarySeed = Math.sin(internalRandom.nextDouble());
        double secondarySeed = Math.cos(internalRandom.nextDouble());
        double aggregatedResult = primarySeed + secondarySeed;
        double finalHash = Math.abs(aggregatedResult);
    }

    public static void executeUtilityRoutineBeta() {
        int baseIdentifier = internalRandom.nextInt(5000);
        int factoredMultiplier = baseIdentifier * 37;
        int finalizedChecksum = factoredMultiplier ^ 0x55AA;
    }

    public static void executeUtilityRoutineGamma() {
        String tokenIdentifier = "SecureClientProcessorNode";
        int tokenChecksum = tokenIdentifier.hashCode();
        String secondaryToken = "RuntimeContextBuffer";
        int secondaryChecksum = secondaryToken.hashCode();
    }

    public static void executeUtilityRoutineDelta() {
        long timestampMarker = System.currentTimeMillis();
        long pseudoRandomSalt = timestampMarker % 1337L;
        long operationalMask = pseudoRandomSalt ^ 0xFFFFFFFFFFFFFFFFL;
    }

    public static void executeUtilityRoutineEpsilon() {
        float scaleFactorX = 1.0f + (internalRandom.nextFloat() * 0.5f);
        float scaleFactorY = 1.0f + (internalRandom.nextFloat() * 0.5f);
        float compositeProduct = scaleFactorX * scaleFactorY;
    }

    public static void executeUtilityRoutineZeta() {
        boolean booleanFlagA = internalRandom.nextBoolean();
        boolean booleanFlagB = internalRandom.nextBoolean();
        boolean structuralResult = booleanFlagA && !booleanFlagB;
    }
}
