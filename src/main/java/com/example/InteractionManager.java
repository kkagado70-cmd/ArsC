package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.InteractionHand;

/**
 * Pequena camada para centralizar ações de interação usadas pelos módulos.
 */
public final class InteractionManager {
    public enum InteractionPriority {
        LOW,
        NORMAL,
        HIGH,
        IMMEDIATE
    }

    private InteractionManager() {}

    public static void simulateClickAttack(Minecraft client) {
        if (client == null || client.player == null || client.gameMode == null) return;

        Entity target = client.hitResult instanceof net.minecraft.world.phys.EntityHitResult ehr
                ? ehr.getEntity()
                : null;

        if (target != null) {
            client.gameMode.attack(client.player, target);
        }
    }

    public static void flushAndRelease(Minecraft client) {
        // Libera qualquer interação pendente e reseta o estado
        if (client == null || client.player == null) return;
        // Garante que teclas de uso/ataque não fiquem presas
        if (client.options != null) {
            client.options.keyAttack.setDown(false);
            client.options.keyUse.setDown(false);
        }
    }

    public static void simulateClickUse(Minecraft client, InteractionPriority priority) {
        if (client == null || client.player == null || client.gameMode == null) return;

        InteractionHand hand = InteractionHand.MAIN_HAND;
        client.gameMode.useItem(client.player, hand);
    }
}
