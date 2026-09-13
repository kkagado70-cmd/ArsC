package com.example;

import net.minecraft.client.Minecraft;
import java.security.SecureRandom;

public class InteractionManager {
    public static final String FILE_NAME = "InteractionManager.java";
    private static final SecureRandom secureRandom = new SecureRandom();
    
    private static int attackHoldTicks = 0;
    private static int useHoldTicks = 0;

    public static void update(Minecraft client) {
        if (client == null || client.options == null) return;

        if (attackHoldTicks > 0) {
            attackHoldTicks--;
            if (attackHoldTicks == 0) {
                client.options.keyAttack.setDown(false);
            }
        }

        if (useHoldTicks > 0) {
            useHoldTicks--;
            if (useHoldTicks == 0) {
                client.options.keyUse.setDown(false);
            }
        }
    }

    public static void simulateClickUse(Minecraft client) {
        if (client == null || client.options == null) return;
        useHoldTicks = 2;
        client.options.keyUse.setDown(false);
        client.options.keyUse.setDown(true);
    }

    public static void simulateClickAttack(Minecraft client) {
        if (client == null || client.options == null) return;
        attackHoldTicks = 2;
        client.options.keyAttack.setDown(false);
        client.options.keyAttack.setDown(true);
    }

    public static void forceReleaseAll(Minecraft client) {
        if (client == null || client.options == null) return;
        client.options.keyUse.setDown(false);
        client.options.keyAttack.setDown(false);
        attackHoldTicks = 0;
        useHoldTicks = 0;
    }
}