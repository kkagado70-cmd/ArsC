package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import java.util.Random;

public class AutoMace {
    public static boolean enabled = false;

    private static final boolean STREAMER_MODE = true;
    private static final float MAX_TURN_SPEED = 6.0f;
    private static final float ATTENTION = 0.7f;
    private static final double TARGET_RANGE = 7.0;
    private static final double ATTACK_DISTANCE = 3.0; // distância máxima de ataque (padrão Minecraft)
    private static final float FALL_THRESHOLD = 2.0f;
    private static final long ATTACK_DELAY_MS = 120;

    private static long lastAttackTime = 0;
    private static int originalSlot = -1;
    private static int swapDelayTicks = 0;
    private static boolean isSwapped = false;
    private static LivingEntity target = null;
    private static final Random RANDOM = new Random();

    private static float smoothYaw = Float.NaN;
    private static float smoothPitch = Float.NaN;

    public static void onTick(Minecraft client) {
        if (!enabled || client.player == null || client.level == null) {
            if (isSwapped) resetState(client);
            return;
        }

        if (swapDelayTicks > 0) {
            swapDelayTicks--;
            return;
        }

        target = findTarget(client);
        if (target == null) {
            resetState(client);
            return;
        }

        boolean isFalling = !client.player.onGround() && client.player.fallDistance >= FALL_THRESHOLD;
        if (!isFalling) {
            resetState(client);
            return;
        }

        applySmoothAim(client, target);

        // ===== VERIFICA DISTÂNCIA DE ATAQUE =====
        double dist = client.player.distanceTo(target);
        if (dist > ATTACK_DISTANCE) {
            resetState(client);
            return;
        }

        // ===== VERIFICA FOV (não ataca alvos atrás) =====
        if (!isTargetInFov(client, target, 90.0f)) {
            resetState(client);
            return;
        }

        // ===== COOLDOWN DO ATAQUE =====
        float strength = client.player.getAttackStrengthScale(0.0f);
        if (strength < 0.9f) return;

        // ===== DELAY HUMANO =====
        if (System.currentTimeMillis() - lastAttackTime < ATTACK_DELAY_MS) return;

        // ===== HESITAÇÃO STREAMER =====
        if (STREAMER_MODE && RANDOM.nextInt(100) < 3) {
            return;
        }

        // ===== VERIFICA SE JÁ ESTÁ SEGURANDO UMA MACE =====
        ItemStack mainHand = client.player.getMainHandItem();
        boolean hasMace = !mainHand.isEmpty() && mainHand.is(Items.MACE);

        if (!hasMace) {
            int maceSlot = findBestMaceSlot(client);
            if (maceSlot == -1) {
                resetState(client);
                return;
            }
            if (originalSlot == -1) originalSlot = client.player.getInventory().getSelectedSlot();
            client.player.getInventory().setSelectedSlot(maceSlot);
            isSwapped = true;
            swapDelayTicks = 2; // espera 2 ticks para o servidor registrar a troca
            return;
        }

        // ===== MICRO-ERRO (streamer) =====
        if (STREAMER_MODE && RANDOM.nextInt(100) < 5) {
            client.player.setYRot(client.player.getYRot() + (RANDOM.nextFloat() - 0.5f) * 2f);
        }

        // ===== ATAQUE =====
        client.gameMode.attack(client.player, target);
        client.player.swing(InteractionHand.MAIN_HAND);
        lastAttackTime = System.currentTimeMillis();

        resetState(client);
    }

    private static LivingEntity findTarget(Minecraft client) {
        AABB box = client.player.getBoundingBox().inflate(TARGET_RANGE, 400, TARGET_RANGE);
        List<LivingEntity> entities = client.level.getEntitiesOfClass(LivingEntity.class, box, e ->
                e != client.player && e.isAlive() && !e.isDeadOrDying()
                        && client.player.getY() > e.getY() + 0.3
                        && !e.isInvisible()
        );
        if (entities.isEmpty()) return null;
        return entities.stream()
                .min((a, b) -> Double.compare(client.player.distanceToSqr(a), client.player.distanceToSqr(b)))
                .orElse(null);
    }

    private static boolean isTargetInFov(Minecraft client, LivingEntity target, float fov) {
        Vec3 eye = client.player.getEyePosition();
        Vec3 targetVec = target.getEyePosition(client.getDeltaTracker().getGameTimeDeltaPartialTick(false));
        Vec3 direction = targetVec.subtract(eye).normalize();
        Vec3 lookVec = client.player.getViewVector(client.getDeltaTracker().getGameTimeDeltaPartialTick(false));
        double dot = lookVec.dot(direction);
        double angle = Math.toDegrees(Math.acos(dot));
        return angle <= fov / 2.0;
    }

    private static void applySmoothAim(Minecraft client, LivingEntity target) {
        Vec3 eye = client.player.getEyePosition();
        // Mira no centro do alvo (sem predição)
        Vec3 targetPos = target.getEyePosition(client.getDeltaTracker().getGameTimeDeltaPartialTick(false));

        double dx = targetPos.x - eye.x;
        double dy = targetPos.y - eye.y;
        double dz = targetPos.z - eye.z;
        double dist = Math.sqrt(dx * dx + dz * dz);

        float targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float targetPitch = (float) -Math.toDegrees(Math.atan2(dy, dist));

        if (Float.isNaN(smoothYaw) || Float.isNaN(smoothPitch)) {
            smoothYaw = client.player.getYRot();
            smoothPitch = client.player.getXRot();
        }

        float yawDiff = targetYaw - smoothYaw;
        while (yawDiff > 180) yawDiff -= 360;
        while (yawDiff < -180) yawDiff += 360;

        float pitchDiff = targetPitch - smoothPitch;
        while (pitchDiff > 180) pitchDiff -= 360;
        while (pitchDiff < -180) pitchDiff += 360;

        float stepYaw = Math.max(-MAX_TURN_SPEED, Math.min(MAX_TURN_SPEED, yawDiff * ATTENTION));
        float stepPitch = Math.max(-MAX_TURN_SPEED * 0.6f, Math.min(MAX_TURN_SPEED * 0.6f, pitchDiff * ATTENTION));

        if (STREAMER_MODE) {
            stepYaw += (RANDOM.nextFloat() - 0.5f) * 0.15f;
            stepPitch += (RANDOM.nextFloat() - 0.5f) * 0.10f;
        }

        smoothYaw += stepYaw;
        smoothPitch += stepPitch;
        smoothPitch = Math.max(-90, Math.min(90, smoothPitch));

        client.player.setYRot(smoothYaw);
        client.player.setXRot(smoothPitch);
        client.player.yHeadRot = smoothYaw;
    }

    private static int findBestMaceSlot(Minecraft client) {
        RegistryAccess reg = client.level.registryAccess();
        var lookup = reg.lookupOrThrow(Registries.ENCHANTMENT);
        Holder<Enchantment> density = lookup.getOrThrow(Enchantments.DENSITY);
        Holder<Enchantment> breach = lookup.getOrThrow(Enchantments.BREACH);

        int bestSlot = -1;
        int bestScore = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (stack.isEmpty() || !stack.is(Items.MACE)) continue;

            int dLvl = EnchantmentHelper.getItemEnchantmentLevel(density, stack);
            int bLvl = EnchantmentHelper.getItemEnchantmentLevel(breach, stack);
            int score = dLvl * 2 + bLvl;
            if (score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }
        // Se nenhuma mace com encantamento, pega qualquer mace
        if (bestSlot == -1) {
            for (int i = 0; i < 9; i++) {
                ItemStack stack = client.player.getInventory().getItem(i);
                if (!stack.isEmpty() && stack.is(Items.MACE)) {
                    bestSlot = i;
                    break;
                }
            }
        }
        return bestSlot;
    }

    private static void resetState(Minecraft client) {
        if (isSwapped && originalSlot != -1 && client.player != null) {
            client.player.getInventory().setSelectedSlot(originalSlot);
        }
        originalSlot = -1;
        isSwapped = false;
        swapDelayTicks = 0;
        if (Float.isNaN(smoothYaw)) {
            smoothYaw = client.player != null ? client.player.getYRot() : 0;
            smoothPitch = client.player != null ? client.player.getXRot() : 0;
        }
    }

    public static void toggle() {
        enabled = !enabled;
        if (!enabled) resetState(Minecraft.getInstance());
    }
    public static void reset() { resetState(Minecraft.getInstance()); }
                }
