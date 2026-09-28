package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;

public final class InteractionManager {

    public enum InteractionPriority {
        LOW,
        NORMAL,
        HIGH,
        IMMEDIATE
    }

    private InteractionManager() {
    }

    /*
     * Chamado pelo ClientBase a cada atualização.
     */
    public static void update(Minecraft client) {
        if (client == null) {
            return;
        }

        // Atualização reservada para gerenciamento de interações.
    }

    public static void simulateClickAttack(Minecraft client) {
        if (client == null ||
            client.player == null ||
            client.gameMode == null) {
            return;
        }

        if (client.hitResult instanceof EntityHitResult entityHit) {
            Entity target = entityHit.getEntity();

            if (target != null) {
                client.gameMode.attack(client.player, target);
            }
        }
    }

    public static void simulateClickUse(
            Minecraft client,
            InteractionPriority priority) {

        if (client == null ||
            client.player == null ||
            client.gameMode == null) {
            return;
        }

        client.gameMode.useItem(
                client.player,
                InteractionHand.MAIN_HAND
        );
    }
}
