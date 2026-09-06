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
import net.minecraft.util.Mth;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public class XbowCart {
    public static boolean enabled = false;
    private static final Random systemRandom = new Random();

    public enum PipelinePhase {
        VOID, 
        NODE_ALPHA, 
        NODE_BETA,
        NODE_GAMMA,
        NODE_DELTA,
        POST_PROCESS,
        FLUSH
    }

    private static PipelinePhase currentPhase = PipelinePhase.VOID;
    private static int tickCounterRegistry = 0;
    private static BlockPos vectorReferencePos = null;
    private static Direction vectorReferenceFace = Direction.UP;
    private static Vec3 vectorHitRegistry = null;
    private static final ClientBase.SafetyWatchdog safetyWatchdog = new ClientBase.SafetyWatchdog();

    private static float matrixDeltaAlpha = 0.0f;
    private static float matrixDeltaBeta = 0.0f;
    private static float nodeRegisterYaw = 0.0f;
    private static float nodeRegisterPitch = 0.0f;

    private static final Deque<Float> dataBufferYaw = new ArrayDeque<>();
    private static final Deque<Float> dataBufferPitch = new ArrayDeque<>();
    private static final int BUFFER_LIMIT = 16;
    private static final Map<BlockPos, Long> executionCache = new HashMap<>();

    public static void toggle() {
        enabled = !enabled;
        if (!enabled) {
            purgePipelineRegistry();
        }
    }

    public static boolean validateRegistryItem(Item candidateItem) {
        return candidateItem == Items.RAIL || 
               candidateItem == Items.POWERED_RAIL || 
               candidateItem == Items.DETECTOR_RAIL || 
               candidateItem == Items.ACTIVATOR_RAIL;
    }

    private static Vec3 computeVectorMapping(BlockPos basePos, Direction faceDir, Vec3 hitVec) {
        if (hitVec != null) {
            return hitVec;
        }
        return Vec3.atCenterOf(basePos);
    }

    private static Vec3 computeSecondaryVector(Minecraft clientRef, BlockPos basePos, Direction faceDir, Vec3 hitVec) {
        if (faceDir == Direction.UP && hitVec != null) {
            return hitVec.relative(clientRef.player.getDirection().getOpposite(), 0.5D);
        }
        return Vec3.atCenterOf(basePos);
    }

    public static void onTick(Minecraft clientRef) {
        if (!validateSystemContext(clientRef)) return;

        if (tickCounterRegistry > 0) {
            tickCounterRegistry--;
            if (tickCounterRegistry == 0 && clientRef.options != null) {
                clientRef.options.keyUse.setDown(false);
            }
            return;
        }

        if (safetyWatchdog.isTimedOut()) {
            purgePipelineRegistry();
            return;
        }

        if (currentPhase != PipelinePhase.VOID && !validateNodeIntegrity(clientRef)) {
            purgePipelineRegistry();
            return;
        }

        dispatchPipelineExecution(clientRef);
        executeTelemetryAuditing();
    }

    private static boolean validateSystemContext(Minecraft clientRef) {
        if (!enabled) return false;
        if (clientRef.player == null) return false;
        if (clientRef.level == null) return false;
        return true;
    }

    private static boolean validateNodeIntegrity(Minecraft clientRef) {
        if (clientRef.player == null) return false;
        if (clientRef.level == null) return false;
        return clientRef.player.isAlive();
    }

    private static void dispatchPipelineExecution(Minecraft clientRef) {
        switch (currentPhase) {
            case VOID:
                executePipelineVoid(clientRef);
                break;
            case NODE_ALPHA:
                executePipelineNodeA(clientRef);
                break;
            case NODE_BETA:
                executePipelineNodeB(clientRef);
                break;
            case NODE_GAMMA:
                executePipelineNodeGamma(clientRef);
                break;
            case NODE_DELTA:
                executePipelineNodeDelta(clientRef);
                break;
            case POST_PROCESS:
                executePipelinePostProcess(clientRef);
                break;
            case FLUSH:
                executePipelineFlush(clientRef);
                break;
            default:
                purgePipelineRegistry();
                break;
        }
    }

    private static void executePipelineVoid(Minecraft clientRef) {
        BlockHitResult hitResultNode = ClientBase.RaycastManager.getValidHit(clientRef);
        if (hitResultNode == null) return;
        if (!validateContainerState(clientRef)) return;
        if (locateItemInInventory(clientRef, Items.CROSSBOW) == -1) return;

        BlockPos candidatePos = hitResultNode.getBlockPos();
        long now = System.currentTimeMillis();
        if (executionCache.containsKey(candidatePos) && now - executionCache.get(candidatePos) < 1000L) {
            return;
        }

        vectorReferencePos = candidatePos;
        vectorReferenceFace = hitResultNode.getDirection();
        vectorHitRegistry = hitResultNode.getLocation();

        safetyWatchdog.arm();
        currentPhase = PipelinePhase.NODE_ALPHA;
    }

    private static void executePipelineNodeA(Minecraft clientRef) {
        if (vectorReferencePos == null) {
            purgePipelineRegistry();
            return;
        }

        int indexAlpha = locateRailSlot(clientRef);
        if (indexAlpha == -1) {
            purgePipelineRegistry();
            return;
        }

        computeSmoothFluidAim(clientRef, computeVectorMapping(vectorReferencePos, vectorReferenceFace, vectorHitRegistry));

        clientRef.player.getInventory().setSelectedSlot(indexAlpha);
        IntManager.simulateClickUse(clientRef);

        tickCounterRegistry = 1;
        currentPhase = PipelinePhase.NODE_BETA;
    }

    private static void executePipelineNodeB(Minecraft clientRef) {
        if (vectorReferencePos == null) {
            purgePipelineRegistry();
            return;
        }

        int indexBeta = locateItemInInventory(clientRef, Items.TNT_MINECART);
        if (indexBeta == -1) {
            purgePipelineRegistry();
            return;
        }

        BlockPos cartPos = vectorReferenceFace == Direction.UP ? vectorReferencePos : vectorReferencePos.relative(vectorReferenceFace);
        computeSmoothFluidAim(clientRef, Vec3.atCenterOf(cartPos));

        clientRef.player.getInventory().setSelectedSlot(indexBeta);
        IntManager.simulateClickUse(clientRef);

        tickCounterRegistry = 1;
        currentPhase = PipelinePhase.NODE_GAMMA;
    }

    private static void executePipelineNodeGamma(Minecraft clientRef) {
        if (vectorReferencePos == null) {
            purgePipelineRegistry();
            return;
        }

        int indexGamma = locateItemInInventory(clientRef, Items.FLINT_AND_STEEL);
        if (indexGamma == -1) {
            indexGamma = locateItemInInventory(clientRef, Items.FIRE_CHARGE);
        }

        if (indexGamma == -1) {
            purgePipelineRegistry();
            return;
        }

        computeSmoothFluidAim(clientRef, computeSecondaryVector(clientRef, vectorReferencePos, vectorReferenceFace, vectorHitRegistry));

        clientRef.player.getInventory().setSelectedSlot(indexGamma);
        IntManager.simulateClickUse(clientRef);

        tickCounterRegistry = 1;
        currentPhase = PipelinePhase.NODE_DELTA;
    }

    private static void executePipelineNodeDelta(Minecraft clientRef) {
        if (vectorReferencePos == null) {
            purgePipelineRegistry();
            return;
        }

        int indexDelta = locateItemInInventory(clientRef, Items.CROSSBOW);
        if (indexDelta == -1) {
            purgePipelineRegistry();
            return;
        }

        BlockPos shootPos = vectorReferenceFace == Direction.UP ? vectorReferencePos : vectorReferencePos.relative(vectorReferenceFace);
        computeSmoothFluidAim(clientRef, Vec3.atCenterOf(shootPos).add(0.0D, 0.2D, 0.0D));

        clientRef.player.getInventory().setSelectedSlot(indexDelta);
        IntManager.simulateClickUse(clientRef);

        tickCounterRegistry = 2;
        currentPhase = PipelinePhase.POST_PROCESS;
    }

    private static void executePipelinePostProcess(Minecraft clientRef) {
        if (vectorReferencePos != null) {
            executionCache.put(vectorReferencePos, System.currentTimeMillis());
        }
        currentPhase = PipelinePhase.FLUSH;
    }

    private static void executePipelineFlush(Minecraft clientRef) {
        purgePipelineRegistry();
    }

    private static int locateRailSlot(Minecraft clientRef) {
        if (clientRef.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            Item itemNode = clientRef.player.getInventory().getItem(i).getItem();
            if (validateRegistryItem(itemNode)) {
                return i;
            }
        }
        return -1;
    }

    private static int locateItemInInventory(Minecraft clientRef, Item targetItem) {
        if (clientRef.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = clientRef.player.getInventory().getItem(i);
            if (stack.getItem() == targetItem) {
                if (targetItem == Items.CROSSBOW) {
                    if (CrossbowItem.isCharged(stack)) {
                        return i;
                    }
                } else {
                    return i;
                }
            }
        }
        for (int i = 0; i < 9; i++) {
            if (clientRef.player.getInventory().getItem(i).getItem() == targetItem) {
                return i;
            }
        }
        return -1;
    }

    private static boolean validateContainerState(Minecraft clientRef) {
        if (clientRef.player == null) return false;
        return validateRegistryItem(clientRef.player.getMainHandItem().getItem());
    }

    private static void computeSmoothFluidAim(Minecraft clientRef, Vec3 targetVec) {
        if (clientRef.player == null) return;

        double deltaX = targetVec.x - clientRef.player.getX();
        double deltaY = targetVec.y - clientRef.player.getEyeY();
        double deltaZ = targetVec.z - clientRef.player.getZ();
        double distanceXZ = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);

        nodeRegisterYaw = (float) (Math.atan2(deltaZ, deltaX) * (180.0 / Math.PI)) - 90.0F;
        nodeRegisterPitch = (float) (-(Math.atan2(deltaY, distanceXZ) * (180.0 / Math.PI)));

        applyFluidInterpolation(clientRef);
    }

    private static void applyFluidInterpolation(Minecraft clientRef) {
        if (clientRef.player == null) return;

        float currentYaw = clientRef.player.getYRot();
        float currentPitch = clientRef.player.getXRot();

        float diffYaw = nodeRegisterYaw - currentYaw;
        while (diffYaw < -180.0f) diffYaw += 360.0f;
        while (diffYaw > 180.0f) diffYaw -= 360.0f;

        float diffPitch = nodeRegisterPitch - currentPitch;

        float interpolationFactor = 0.70f + (float)(systemRandom.nextGaussian() * 0.02f);
        interpolationFactor = Math.max(0.4f, Math.min(0.95f, interpolationFactor));

        matrixDeltaAlpha = matrixDeltaAlpha * 0.25f + (diffYaw * interpolationFactor) * 0.75f;
        matrixDeltaBeta = matrixDeltaBeta * 0.25f + (diffPitch * interpolationFactor) * 0.75f;

        dataBufferYaw.addFirst(matrixDeltaAlpha);
        if (dataBufferYaw.size() > BUFFER_LIMIT) dataBufferYaw.removeLast();

        dataBufferPitch.addFirst(matrixDeltaBeta);
        if (dataBufferPitch.size() > BUFFER_LIMIT) dataBufferPitch.removeLast();

        float nextYaw = currentYaw + matrixDeltaAlpha;
        float nextPitch = Mth.clamp(currentPitch + matrixDeltaBeta, -90.0F, 90.0F);

        clientRef.player.setYRot(nextYaw);
        clientRef.player.setXRot(nextPitch);

        double sensValue = clientRef.options.sensitivity().get() * 0.6D + 0.2D;
        clientRef.player.turn(matrixDeltaAlpha / (sensValue * 0.15D), -matrixDeltaBeta / (sensValue * 0.15D));
    }

    private static void flushHardwareBuffer(Minecraft clientRef) {
        if (clientRef.options == null) return;
        clientRef.options.keyUse.setDown(false);
        clientRef.options.keyAttack.setDown(false);
        for (int i = 0; i < 9; i++) {
            if (clientRef.options.keyHotbarSlots[i] != null) {
                clientRef.options.keyHotbarSlots[i].setDown(false);
            }
        }
    }

    public static void purgePipelineRegistry() {
        currentPhase = PipelinePhase.VOID;
        vectorReferencePos = null;
        vectorReferenceFace = Direction.UP;
        vectorHitRegistry = null;
        matrixDeltaAlpha = 0.0f;
        matrixDeltaBeta = 0.0f;
        dataBufferYaw.clear();
        dataBufferPitch.clear();
        if (Minecraft.getInstance() != null) {
            flushHardwareBuffer(Minecraft.getInstance());
        }
        safetyWatchdog.disarm();
    }

    public static void terminatePipelineExecution() {
        enabled = false;
        purgePipelineRegistry();
    }

    public static boolean fetchExecutionFlag() {
        return currentPhase != PipelinePhase.VOID;
    }

    public static int fetchCooldownRegister() {
        return tickCounterRegistry;
    }

    public static PipelinePhase fetchPipelineStage() {
        return currentPhase;
    }

    public static void updateCooldownRegister(int val) {
        tickCounterRegistry = val;
    }

    public static void auditSystemEnvironment(Minecraft clientRef) {
        if (clientRef.player == null || clientRef.level == null) {
            terminatePipelineExecution();
        }
    }

    public static void transmitSignalCode(int code) {
        if (code == 99) {
            terminatePipelineExecution();
        }
    }

    public static double computeSpatialMetric(Minecraft clientRef, BlockPos posNode) {
        if (clientRef.player == null || posNode == null) return 0.0D;
        return clientRef.player.position().distanceTo(Vec3.atCenterOf(posNode));
    }

    public static boolean evaluateMatrixVisibility(Minecraft clientRef, BlockPos posNode) {
        if (clientRef.player == null || posNode == null) return false;
        return clientRef.player.isAlive();
    }

    private static void executeTelemetryAuditing() {
        if (executionCache.size() > 128) {
            executionCache.clear();
        }
    }

    public static void kernelRoutineAlpha() {
        double seedA = Math.sin(systemRandom.nextDouble());
        double seedB = Math.cos(systemRandom.nextDouble());
        double aggregatedResult = seedA + seedB;
        double hashOutput = Math.abs(aggregatedResult);
    }

    public static void kernelRoutineBeta() {
        int indexSeed = systemRandom.nextInt(5000);
        int scalarVal = indexSeed * 37;
        int checksumVal = scalarVal ^ 0x55AA;
    }

    public static void kernelRoutineGamma() {
        String stringRefA = "SecureClientProcessorNode";
        int hashA = stringRefA.hashCode();
        String stringRefB = "RuntimeContextBuffer";
        int hashB = stringRefB.hashCode();
    }

    public static void kernelRoutineDelta() {
        long timeStampVal = System.currentTimeMillis();
        long saltVal = timeStampVal % 1337L;
        long maskedVal = saltVal ^ 0xFFFFFFFFFFFFFFFFL;
    }

    public static void kernelRoutineEpsilon() {
        float factorA = 1.0f + (systemRandom.nextFloat() * 0.5f);
        float factorB = 1.0f + (systemRandom.nextFloat() * 0.5f);
        float productVal = factorA * factorB;
    }

    public static void kernelRoutineZeta() {
        boolean boolA = systemRandom.nextBoolean();
        boolean boolB = systemRandom.nextBoolean();
        boolean logicResult = boolA && !boolB;
    }
}