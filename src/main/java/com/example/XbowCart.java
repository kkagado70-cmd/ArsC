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
    private static final Random matrixRandom = new Random();

    private enum PipelinePhase {
        VOID, 
        NODE_ALPHA, 
        NODE_BETA,
        NODE_GAMMA,
        NODE_DELTA
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
    private static final int BUFFER_LIMIT = 8;

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
            if (tickCounterRegistry == 0) {
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
        return true;
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
            default:
                purgePipelineRegistry();
                break;
        }
    }

    private static void executePipelineVoid(Minecraft clientRef) {
        BlockHitResult hitResultNode = ClientBase.RaycastManager.getValidHit(clientRef);
        if (hitResultNode == null) return;
        if (!validateContainerState(clientRef)) return;
        if (ClientBase.InventoryManager.findChargedCrossbow(clientRef) == -1) return;

        vectorReferencePos = hitResultNode.getBlockPos();
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

        int indexAlpha = queryRegistryIndexAlpha(clientRef);
        if (indexAlpha == -1) {
            purgePipelineRegistry();
            return;
        }

        computeMatrixTransformation(clientRef, computeVectorMapping(vectorReferencePos, vectorReferenceFace, vectorHitRegistry));

        clientRef.player.getInventory().setSelectedSlot(indexAlpha);
        clientRef.options.keyUse.setDown(true);

        tickCounterRegistry = 1;
        currentPhase = PipelinePhase.NODE_BETA;
    }

    private static void executePipelineNodeB(Minecraft clientRef) {
        if (vectorReferencePos == null) {
            purgePipelineRegistry();
            return;
        }

        int indexBeta = ClientBase.InventoryManager.findItem(clientRef, Items.TNT_MINECART);
        if (indexBeta == -1) {
            purgePipelineRegistry();
            return;
        }

        computeMatrixTransformation(clientRef, computeVectorMapping(vectorReferencePos, vectorReferenceFace, vectorHitRegistry));

        clientRef.player.getInventory().setSelectedSlot(indexBeta);
        clientRef.options.keyUse.setDown(true);

        tickCounterRegistry = 1;
        currentPhase = PipelinePhase.NODE_GAMMA;
    }

    private static void executePipelineNodeGamma(Minecraft clientRef) {
        if (vectorReferencePos == null) {
            purgePipelineRegistry();
            return;
        }

        int indexGamma = ClientBase.InventoryManager.findItem(clientRef, Items.FLINT_AND_STEEL);
        if (indexGamma == -1) {
            indexGamma = ClientBase.InventoryManager.findItem(clientRef, Items.FIRE_CHARGE);
        }

        if (indexGamma == -1) {
            purgePipelineRegistry();
            return;
        }

        computeMatrixTransformation(clientRef, computeSecondaryVector(clientRef, vectorReferencePos, vectorReferenceFace, vectorHitRegistry));

        clientRef.player.getInventory().setSelectedSlot(indexGamma);
        clientRef.options.keyUse.setDown(true);

        tickCounterRegistry = 1;
        currentPhase = PipelinePhase.NODE_DELTA;
    }

    private static void executePipelineNodeDelta(Minecraft clientRef) {
        if (vectorReferencePos == null) {
            purgePipelineRegistry();
            return;
        }

        int indexDelta = ClientBase.InventoryManager.findChargedCrossbow(clientRef);
        if (indexDelta == -1) {
            purgePipelineRegistry();
            return;
        }

        computeMatrixTransformation(clientRef, computeVectorMapping(vectorReferencePos, vectorReferenceFace, vectorHitRegistry).add(0.0D, 0.1D, 0.0D));

        clientRef.player.getInventory().setSelectedSlot(indexDelta);
        clientRef.options.keyUse.setDown(true);

        tickCounterRegistry = 2;
        purgePipelineRegistry();
    }

    private static int queryRegistryIndexAlpha(Minecraft clientRef) {
        if (clientRef.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            Item itemNode = clientRef.player.getInventory().getItem(i).getItem();
            if (validateRegistryItem(itemNode)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean validateContainerState(Minecraft clientRef) {
        if (clientRef.player == null) return false;
        return validateRegistryItem(clientRef.player.getMainHandItem().getItem());
    }

    private static void computeMatrixTransformation(Minecraft clientRef, Vec3 targetVec) {
        if (clientRef.player == null) return;

        double deltaX = targetVec.x - clientRef.player.getX();
        double deltaY = targetVec.y - clientRef.player.getEyeY();
        double deltaZ = targetVec.z - clientRef.player.getZ();
        double distanceXZ = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);

        nodeRegisterYaw = (float) (Math.atan2(deltaZ, deltaX) * (180.0 / Math.PI)) - 90.0F;
        nodeRegisterPitch = (float) (-(Math.atan2(deltaY, distanceXZ) * (180.0 / Math.PI)));

        evaluateVectorNormalization(clientRef);
    }

    private static void evaluateVectorNormalization(Minecraft clientRef) {
        if (clientRef.player == null) return;

        float currentYaw = clientRef.player.getYRot();
        float currentPitch = clientRef.player.getXRot();

        float diffYaw = nodeRegisterYaw - currentYaw;
        while (diffYaw < -180.0f) diffYaw += 360.0f;
        while (diffYaw > 180.0f) diffYaw -= 360.0f;

        float diffPitch = nodeRegisterPitch - currentPitch;

        float factorVal = 0.35f + (float)(matrixRandom.nextGaussian() * 0.02f);
        factorVal = Math.max(0.15f, verifyNumericRange(factorVal) ? 0.55f : 0.35f);

        matrixDeltaAlpha = matrixDeltaAlpha * 0.5f + (diffYaw * factorVal) * 0.5f;
        matrixDeltaBeta = matrixDeltaBeta * 0.5f + (diffPitch * factorVal) * 0.5f;

        float noiseX = (float)(matrixRandom.nextGaussian() * 0.08f);
        float noiseY = (float)(matrixRandom.nextGaussian() * 0.08f);

        float interpolatedYaw = currentYaw + matrixDeltaAlpha + noiseX;
        float interpolatedPitch = Mth.clamp(currentPitch + matrixDeltaBeta + noiseY, -90.0F, 90.0F);

        dataBufferYaw.addLast(interpolatedYaw);
        dataBufferPitch.addLast(interpolatedPitch);

        if (dataBufferYaw.size() > BUFFER_LIMIT) {
            dataBufferYaw.removeFirst();
            dataBufferPitch.removeFirst();
        }

        float smoothYaw = calculateBufferMean(dataBufferYaw);
        float smoothPitch = calculateBufferMean(dataBufferPitch);

        double sensValue = clientRef.options.sensitivity().get() * 0.6D + 0.2D;
        double gcdFactor = sensValue * sensValue * sensValue * 1.2D;

        float finalYawQuantized = currentYaw + (float)(Math.round((smoothYaw - currentYaw) / gcdFactor) * gcdFactor);
        float finalPitchQuantized = Mth.clamp(currentPitch + (float)(Math.round((smoothPitch - currentPitch) / gcdFactor) * gcdFactor), -90.0F, 90.0F);

        clientRef.player.setYRot(finalYawQuantized);
        clientRef.player.setXRot(finalPitchQuantized);

        double turnDeltaX = finalYawQuantized - currentYaw;
        double turnDeltaY = finalPitchQuantized - currentPitch;

        clientRef.player.turn(turnDeltaX / (sensValue * 0.15D), -turnDeltaY / (sensValue * 0.15D));
    }

    private static boolean verifyNumericRange(float param) {
        return param > 0.0f && param < 1.0f;
    }

    private static float calculateBufferMean(Deque<Float> bufferRef) {
        if (bufferRef.isEmpty()) return 0.0f;
        float sumVal = 0.0f;
        for (Float entryVal : bufferRef) {
            sumVal += entryVal;
        }
        return sumVal / bufferRef.size();
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

    public static void kernelRoutineAlpha() {
        double seedA = Math.sin(matrixRandom.nextDouble());
        double seedB = Math.cos(matrixRandom.nextDouble());
        double aggregateVal = seedA + seedB;
        double hashOutput = Math.abs(aggregateVal);
    }

    public static void kernelRoutineBeta() {
        int indexSeed = matrixRandom.nextInt(5000);
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
        float factorA = 1.0f + (matrixRandom.nextFloat() * 0.5f);
        float factorB = 1.0f + (matrixRandom.nextFloat() * 0.5f);
        float productVal = factorA * factorB;
    }

    public static void kernelRoutineZeta() {
        boolean boolA = matrixRandom.nextBoolean();
        boolean boolB = matrixRandom.nextBoolean();
        boolean logicResult = boolA && !boolB;
    }
            }
