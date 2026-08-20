package com.example;

import net.minecraft.client.Minecraft;
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
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import java.util.Random;

public class AutoMace {
    // ===== CONFIGURAÇÕES (exatamente como no Echo) =====
    private static final float FALL_THRESHOLD = 2.0f;
    private static final double RANGE = 7.0;
    private static final double ATTACK_RANGE = 3.0;
    private static final long ATTACK_DELAY = 120;
    private static final float MAX_TURN_SPEED = 6.0f;
    private static final float ATTENTION = 0.7f;

    // ===== ESTADO =====
    private static boolean enabled = false;
    private static LivingEntity target = null;
    private static int originalSlot = -1;
    private static boolean isSwapped = false;
    private static int swapDelay = 0;
    private static long lastAttackTime = 0;
    private static final Random RANDOM = new Random();

    // ===== MIRA WINDMOUSE =====
    private static float smoothYaw = Float.NaN;
    private static float smoothPitch = Float.NaN;
    private static float velYaw = 0, velPitch = 0;
    private static Vec3 lastTargetPos = null;

    public static void setEnabled(boolean e) {
        enabled = e;
        if (!enabled) resetState(Minecraft.getInstance());
    }

    public static void toggle() {
        enabled = !enabled;
        if (!enabled) resetState(Minecraft.getInstance());
    }

    public static void onTick(Minecraft client) {
        if (!enabled || client.player == null || client.level == null) {
            if (isSwapped) resetState(client);
            return;
        }

        // ===== 1. QUEDA =====
        if (!client.player.onGround() && client.player.fallDistance >= FALL_THRESHOLD) {
            // ===== 2. ALVO =====
            target = findTarget(client);
            if (target == null) {
                resetState(client);
                return;
            }

            // ===== 3. MIRA =====
            applyWindMouse(client, target);

            // ===== 4. DISTÂNCIA =====
            if (client.player.distanceTo(target) > ATTACK_RANGE) return;

            // ===== 5. COOLDOWN E HESITAÇÃO =====
            if (System.currentTimeMillis() - lastAttackTime < ATTACK_DELAY) return;
            if (client.player.getAttackStrengthScale(0.0f) < 0.9f) return;
            if (RANDOM.nextInt(100) < 3) return;

            // ===== 6. SHIELD BREAK =====
            if (target instanceof Player p && p.isUsingItem() && p.getUseItem().getItem() instanceof ShieldItem) {
                int axe = findAxeSlot(client);
                if (axe != -1) {
                    if (originalSlot == -1) originalSlot = client.player.getInventory().getSelectedSlot();
                    client.player.getInventory().setSelectedSlot(axe);
                    isSwapped = true;
                    swapDelay = 2;
                    client.gameMode.attack(client.player, target);
                    client.player.swing(InteractionHand.MAIN_HAND);
                    lastAttackTime = System.currentTimeMillis();
                    resetState(client);
                    return;
                }
            }

            // ===== 7. TROCA PARA MACE =====
            ItemStack mainHand = client.player.getMainHandItem();
            boolean hasMace = !mainHand.isEmpty() && mainHand.is(Items.MACE);

            if (!hasMace) {
                if (swapDelay > 0) { swapDelay--; return; }
                int maceSlot = findBestMaceSlot(client);
                if (maceSlot == -1) {
                    resetState(client);
                    return;
                }
                if (originalSlot == -1) originalSlot = client.player.getInventory().getSelectedSlot();
                client.player.getInventory().setSelectedSlot(maceSlot);
                isSwapped = true;
                swapDelay = 2;
                return;
            }

            if (swapDelay > 0) { swapDelay--; return; }

            // ===== 8. MICRO-ERRO =====
            if (RANDOM.nextInt(100) < 5) {
                client.player.setYRot(client.player.getYRot() + (RANDOM.nextFloat() - 0.5f) * 1.5f);
            }

            // ===== 9. ATAQUE =====
            client.gameMode.attack(client.player, target);
            client.player.swing(InteractionHand.MAIN_HAND);
            lastAttackTime = System.currentTimeMillis();

            // ===== 10. RESET =====
            resetState(client);
        } else {
            resetState(client);
        }
    }

    // ===== SELEÇÃO DE ALVO (Echo) =====
    private static LivingEntity findTarget(Minecraft client) {
        AABB box = client.player.getBoundingBox().inflate(RANGE, 400, RANGE);
        List<LivingEntity> entities = client.level.getEntitiesOfClass(LivingEntity.class, box, e ->
                e != client.player && e.isAlive() && !e.isDeadOrDying()
                        && client.player.getY() > e.getY() + 0.3
                        && !e.isInvisible()
        );
        if (entities.isEmpty()) return null;
        // Prioriza o mais próximo (Echo)
        return entities.stream()
                .min((a, b) -> Double.compare(client.player.distanceToSqr(a), client.player.distanceToSqr(b)))
                .orElse(null);
    }

    // ===== MIRA WINDMOUSE (Echo) =====
    private static void applyWindMouse(Minecraft client, LivingEntity target) {
        Vec3 eye = client.player.getEyePosition();
        Vec3 targetPos = target.getEyePosition(client.getDeltaTracker().getGameTimeDeltaPartialTick(false));

        // Predição leve (10% da velocidade)
        if (lastTargetPos != null) {
            Vec3 vel = target.position().subtract(lastTargetPos);
            targetPos = targetPos.add(vel.scale(0.1));
        }
        lastTargetPos = target.position();

        double dx = targetPos.x - eye.x;
        double dy = targetPos.y - eye.y;
        double dz = targetPos.z - eye.z;
        double dist = Math.sqrt(dx * dx + dz * dz);

        float targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float targetPitch = (float) -Math.toDegrees(Math.atan2(dy, dist));

        if (Float.isNaN(smoothYaw) || Float.isNaN(smoothPitch)) {
            smoothYaw = client.player.getYRot();
            smoothPitch = client.player.getXRot();
            velYaw = 0;
            velPitch = 0;
        }

        float yDiff = targetYaw - smoothYaw;
        while (yDiff > 180) yDiff -= 360;
        while (yDiff < -180) yDiff += 360;

        float pDiff = targetPitch - smoothPitch;
        while (pDiff > 180) pDiff -= 360;
        while (pDiff < -180) pDiff += 360;

        // ===== WINDMOUSE (inércia) =====
        float attention = ATTENTION + (float)(1.0 / (dist + 0.5)) * 0.2f;
        if (attention > 0.9f) attention = 0.9f;

        float maxTurn = MAX_TURN_SPEED + (RANDOM.nextFloat() - 0.5f) * 1.0f;

        float targetVelY = yDiff * attention;
        float targetVelP = pDiff * attention * 0.6f;

        targetVelY = Math.max(-maxTurn, Math.min(maxTurn, targetVelY));
        targetVelP = Math.max(-maxTurn * 0.6f, Math.min(maxTurn * 0.6f, targetVelP));

        // Inércia (WindMouse)
        float inertia = 0.85f;
        velYaw = velYaw * inertia + targetVelY * (1 - inertia);
        velPitch = velPitch * inertia + targetVelP * (1 - inertia);

        // Jitter (micro-movimentos)
        velYaw += (RANDOM.nextFloat() - 0.5f) * 0.12f;
        velPitch += (RANDOM.nextFloat() - 0.5f) * 0.08f;

        smoothYaw += velYaw;
        smoothPitch += velPitch;
        smoothPitch = Math.max(-90, Math.min(90, smoothPitch));

        client.player.setYRot(smoothYaw);
        client.player.setXRot(smoothPitch);
        client.player.yHeadRot = smoothYaw;
        client.player.yHeadRotO = smoothYaw;
    }

    // ===== MACHADO =====
    private static int findAxeSlot(Minecraft client) {
        for (int i = 0; i < 9; i++) {
            ItemStack s = client.player.getInventory().getItem(i);
            if (!s.isEmpty() && s.getItem() instanceof AxeItem) return i;
        }
        return -1;
    }

    // ===== MELHOR MACE =====
    private static int findBestMaceSlot(Minecraft client) {
        RegistryAccess reg = client.level.registryAccess();
        var lookup = reg.lookupOrThrow(Registries.ENCHANTMENT);
        Holder<Enchantment> density = lookup.getOrThrow(Enchantments.DENSITY);
        Holder<Enchantment> breach = lookup.getOrThrow(Enchantments.BREACH);

        int best = -1, bestScore = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack s = client.player.getInventory().getItem(i);
            if (s.isEmpty() || !s.is(Items.MACE)) continue;
            int d = EnchantmentHelper.getItemEnchantmentLevel(density, s);
            int b = EnchantmentHelper.getItemEnchantmentLevel(breach, s);
            int score = d * 2 + b;
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        if (best == -1) {
            for (int i = 0; i < 9; i++) {
                ItemStack s = client.player.getInventory().getItem(i);
                if (!s.isEmpty() && s.is(Items.MACE)) {
                    best = i;
                    break;
                }
            }
        }
        return best;
    }

    // ===== RESET =====
    private static void resetState(Minecraft client) {
        if (isSwapped && originalSlot != -1 && client.player != null) {
            client.player.getInventory().setSelectedSlot(originalSlot);
        }
        originalSlot = -1;
        isSwapped = false;
        swapDelay = 0;
        lastTargetPos = null;
        if (Float.isNaN(smoothYaw)) {
            smoothYaw = client.player != null ? client.player.getYRot() : 0;
            smoothPitch = client.player != null ? client.player.getXRot() : 0;
            velYaw = 0;
            velPitch = 0;
        }
    }
                                                                          }
