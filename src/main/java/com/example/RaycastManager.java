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
    public static final String FILE_NAME = "RaycastManager.java";

    public static BlockHitResult getValidHit(Minecraft client) {
        if (client == null || client.hitResult == null) return null;
        
        if (client.hitResult.getType() == HitResult.Type.BLOCK) {
            if (client.hitResult instanceof BlockHitResult blockHit) {
                if (blockHit.getDirection() != Direction.DOWN) {
                    return blockHit;
                }
            }
        }
        
        HitResult freshHit = client.player.pick(6.0D, 1.0F, false);
        if (freshHit.getType() == HitResult.Type.BLOCK && freshHit instanceof BlockHitResult freshBlockHit) {
            if (freshBlockHit.getDirection() != Direction.DOWN) {
                return freshBlockHit;
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
    }

    public static Direction fetchTargetFace(Minecraft client) {
        BlockHitResult hit = getValidHit(client);
        return hit != null ? hit.getDirection() : Direction.UP;
    }
}