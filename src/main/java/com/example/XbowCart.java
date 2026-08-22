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
    }

    public static void onTick(Minecraft client) {
        if (mc.player == null || mc.level == null) return;

        boolean holdingRail = mc.player.getMainHandItem().is(Items.RAIL);
        HitResult hit = mc.hitResult;

        if (holdingRail && (mc.player.getXRot() > 30.0F || (hit != null && hit.getType() == HitResult.Type.BLOCK))) {
            executeHT1Sequence(hit);
        }
    }

    private static void executeHT1Sequence(HitResult hit) {
        if (mc.player == null || mc.level == null) return;

        int preSlot = mc.player.getInventory().getSelectedSlot();
        BlockPos baseBlock;
        Direction side = Direction.UP;

        if (hit != null && hit.getType() == HitResult.Type.BLOCK) {
            BlockHitResult blockHit = (BlockHitResult) hit;
            baseBlock = blockHit.getBlockPos();
            side = blockHit.getDirection();
        } else {
            baseBlock = mc.player.blockPosition().below();
        }

        BlockPos placedPos = baseBlock.relative(side);
        Vec3 targetVec = Vec3.atCenterOf(placedPos);

        if (mc.player.distanceToSqr(targetVec) > 12.0D) return;

        // Rotação suave no campo de visão para gravação legítima
        applyProLookAt(targetVec);

        BlockHitResult baseHit = new BlockHitResult(targetVec, side, baseBlock, false);
        BlockHitResult cartHit = new BlockHitResult(targetVec, Direction.UP, placedPos, false);

        // Execução HT1 em sub-tick sequencial
        // 1. Trilho
        if (selectItem(Items.RAIL)) {
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, baseHit);
            mc.player.swing(InteractionHand.MAIN_HAND);
        }

        // 2. Carrinho TNT
        if (selectItem(Items.TNT_MINECART)) {
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, cartHit);
            mc.player.swing(InteractionHand.MAIN_HAND);
        }

        // 3. Isqueiro
        if (selectItem(Items.FLINT_AND_STEEL)) {
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, cartHit);
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            mc.player.swing(InteractionHand.MAIN_HAND);
        }

        // 4. Crossbow
        if (selectItem(Items.CROSSBOW)) {
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            mc.gameMode.releaseUsingItem(mc.player);
            mc.player.swing(InteractionHand.MAIN_HAND);
        }

        // Restaura a hotbar original imediatamente
        if (preSlot >= 0 && preSlot < 9) {
            mc.player.getInventory().setSelectedSlot(preSlot);
        }
    }

    private static void applyProLookAt(Vec3 target) {
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

        float interpolatedYaw = mc.player.getYRot() + (yawDiff * 0.70F);
        float interpolatedPitch = mc.player.getXRot() + (pitchDiff * 0.70F);

        float finalYaw = (float) (mc.player.getYRot() + Math.round((interpolatedYaw - mc.player.getYRot()) / gcd) * gcd);
        float finalPitch = (float) (mc.player.getXRot() + Math.round((interpolatedPitch - mc.player.getXRot()) / gcd) * gcd);

        mc.player.setYRot(finalYaw);
        mc.player.setXRot(Mth.clamp(finalPitch, -90.0F, 90.0F));
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
}
