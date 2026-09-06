package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;

public class RaycastManager {
    private static HitResult cachedHitResult = null;
    private static long lastRaycastTimestamp = 0L;
    private static BlockPos lastValidBlockPos = null;

    public static BlockHitResult getValidHit(Minecraft client) {
        if (client == null || client.hitResult == null) return null;
        
        if (client.hitResult.getType() == HitResult.Type.BLOCK) {
            if (client.hitResult instanceof BlockHitResult blockHit) {
                if (blockHit.getDirection() != Direction.DOWN) {
                    cachedHitResult = blockHit;
                    lastRaycastTimestamp = System.currentTimeMillis();
                    lastValidBlockPos = blockHit.getBlockPos();
                    return blockHit;
                }
            }
        }
        return null;
    }

    public static EntityHitResult getEntityHit(Minecraft client) {
        if (client == null || client.hitResult == null) return null;
        if (client.hitResult.getType() == HitResult.Type.ENTITY) {
            if (client.hitResult instanceof EntityHitResult entityHit) {
                return entityHit;
            }
        }
        return null;
    }

    public static boolean isLookingAtValidSurface(Minecraft client) {
        return getValidHit(client) != null;
    }

    public static Vec3 fetchRaycastPosition(Minecraft client) {
        BlockHitResult hit = getValidHit(client);
        if (hit != null) {
            return hit.getLocation();
        }
        return null;
    }

    public static void purgeRaycastCache() {
        cachedHitResult = null;
        lastRaycastTimestamp = 0L;
        lastValidBlockPos = null;
    }

    public static boolean verifyRaycastFreshness(long maxAgeMs) {
        return System.currentTimeMillis() - lastRaycastTimestamp <= maxAgeMs;
    }

    public static Direction fetchTargetFace(Minecraft client) {
        BlockHitResult hit = getValidHit(client);
        return hit != null ? hit.getDirection() : Direction.UP;
    }

    public static double computeDistanceFromCamera(Minecraft client, Vec3 targetPos) {
        if (client == null || client.player == null || targetPos == null) return 0.0D;
        return client.player.getEyePosition().distanceTo(targetPos);
    }

    public static boolean isEntityWithinRange(Minecraft client, Entity entity, double maxRange) {
        if (client == null || client.player == null || entity == null) return false;
        return client.player.distanceToSqr(entity) <= maxRange * maxRange;
    }

    public static void stepCycle(Minecraft client) {
        if (client != null) {
            getValidHit(client);
        }
    }

    public static BlockPos getLastValidBlockPos() {
        return lastValidBlockPos;
    }

    public static boolean isPointingAtBlock(Minecraft client, BlockPos pos) {
        BlockHitResult hit = getValidHit(client);
        return hit != null && hit.getBlockPos().equals(pos);
    }

    public static double distanceToHit(Minecraft client) {
        Vec3 pos = fetchRaycastPosition(client);
        if (pos != null && client.player != null) {
            return client.player.getEyePosition().distanceTo(pos);
        }
        return -1.0D;
    }

    public static boolean validateLineOfSight(Minecraft client, Vec3 destination) {
        if (client.player == null || client.level == null) return false;
        Vec3 eye = client.player.getEyePosition();
        return client.level.clip(new net.minecraft.world.level.ClipContext(
            eye, destination, 
            net.minecraft.world.level.ClipContext.Block.COLLIDER, 
            net.minecraft.world.level.ClipContext.Fluid.NONE, 
            client.player
        )).getType() == HitResult.Type.MISS;
    }

    // Additional robust expansion helpers
    public static boolean isLookingAtAir(Minecraft client) {
        return client != null && client.hitResult != null && client.hitResult.getType() == HitResult.Type.MISS;
    }

    public static Entity findTargetEntityInCrosshair(Minecraft client, double range) {
        EntityHitResult hit = getEntityHit(client);
        if (hit != null && isEntityWithinRange(client, hit.getEntity(), range)) {
            return hit.getEntity();
        }
        return null;
    }

    public static void refreshRaycastState(Minecraft client) {
        if (client != null) {
            getValidHit(client);
            getEntityHit(client);
        }
    }
}