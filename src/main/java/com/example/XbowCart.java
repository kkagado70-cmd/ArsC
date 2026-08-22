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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

public class XBowCart implements ClientModInitializer {
    private static final Minecraft mc = Minecraft.getInstance();
    private static KeyMapping triggerKey;
    private static boolean active = false;
    private static int step = 0;
    private static int tickDelay = 0;

    @Override
    public void onInitializeClient() {
        // Correção de construtor do KeyMapping (Usando CATEGORY_GAMEPLAY do Fabric/Minecraft)
        triggerKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.xbowcart.trigger",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_X,
            KeyMapping.CATEGORY_GAMEPLAY
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (mc.player == null || mc.level == null) return;

            while (triggerKey.consumeClick()) {
                active = !active;
                step = 0;
                tickDelay = 0;
            }

            if (!active) return;

            if (tickDelay > 0) {
                tickDelay--;
                return;
            }

            BlockPos targetPos = mc.player.blockPosition().below();
            Vec3 targetVec = Vec3.atCenterOf(targetPos);

            if (mc.player.distanceToSqr(targetVec) > 9.0D) {
                active = false;
                return;
            }

            // Mira ultra-rápida estilo Blump/eyezingz (0.65F = ajuste de mira agressivo, mas visível em vídeo)
            applyProLookAt(targetVec);

            switch (step) {
                case 0:
                    // Etapa 1: Colocar Trilho
                    if (selectItem(Items.RAIL)) {
                        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(targetVec, Direction.UP, targetPos, false));
                        mc.player.swing(InteractionHand.MAIN_HAND);
                        tickDelay = 1; // 1 tick para dar o tempo exato do motor ler a física
                        step = 1;
                    } else {
                        active = false;
                    }
                    break;

                case 1:
                    // Etapa 2: Colocar Cart com TNT
                    if (selectItem(Items.TNT_MINECART)) {
                        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(targetVec, Direction.UP, targetPos, false));
                        mc.player.swing(InteractionHand.MAIN_HAND);
                        tickDelay = 1;
                        step = 2;
                    } else {
                        active = false;
                    }
                    break;

                case 2:
                    // Etapa 3: Isqueiro / Acendimento
                    if (selectItem(Items.FLINT_AND_STEEL)) {
                        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(targetVec, Direction.UP, targetPos, false));
                        mc.player.swing(InteractionHand.MAIN_HAND);
                        tickDelay = 1;
                        step = 3;
                    } else {
                        active = false;
                    }
                    break;

                case 3:
                    // Etapa 4: Disparo do Crossbow
                    if (selectItem(Items.CROSSBOW)) {
                        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                        tickDelay = 1;
                        step = 4;
                    } else {
                        active = false;
                    }
                    break;

                case 4:
                    // Etapa 5: Soltar e acionar a explosão do Cart
                    if (selectItem(Items.CROSSBOW)) {
                        mc.gameMode.releaseUsingItem(mc.player);
                        mc.player.swing(InteractionHand.MAIN_HAND);
                        active = false;
                    }
                    break;
            }
        });
    }

    private static void applyProLookAt(Vec3 target) {
        double dx = target.x - mc.player.getX();
        double dy = target.y - mc.player.getEyeY();
        double dz = target.z - mc.player.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);

        float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        float targetPitch = (float) (-Math.toDegrees(Math.atan2(dy, dist)));

        float currentYaw = mc.player.getYaw();
        float currentPitch = mc.player.getPitch();

        float yawDiff = Mth.wrapDegrees(targetYaw - currentYaw);
        float pitchDiff = Mth.wrapDegrees(targetPitch - currentPitch);

        // Multiplicador 0.65f garante rotação instantânea e visual legítimo para gravação
        mc.player.setYaw(currentYaw + yawDiff * 0.65F);
        mc.player.setPitch(currentPitch + pitchDiff * 0.65F);
    }

    private static boolean selectItem(Item item) {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).is(item)) {
                // Seleção de slot utilizando o método Setter do Mojang Mappings 1.21.11
                mc.player.getInventory().setSelectedHotbarSlot(i);
                return true;
            }
        }
        return false;
    }
}
