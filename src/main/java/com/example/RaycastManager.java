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
    private static BlockHitResult cachedHitResult = null;
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

    public static void purgeRaycastCache() {
        cachedHitResult = null;
        lastRaycastTimestamp = 0L;
        lastValidBlockPos = null;
    }
}