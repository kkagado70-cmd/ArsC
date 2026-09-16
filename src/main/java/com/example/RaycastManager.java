package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class RaycastManager {

    public static final String FILE_NAME = "RaycastManager.java";

    public enum RaycastMode {
        FAST,
        PRECISE,
        MULTI_SAMPLE
    }

    public enum SurfaceFilter {
        ALL,
        SOLID_ONLY,
        NON_FLUID,
        PLACEABLE_TOP_ONLY
    }

    private static final Map<String, Object> RAYCAST_REGISTRY = new ConcurrentHashMap<>();
    private static final UUID SUBSESSION_ID = UUID.randomUUID();

    private static RaycastMode mode = RaycastMode.PRECISE;
    private static double defaultReach = 4.8D;
    private static long raycastInvocations = 0L;
    private static BlockHitResult lastValidHit = null;
    private static long lastHitTimestamp = 0L;
    private static final long HIT_CACHE_TTL_MS = 50L;

    static {
        RAYCAST_REGISTRY.put("SubsessionUUID", SUBSESSION_ID);
        RAYCAST_REGISTRY.put("Profile", "Enterprise-RaycastManager-MultiFace");
        RAYCAST_REGISTRY.put("DefaultReach", defaultReach);
    }

    public static BlockHitResult getValidHit(Minecraft client) {
        return getValidHit(client, SurfaceFilter.ALL, defaultReach);
    }

    public static BlockHitResult getValidHit(Minecraft client, SurfaceFilter filter, double reach) {
        if (client == null || client.player == null) return null;
        raycastInvocations++;

        if (client.hitResult != null && client.hitResult.getType() == HitResult.Type.BLOCK
                && client.hitResult instanceof BlockHitResult bhr
                && passesFilter(client, bhr, filter)) {
            lastValidHit = bhr;
            lastHitTimestamp = System.currentTimeMillis();
            return bhr;
        }

        HitResult fresh = client.player.pick(reach, 1.0f, false);
        if (fresh.getType() == HitResult.Type.BLOCK && fresh instanceof BlockHitResult freshBhr
                && passesFilter(client, freshBhr, filter)) {
            lastValidHit = freshBhr;
            lastHitTimestamp = System.currentTimeMillis();
            return freshBhr;
        }

        return null;
    }

    public static BlockHitResult raycastAt(Minecraft client, Vec3 origin, Vec3 direction, double distance) {
        if (client == null || client.level == null) return null;
        Vec3 end = origin.add(direction.normalize().scale(distance));
        ClipContext ctx = new ClipContext(origin, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, client.player);
        HitResult hit = client.level.clip(ctx);
        if (hit instanceof BlockHitResult bhr) return bhr;
        return null;
    }

    public static BlockHitResult raycastFromEyes(Minecraft client, double distance) {
        if (client == null || client.player == null) return null;
        Vec3 eyePos = client.player.getEyePosition(1.0f);
        Vec3 lookVec = client.player.getLookAngle();
        return raycastAt(client, eyePos, lookVec, distance);
    }

    public static BlockHitResult raycastToFace(Minecraft client, BlockPos target, Direction face) {
        if (client == null || client.player == null || target == null) return null;
        Vec3 faceCenter = Vec3.atCenterOf(target).add(
                face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5
        );
        return new BlockHitResult(faceCenter, face, target, false);
    }

    private static boolean passesFilter(Minecraft client, BlockHitResult bhr, SurfaceFilter filter) {
        if (client == null || client.level == null) return false;
        return switch (filter) {
            case ALL -> bhr.getDirection() != Direction.DOWN;
            case SOLID_ONLY -> {
                BlockState state = client.level.getBlockState(bhr.getBlockPos());
                yield !state.isAir() && state.isSolidRender();
            }
            case NON_FLUID -> {
                BlockState state = client.level.getBlockState(bhr.getBlockPos());
                yield !state.isAir() && bhr.getDirection() != Direction.DOWN;
            }
            case PLACEABLE_TOP_ONLY -> bhr.getDirection() == Direction.UP;
        };
    }

    public static EntityHitResult getEntityHit(Minecraft client) {
        if (client == null || client.hitResult == null) return null;
        if (client.hitResult.getType() == HitResult.Type.ENTITY
                && client.hitResult instanceof EntityHitResult ehr) {
            return ehr;
        }
        return null;
    }

    public static <T extends Entity> Optional<T> findNearestEntity(Minecraft client, Class<T> entityClass, BlockPos center, double radius) {
        if (client == null || client.level == null) return Optional.empty();
        Vec3 centerVec = Vec3.atCenterOf(center);
        AABB box = new AABB(
                centerVec.x - radius, centerVec.y - radius, centerVec.z - radius,
                centerVec.x + radius, centerVec.y + radius, centerVec.z + radius
        );
        List<T> entities = client.level.getEntitiesOfClass(entityClass, box);
        return entities.stream()
                .min((a, b) -> Double.compare(a.distanceToSqr(centerVec), b.distanceToSqr(centerVec)));
    }

    public static <T extends Entity> List<T> findEntitiesNear(Minecraft client, Class<T> entityClass, BlockPos center, double radius) {
        if (client == null || client.level == null) return List.of();
        Vec3 centerVec = Vec3.atCenterOf(center);
        AABB box = new AABB(
                centerVec.x - radius, centerVec.y - radius, centerVec.z - radius,
                centerVec.x + radius, centerVec.y + radius, centerVec.z + radius
        );
        return client.level.getEntitiesOfClass(entityClass, box);
    }

    public static BlockPos computeRailPosition(Minecraft client, BlockPos hitTarget, Direction hitFace) {
        if (client == null || client.level == null || hitTarget == null) return null;
        return switch (hitFace) {
            case UP -> {
                BlockState state = client.level.getBlockState(hitTarget);
                yield state.isAir() ? hitTarget : hitTarget.above();
            }
            case DOWN -> null;
            default -> hitTarget.above();
        };
    }

    public static BlockPos computeFirePosition(Minecraft client, BlockPos railPos, Direction hitFace) {
        if (client == null || client.player == null || railPos == null) return null;

        Vec3 playerPos = client.player.position();
        Vec3 railCenter = Vec3.atCenterOf(railPos);

        double dx = playerPos.x - railCenter.x;
        double dz = playerPos.z - railCenter.z;

        Direction dirToPlayer = Math.abs(dx) > Math.abs(dz)
                ? (dx > 0 ? Direction.EAST : Direction.WEST)
                : (dz > 0 ? Direction.SOUTH : Direction.NORTH);

        BlockPos candidate = railPos.relative(dirToPlayer);
        if (isValidFirePosition(client, candidate)) {
            return candidate;
        }

        Direction[] horizontals = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        for (Direction d : horizontals) {
            if (d == dirToPlayer) continue;
            BlockPos p = railPos.relative(d);
            if (isValidFirePosition(client, p)) {
                return p;
            }
        }

        return candidate;
    }

    public static boolean isValidFirePosition(Minecraft client, BlockPos pos) {
        if (client == null || client.level == null || pos == null) return false;
        BlockState state = client.level.getBlockState(pos);
        if (!state.isAir()) return false;
        BlockState below = client.level.getBlockState(pos.below());
        return !below.isAir();
    }

    public static boolean hasLineOfSight(Minecraft client, BlockPos from, BlockPos to) {
        if (client == null || client.level == null) return false;
        Vec3 fromVec = Vec3.atCenterOf(from).add(0, 0.5, 0);
        Vec3 toVec = Vec3.atCenterOf(to).add(0, 0.5, 0);
        ClipContext ctx = new ClipContext(fromVec, toVec, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, client.player);
        HitResult hit = client.level.clip(ctx);
        if (hit.getType() == HitResult.Type.MISS) return true;
        if (hit instanceof BlockHitResult bhr) {
            return bhr.getBlockPos().equals(to) || bhr.getBlockPos().equals(from);
        }
        return false;
    }

    public static boolean isWithinReach(Minecraft client, BlockPos pos, double maxDist) {
        if (client == null || client.player == null) return false;
        return client.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) <= maxDist * maxDist;
    }

    public static boolean isWithinReach(Minecraft client, Vec3 point, double maxDist) {
        if (client == null || client.player == null) return false;
        return client.player.getEyePosition().distanceToSqr(point) <= maxDist * maxDist;
    }

    public static Direction getHorizontalDirectionToward(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        } else {
            return dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }
    }

    public static Direction getHorizontalDirectionToward(BlockPos from, BlockPos to) {
        return getHorizontalDirectionToward(Vec3.atCenterOf(from), Vec3.atCenterOf(to));
    }

    public static boolean isLookingAtBlock(Minecraft client, BlockPos target) {
        if (client == null || client.hitResult == null) return false;
        if (client.hitResult.getType() != HitResult.Type.BLOCK) return false;
        if (!(client.hitResult instanceof BlockHitResult bhr)) return false;
        return bhr.getBlockPos().equals(target);
    }

    public static boolean isLookingAtFace(Minecraft client, BlockPos target, Direction face) {
        if (client == null || client.hitResult == null) return false;
        if (client.hitResult.getType() != HitResult.Type.BLOCK) return false;
        if (!(client.hitResult instanceof BlockHitResult bhr)) return false;
        return bhr.getBlockPos().equals(target) && bhr.getDirection() == face;
    }

    public static Vec3 getHitLocation(Minecraft client) {
        if (client == null || client.hitResult == null) return null;
        if (client.hitResult.getType() != HitResult.Type.BLOCK) return null;
        return client.hitResult.getLocation();
    }

    public static Direction getHitFace(Minecraft client) {
        if (client == null || client.hitResult == null) return Direction.UP;
        if (!(client.hitResult instanceof BlockHitResult bhr)) return Direction.UP;
        return bhr.getDirection();
    }

    public static BlockPos getHitBlockPos(Minecraft client) {
        if (client == null || client.hitResult == null) return null;
        if (!(client.hitResult instanceof BlockHitResult bhr)) return null;
        return bhr.getBlockPos();
    }

    public static Vec3 computeAimTarget(BlockPos railPos, double heightOffset) {
        if (railPos == null) return null;
        return Vec3.atCenterOf(railPos).add(0.0, heightOffset, 0.0);
    }

    public static List<BlockPos> computeFireCandidates(Minecraft client, BlockPos railPos) {
        if (client == null || railPos == null) return List.of();
        List<BlockPos> candidates = new ArrayList<>();
        Direction[] horizontals = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        for (Direction d : horizontals) {
            BlockPos p = railPos.relative(d);
            if (isValidFirePosition(client, p)) {
                candidates.add(p);
            }
        }
        return candidates;
    }

    public static void setMode(RaycastMode m) {
        mode = m;
        RAYCAST_REGISTRY.put("Mode", m.name());
    }

    public static void setDefaultReach(double reach) {
        defaultReach = Math.max(1.0, reach);
        RAYCAST_REGISTRY.put("DefaultReach", defaultReach);
    }

    public static void purgeCache() {
        lastValidHit = null;
        lastHitTimestamp = 0L;
    }

    public static BlockHitResult getLastValidHit() {
        if (lastValidHit == null) return null;
        if (System.currentTimeMillis() - lastHitTimestamp > HIT_CACHE_TTL_MS) {
            lastValidHit = null;
            return null;
        }
        return lastValidHit;
    }

    public static long getRaycastInvocations() {
        return raycastInvocations;
    }

    public static UUID getSubsessionIdentity() {
        return SUBSESSION_ID;
    }
}

    public static net.minecraft.world.phys.BlockHitResult multiSampleRaycast(Minecraft client, net.minecraft.world.phys.Vec3 aimTarget, int samples, double spread) 
        if (client == null || client.player == null) return null;
        net.minecraft.world.phys.Vec3 eyePos = client.player.getEyePosition(1.0f);
        java.util.List<net.minecraft.world.phys.BlockHitResult> hits = new java.util.ArrayList<>();
        java.util.Random rng = new java.util.Random();

        for (int i = 0; i < samples; i++) {
            double nx = aimTarget.x + (rng.nextDouble() - 0.5) * spread;
            double ny = aimTarget.y + (rng.nextDouble() - 0.5) * spread;
            double nz = aimTarget.z + (rng.nextDouble() - 0.5) * spread;
            net.minecraft.world.phys.Vec3 jittered = new net.minecraft.world.phys.Vec3(nx, ny, nz);
            net.minecraft.world.phys.Vec3 dir = jittered.subtract(eyePos).normalize();
            net.minecraft.world.phys.BlockHitResult h = raycastAt(client, eyePos, dir, defaultReach);
            if (h != null) hits.add(h);
        }

        if (hits.isEmpty()) return null;
        net.minecraft.world.phys.Vec3 eyeFinal = eyePos;
        return hits.stream()
                .min((a, b) -> Double.compare(a.getLocation().distanceToSqr(eyeFinal), b.getLocation().distanceToSqr(eyeFinal)))
                .orElse(null);
    }

    public static boolean isBlockSolid(Minecraft client, net.minecraft.core.BlockPos pos) {
        if (client == null || client.level == null) return false;
        net.minecraft.world.level.block.state.BlockState state = client.level.getBlockState(pos);
        return !state.isAir() && state.isSolidRender();
    }

    public static boolean isBlockAir(Minecraft client, net.minecraft.core.BlockPos pos) {
        if (client == null || client.level == null) return true;
        return client.level.getBlockState(pos).isAir();
    }

    public static boolean isRailAt(Minecraft client, net.minecraft.core.BlockPos pos) {
        if (client == null || client.level == null) return false;
        net.minecraft.world.level.block.state.BlockState state = client.level.getBlockState(pos);
        return state.is(net.minecraft.world.level.block.Blocks.RAIL)
                || state.is(net.minecraft.world.level.block.Blocks.POWERED_RAIL)
                || state.is(net.minecraft.world.level.block.Blocks.ACTIVATOR_RAIL)
                || state.is(net.minecraft.world.level.block.Blocks.DETECTOR_RAIL);
    }

    public static boolean isFireAt(Minecraft client, net.minecraft.core.BlockPos pos) {
        if (client == null || client.level == null) return false;
        net.minecraft.world.level.block.state.BlockState state = client.level.getBlockState(pos);
        return state.is(net.minecraft.world.level.block.Blocks.FIRE) || state.is(net.minecraft.world.level.block.Blocks.SOUL_FIRE);
    }

    public static net.minecraft.world.phys.Vec3 getFaceCenterWorld(net.minecraft.core.BlockPos pos, net.minecraft.core.Direction face) {
        net.minecraft.world.phys.Vec3 center = net.minecraft.world.phys.Vec3.atCenterOf(pos);
        return center.add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
    }

    public static net.minecraft.core.Direction getClosestFaceToPlayer(Minecraft client, net.minecraft.core.BlockPos pos) {
        if (client == null || client.player == null) return net.minecraft.core.Direction.UP;
        net.minecraft.world.phys.Vec3 playerEye = client.player.getEyePosition(1.0f);
        net.minecraft.world.phys.Vec3 blockCenter = net.minecraft.world.phys.Vec3.atCenterOf(pos);
        double dx = playerEye.x - blockCenter.x;
        double dy = playerEye.y - blockCenter.y;
        double dz = playerEye.z - blockCenter.z;
        double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
        if (ax >= ay && ax >= az) return dx > 0 ? net.minecraft.core.Direction.EAST : net.minecraft.core.Direction.WEST;
        if (ay >= ax && ay >= az) return dy > 0 ? net.minecraft.core.Direction.UP : net.minecraft.core.Direction.DOWN;
        return dz > 0 ? net.minecraft.core.Direction.SOUTH : net.minecraft.core.Direction.NORTH;
    }

    public static double distanceToBlock(Minecraft client, net.minecraft.core.BlockPos pos) {
        if (client == null || client.player == null) return Double.MAX_VALUE;
        return client.player.getEyePosition().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(pos));
    }

    public static String buildRaycastReport(Minecraft client) {
        net.minecraft.core.BlockPos hit = getHitBlockPos(client);
        net.minecraft.core.Direction face = getHitFace(client);
        return "[RC]"
                + " hitPos=" + (hit != null ? hit : "null")
                + " face=" + face.name()
                + " reach=" + defaultReach
                + " mode=" + mode.name()
                + " invocations=" + raycastInvocations;
    }
