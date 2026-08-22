package com.example;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionHand;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;

public class AutoMace implements ClientModInitializer {
    private static final Minecraft mc = Minecraft.getInstance();
    private static KeyMapping toggleKey;
    public static boolean enabled = false;

    // Configurações de Combate e Alcance
    private static final double AIM_RANGE = 7.0D;
    private static final double SWING_RANGE = 2.95D;
    private static final double MIN_FALL_DIST = 1.5D;
    private static final float AIM_SMOOTHNESS = 0.35F;

    private static Player currentTarget = null;
    private static int preSequenceSlot = -1;
    private static long lastComboTime = 0L;
    private static long axeHitTime = 0L;
    private static int delayTicks = 0;
    private static double highestY = 0.0D;

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.automace.toggle",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_M,
            KeyMapping.CATEGORY_GAMEPLAY
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (mc.player == null || mc.level == null) return;

            while (toggleKey.consumeClick()) {
                enabled = !enabled;
                mc.player.displayClientMessage(Component.literal("§6[AutoMace] " + (enabled ? "§aON" : "§cOFF")), true);
            }

            if (enabled) {
                onTick(client);
            }
        });
    }

    public static void onTick(Minecraft client) {
        if (mc.player == null || mc.level == null) return;

        if (delayTicks > 0) {
            delayTicks--;
            return;
        }

        // Rastreamento de altura para quedas massivas (500+ blocos)
        if (mc.player.onGround()) {
            highestY = mc.player.getY();
        } else {
            highestY = Math.max(highestY, mc.player.getY());
        }
        double manualFallDist = Math.max(0.0D, highestY - mc.player.getY());

        currentTarget = findTarget(AIM_RANGE);
        if (currentTarget == null) return;

        // Suavização de rotação legítima com correção de sensibilidade (GCD)
        applySmoothHumanLookAt(currentTarget);

        boolean isFalling = mc.player.fallDistance >= MIN_FALL_DIST || manualFallDist >= MIN_FALL_DIST;
        if (!isFalling) return;

        // Verificação de escudo para atuar o Stun Slam
        boolean isBlocking = currentTarget.isUsingItem() && currentTarget.getUseItem().getItem() instanceof ShieldItem;

        if (isBlocking) {
            executeStunSlam(currentTarget);
        } else {
            executeOptimizedMaceSmash(currentTarget, manualFallDist);
        }
    }

    private static void executeStunSlam(Player target) {
        int axeSlot = findItemSlot(AxeItem.class);
        int maceSlot = selectOptimalMaceSlot(target, 0.0D);

        if (axeSlot != -1 && maceSlot != -1) {
            if (preSequenceSlot == -1) {
                preSequenceSlot = getHotbarSlot();
            }

            // Quebra de escudo com machado
            setHotbarSlot(axeSlot);
            mc.gameMode.attack(mc.player, target);
            mc.player.swing(InteractionHand.MAIN_HAND);
            axeHitTime = System.currentTimeMillis();

            // Golfe sequencial com a maça
            setHotbarSlot(maceSlot);
            mc.gameMode.attack(mc.player, target);
            mc.player.swing(InteractionHand.MAIN_HAND);

            restorePreSequenceSlot();
            delayTicks = 6;
        }
    }

    private static void executeOptimizedMaceSmash(Player target, double fallDistance) {
        if (mc.player.distanceTo(target) > SWING_RANGE) return;

        int optimalMaceSlot = selectOptimalMaceSlot(target, fallDistance);
        if (optimalMaceSlot != -1) {
            if (preSequenceSlot == -1) {
                preSequenceSlot = getHotbarSlot();
            }

            setHotbarSlot(optimalMaceSlot);
            mc.gameMode.attack(mc.player, target);
            mc.player.swing(InteractionHand.MAIN_HAND);

            restorePreSequenceSlot();
            lastComboTime = System.currentTimeMillis();
            delayTicks = 5;
        }
    }

    private static int selectOptimalMaceSlot(Player target, double fallDist) {
        int bestSlot = -1;
        int maxDensityScore = -1;
        int maxBreachScore = -1;

        double distance = mc.player.distanceTo(target);

        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.getItem() instanceof MaceItem) {
                int densityLevel = getEnchantmentLevel(stack, "density");
                int breachLevel = getEnchantmentLevel(stack, "breach");

                // Prioridade: Breach para combate próximo/alvo blindado (< 7 blocos); Density para grandes quedas
                if (distance < 7.0D && breachLevel > maxBreachScore) {
                    maxBreachScore = breachLevel;
                    bestSlot = i;
                } else if (fallDist >= 7.0D && densityLevel > maxDensityScore) {
                    maxDensityScore = densityLevel;
                    bestSlot = i;
                } else if (bestSlot == -1) {
                    bestSlot = i;
                }
            }
        }
        return bestSlot;
    }

    private static int getEnchantmentLevel(ItemStack stack, String enchantmentNameId) {
        if (stack.isEmpty()) return 0;
        ItemEnchantments enchantments = stack.get(DataComponents.ENCHANTMENTS);
        if (enchantments == null) return 0;

        for (var entry : enchantments.entrySet()) {
            String id = entry.getKey().getRegisteredName();
            if (id != null && id.contains(enchantmentNameId)) {
                return entry.getIntValue();
            }
        }
        return 0;
    }

    private static void applySmoothHumanLookAt(Player target) {
        Vec3 targetVec = target.getBoundingBox().getCenter();
        double dx = targetVec.x - mc.player.getX();
        double dy = targetVec.y - mc.player.getEyeY();
        double dz = targetVec.z - mc.player.getZ();
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);

        float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        float targetPitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontalDist)));

        float yawDelta = Mth.wrapDegrees(targetYaw - mc.player.getYaw());
        float pitchDelta = Mth.wrapDegrees(targetPitch - mc.player.getPitch());

        // Correção de GCD (Garante curva de rotação humana para gravações)
        double sensitivity = mc.options.sensitivity().getValue();
        double f = sensitivity * 0.6D + 0.2D;
        double gcd = f * f * f * 8.0D * 0.15D;

        float nextYaw = mc.player.getYaw() + (yawDelta * AIM_SMOOTHNESS);
        float nextPitch = mc.player.getPitch() + (pitchDelta * AIM_SMOOTHNESS);

        nextYaw = (float) (mc.player.getYaw() + Math.round((nextYaw - mc.player.getYaw()) / gcd) * gcd);
        nextPitch = (float) (mc.player.getPitch() + Math.round((nextPitch - mc.player.getPitch()) / gcd) * gcd);

        mc.player.setYaw(nextYaw);
        mc.player.setPitch(Mth.clamp(nextPitch, -90.0F, 90.0F));
    }

    private static int findItemSlot(Class<?> itemClass) {
        for (int i = 0; i < 9; i++) {
            if (itemClass.isInstance(mc.player.getInventory().getItem(i).getItem())) {
                return i;
            }
        }
        return -1;
    }

    private static Player findTarget(double range) {
        if (mc.level == null || mc.player == null) return null;
        Player best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (Player player : mc.level.players()) {
            if (player != mc.player && player.isAlive() && !player.isSpectator()) {
                double distSq = mc.player.distanceToSqr(player);
                if (distSq <= range * range && distSq < bestDistSq) {
                    bestDistSq = distSq;
                    best = player;
                }
            }
        }
        return best;
    }

    private static void setHotbarSlot(int slot) {
        if (mc.player == null || slot < 0 || slot > 8) return;
        try {
            mc.player.getInventory().selected = slot;
        } catch (Throwable t) {
            try {
                Field field = Inventory.class.getDeclaredField("selected");
                field.setAccessible(true);
                field.setInt(mc.player.getInventory(), slot);
            } catch (Exception ignored) {}
        }
    }

    private static int getHotbarSlot() {
        if (mc.player == null) return 0;
        try {
            return mc.player.getInventory().selected;
        } catch (Throwable t) {
            try {
                Field field = Inventory.class.getDeclaredField("selected");
                field.setAccessible(true);
                return field.getInt(mc.player.getInventory());
            } catch (Exception ignored) {}
        }
        return 0;
    }

    private static void restorePreSequenceSlot() {
        if (preSequenceSlot >= 0 && preSequenceSlot < 9) {
            setHotbarSlot(preSequenceSlot);
        }
        preSequenceSlot = -1;
    }
    }
