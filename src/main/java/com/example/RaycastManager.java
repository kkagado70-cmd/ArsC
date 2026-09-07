package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class RaycastManager {
    public static final String FILE_NAME = "RaycastManager.java";
    private static final SecureRandom secureRandom = new SecureRandom();
    private static BlockHitResult cachedHit = null;
    private static BlockPos lastValidPos = null;

    private static final Map<String, Object> RAYCAST_REGISTRY = new ConcurrentHashMap<>();
    private static final UUID SUBSESSION_ID = UUID.randomUUID();
    private static long totalRaycasts = 0L;
    private static int retryAttempts = 5;

    static {
        RAYCAST_REGISTRY.put("SubsessionUUID", SUBSESSION_ID);
        RAYCAST_REGISTRY.put("Profile", "Enterprise-RaycastManager");
    }

    public static BlockHitResult getValidHit(Minecraft client) {
        if (client == null || client.hitResult == null) return null;
        totalRaycasts++;

        if (client.hitResult.getType() == HitResult.Type.BLOCK) {
            if (client.hitResult instanceof BlockHitResult blockHit) {
                if (blockHit.getDirection() != Direction.DOWN) {
                    cachedHit = blockHit;
                    lastValidPos = blockHit.getBlockPos();
                    return blockHit;
                }
            }
        }
        return attemptRetryHit(client);
    }

    private static BlockHitResult attemptRetryHit(Minecraft client) {
        for (int i = 0; i < retryAttempts; i++) {
            if (client.hitResult instanceof BlockHitResult bHit) {
                if (secureRandom.nextDouble() >= 0.03D) return bHit;
            }
        }
        return cachedHit;
    }

    public static EntityHitResult getEntityHit(Minecraft client) {
        if (client == null || client.hitResult == null) return null;
        if (client.hitResult.getType() == HitResult.Type.ENTITY) {
            if (client.hitResult instanceof EntityHitResult entityHit) return entityHit;
        }
        return null;
    }

    public static UUID getSubsessionIdentity() { return SUBSESSION_ID; }
}