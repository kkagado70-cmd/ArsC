package com.example;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Item;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

public class XbowCart implements ClientModInitializer {
    private static final Minecraft mc = Minecraft.getInstance();
    private static KeyMapping triggerKey;
    public static boolean enabled = false;
    private static boolean active = false;
    private static int stage = 0;
    private static int tickCounter = 0;

    // Posições travadas para garantir que o Isqueiro (Flint) funcione mesmo após colocar o carrinho
    private static BlockPos lockedBaseBlock = null;
    private static Vec3 lockedTargetVec = null;

    @Override
    public void onInitializeClient() {
        triggerKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.xbowcart.trigger",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_X,
            KeyMapping.Category.MISC
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (mc.player == null || mc.level == null) return;

            while (triggerKey.consumeClick()) {
                toggle();
            }

            if (enabled) {
                onTick(client);
            }
        });
    }

    public static void toggle() {
        enabled = !enabled;
        resetSequence();
    }

    public static void onTick(Minecraft client) {
        if (mc.player == null || mc.level == null) return;

        // Gatilho: Olhar para o chão (> 40 graus de inclinação) segurando um Trilho
        if (!active) {
            boolean holdingRail = mc.player.getMainHandItem().is(Items.RAIL);
            HitResult hit = mc.hitResult;

            if (holdingRail && hit != null && hit.getType() == HitResult.Type.BLOCK) {
                BlockHitResult blockHit = (BlockHitResult) hit;
                if (blockHit.getDirection() == Direction.UP && mc.player.getXRot() > 40.0F) {
                    active = true;
                    stage = 0;
                    tickCounter = 0;
                    
                    // Trava o bloco base e a posição no momento exato do disparo
                    lockedBaseBlock = blockHit.getBlockPos();
                    BlockPos placedBlockPos = lockedBaseBlock.relative(Direction.UP);
                    lockedTargetVec = Vec3.atCenterOf(placedBlockPos);
                }
            }
        }

        if (!active || lockedBaseBlock == null || lockedTargetVec == null) return;

        if (tickCounter > 0) {
            tickCounter--;
            return;
        }

        if (mc.player.distanceToSqr(lockedTargetVec) > 8.7D) {
            resetSequence();
            return;
        }

        applySmoothLookAt(lockedTargetVec);

        BlockHitResult placementHit = new BlockHitResult(lockedTargetVec, Direction.UP, lockedBaseBlock, false);

        switch (stage) {
            case 0:
                // 1. Colocar Trilho
                if (selectItem(Items.RAIL)) {
                    mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, placementHit);
                    mc.player.swing(InteractionHand.MAIN_HAND);
                    tickCounter = 1;
                    stage = 1;
                } else {
                    resetSequence();
                }
                break;

            case 1:
                // 2. Colocar TNT Minecart
                if (selectItem(Items.TNT_MINECART)) {
                    mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, placementHit);
                    mc.player.swing(InteractionHand.MAIN_HAND);
                    tickCounter = 1;
                    stage = 2;
                } else {
                    resetSequence();
                }
                break;

            case 2:
                // 3. Isqueiro (Flint & Steel) - Funciona de forma independente do raycast atual
                if (selectItem(Items.FLINT_AND_STEEL)) {
                    mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, placementHit);
                    mc.player.swing(InteractionHand.MAIN_HAND);
                    tickCounter = 1;
                    stage = 3;
                } else {
                    resetSequence();
                }
                break;

            case 3:
                // 4. Armar Crossbow
                if (selectItem(Items.CROSSBOW)) {
                    mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                    tickCounter = 1;
                    stage = 4;
                } else {
                    resetSequence();
                }
                break;

            case 4:
                // 5. Disparar e Explodir
                if (selectItem(Items.CROSSBOW)) {
                    mc.gameMode.releaseUsingItem(mc.player);
                    mc.player.swing(InteractionHand.MAIN_HAND);
                    resetSequence();
                }
                break;
        }
    }

    private static void applySmoothLookAt(Vec3 target) {
        double dx = target.x - mc.player.getX();
        double dy = target.y - mc.player.getEyeY();
        double dz = target.z - mc.player.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);

        float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        float targetPitch = (float) (-Math.toDegrees(Math.atan2(dy, dist)));

        float currentYaw = mc.player.getYRot();
        float currentPitch = mc.player.getXRot();

        float yawDiff = Mth.wrapDegrees(targetYaw - currentYaw);
        float pitchDiff = Mth.wrapDegrees(targetPitch - currentPitch);

        // Suavização do Xbow (0.65F = velocidade competitiva Pro estilo Blump/eyezingz)
        mc.player.setYRot(currentYaw + yawDiff * 0.65F);
        mc.player.setXRot(currentPitch + pitchDiff * 0.65F);
    }

    private static boolean selectItem(Item item) {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).is(item)) {
                mc.player.getInventory().setSelectedSlot(i);
                return true;
            }
        }
        return false;
    }

    private static void resetSequence() {
        active = false;
        stage = 0;
        tickCounter = 0;
        lockedBaseBlock = null;
        lockedTargetVec = null;
    }
}
