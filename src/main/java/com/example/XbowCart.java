package com.example;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Item;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraft.util.Mth;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

import java.util.List;

public class XbowCart implements ClientModInitializer {
    private static final Minecraft mc = Minecraft.getInstance();
    private static KeyMapping triggerKey;
    public static boolean enabled = false;
    private static boolean active = false;
    private static int stage = 0;
    private static int tickCounter = 0;

    private static BlockPos lockedBaseBlock = null;
    private static Vec3 lockedTargetVec = null;
    private static Direction lockedDirection = Direction.UP;

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

        // Ativação quando segura o Trilho e aponta para qualquer bloco
        if (!active) {
            boolean holdingRail = mc.player.getMainHandItem().is(Items.RAIL);
            HitResult hit = mc.hitResult;

            if (holdingRail && hit != null && hit.getType() == HitResult.Type.BLOCK) {
                BlockHitResult blockHit = (BlockHitResult) hit;
                
                active = true;
                stage = 0;
                tickCounter = 0;

                lockedBaseBlock = blockHit.getBlockPos();
                lockedDirection = blockHit.getDirection();
                
                Vec3 blockCenter = Vec3.atCenterOf(lockedBaseBlock);
                lockedTargetVec = new Vec3(blockCenter.x, blockHit.getLocation().y, blockCenter.z);
            }
        }

        if (!active || lockedBaseBlock == null || lockedTargetVec == null) return;

        if (tickCounter > 0) {
            tickCounter--;
            return;
        }

        if (mc.player.distanceToSqr(lockedTargetVec) > 20.0D) {
            resetSequence();
            return;
        }

        applyGrimBypassRotation(lockedTargetVec);

        BlockHitResult placementHit = new BlockHitResult(lockedTargetVec, lockedDirection, lockedBaseBlock, false);

        switch (stage) {
            case 0:
                // 1. Colocar Trilho
                if (selectItem(Items.RAIL)) {
                    mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, placementHit);
                    mc.player.swing(InteractionHand.MAIN_HAND);
                    tickCounter = 2; // 2 ticks de espera para sincronização do pacote
                    stage = 1;
                } else {
                    resetSequence();
                }
                break;

            case 1:
                // 2. Colocar TNT Minecart
                if (selectItem(Items.TNT_MINECART)) {
                    BlockPos railPos = lockedBaseBlock.relative(lockedDirection);
                    BlockHitResult cartHit = new BlockHitResult(Vec3.atCenterOf(railPos), Direction.UP, railPos, false);
                    
                    mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, cartHit);
                    mc.player.swing(InteractionHand.MAIN_HAND);
                    tickCounter = 2;
                    stage = 2;
                } else {
                    stage = 2;
                }
                break;

            case 2:
                // 3. Acionar Isqueiro (Flint & Steel) na entidade do carrinho ou bloco
                if (selectItem(Items.FLINT_AND_STEEL)) {
                    BlockPos cartPos = lockedBaseBlock.relative(lockedDirection);
                    Vec3 cartVec = Vec3.atCenterOf(cartPos);
                    
                    AABB searchBox = new AABB(cartPos).inflate(1.5D);
                    List<Entity> minecarts = mc.level.getEntities((Entity) null, searchBox, e -> e.getType().getDescriptionId().contains("minecart"));

                    if (!minecarts.isEmpty()) {
                        mc.gameMode.interact(mc.player, minecarts.get(0), InteractionHand.MAIN_HAND);
                    } else {
                        BlockHitResult flintHit = new BlockHitResult(cartVec, Direction.UP, cartPos, false);
                        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, flintHit);
                    }
                    
                    mc.player.swing(InteractionHand.MAIN_HAND);
                    tickCounter = 2;
                    stage = 3;
                } else {
                    stage = 3;
                }
                break;

            case 3:
                // 4. Carregar Crossbow
                if (selectItem(Items.CROSSBOW)) {
                    mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                    mc.player.swing(InteractionHand.MAIN_HAND);
                    tickCounter = 2;
                    stage = 4;
                } else {
                    resetSequence();
                }
                break;

            case 4:
                // 5. Disparar (Permanece no Crossbow, sem SwapBack)
                if (selectItem(Items.CROSSBOW)) {
                    mc.gameMode.releaseUsingItem(mc.player);
                    mc.player.swing(InteractionHand.MAIN_HAND);
                }
                resetSequence();
                break;
        }
    }

    private static void applyGrimBypassRotation(Vec3 target) {
        if (mc.player == null) return;

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

        double sensitivity = mc.options.sensitivity().get();
        double f = sensitivity * 0.6D + 0.2D;
        double gcd = f * f * f * 8.0D * 0.15D;

        float interpolatedYaw = mc.player.getYRot() + (yawDiff * 0.65F);
        float interpolatedPitch = mc.player.getXRot() + (pitchDelta(pitchDiff));

        float finalYaw = (float) (mc.player.getYRot() + Math.round((interpolatedYaw - mc.player.getYRot()) / gcd) * gcd);
        float finalPitch = (float) (mc.player.getXRot() + Math.round((interpolatedPitch - mc.player.getXRot()) / gcd) * gcd);

        mc.player.setYRot(finalYaw);
        mc.player.setXRot(Mth.clamp(finalPitch, -90.0F, 90.0F));
    }

    private static float pitchDelta(float pitchDiff) {
        return pitchDiff * 0.65F;
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
        lockedDirection = Direction.UP;
    }
}
