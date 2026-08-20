package com.example.mixin;

import com.example.AutoMace;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.List;
import java.util.Random;

@Mixin(Minecraft.class)
public class MixinAutoMace {
    private static final Random R = new Random();

    // ===== ESTADO (igual ao Echo) =====
    private static LivingEntity target = null;
    private static int originalSlot = -1;
    private static boolean isSwapped = false;
    private static int swapDelay = 0;
    private static long lastAttackTime = 0;
    private static Vec3 lastTargetPos = null;

    // ===== MIRA WINDMOUSE =====
    private static float smoothYaw = Float.NaN;
    private static float smoothPitch = Float.NaN;
    private static float velYaw = 0, velPitch = 0;

    // ===== CONFIGURAÇÕES =====
    private static final float FALL_THRESHOLD = 2.0f;
    private static final double RANGE = 7.0;
    private static final double ATTACK_RANGE = 3.0;
    private static final long ATTACK_DELAY = 120;
    private static final float MAX_TURN_SPEED = 6.0f;
    private static final float ATTENTION = 0.7f;

    @Inject(method = "tick", at = @At("HEAD"))
    private void onTick(CallbackInfo ci) {
        Minecraft c = Minecraft.getInstance();
        if (c.player == null || c.level == null) return;
        if (!AutoMace.enabled) return;

        // ===== 1. VERIFICA QUEDA =====
        boolean isFalling = !c.player.onGround() && c.player.fallDistance >= FALL_THRESHOLD;
        if (!isFalling) {
            resetState(c);
            return;
        }

        // ===== 2. SELEÇÃO DE ALVO (igual ao Echo) =====
        target = findTarget(c);
        if (target == null) {
            resetState(c);
            return;
        }

        // ===== 3. MIRA WINDMOUSE (igual ao Echo) =====
        applyWindMouse(c, target);

        // ===== 4. DISTÂNCIA DE ATAQUE =====
        if (c.player.distanceTo(target) > ATTACK_RANGE) return;

        // ===== 5. COOLDOWN =====
        if (System.currentTimeMillis() - lastAttackTime < ATTACK_DELAY) return;
        if (c.player.getAttackStrengthScale(0.0f) < 0.9f) return;

        // ===== 6. HESITAÇÃO NATURAL =====
        if (R.nextInt(100) < 3) return;

        // ===== 7. SHIELD BREAK COM MACHADO =====
        if (target instanceof Player p && p.isUsingItem() && p.getUseItem().getItem() instanceof ShieldItem) {
            int axe = findAxeSlot(c);
            if (axe != -1) {
                if (originalSlot == -1) originalSlot = c.player.getInventory().getSelectedSlot();
                c.player.getInventory().setSelectedSlot(axe);
                isSwapped = true;
                swapDelay = 2;
                c.gameMode.attack(c.player, target);
                c.player.swing(InteractionHand.MAIN_HAND);
                lastAttackTime = System.currentTimeMillis();
                resetState(c);
                return;
            }
        }

        // ===== 8. TROCA PARA MACE (se necessário) =====
        ItemStack mainHand = c.player.getMainHandItem();
        boolean hasMace = !mainHand.isEmpty() && mainHand.is(Items.MACE);

        if (!hasMace) {
            if (swapDelay > 0) { swapDelay--; return; }
            int maceSlot = findBestMaceSlot(c);
            if (maceSlot == -1) {
                resetState(c);
                return;
            }
            if (originalSlot == -1) originalSlot = c.player.getInventory().getSelectedSlot();
            c.player.getInventory().setSelectedSlot(maceSlot);
            isSwapped = true;
            swapDelay = 2;
            return;
        }

        if (swapDelay > 0) { swapDelay--; return; }

        // ===== 9. MICRO-ERRO (jitter) =====
        if (R.nextInt(100) < 5) {
            c.player.setYRot(c.player.getYRot() + (R.nextFloat() - 0.5f) * 1.5f);
        }

        // ===== 10. ATAQUE =====
        c.gameMode.attack(c.player, target);
        c.player.swing(InteractionHand.MAIN_HAND);
        lastAttackTime = System.currentTimeMillis();

        // ===== 11. VOLTA PARA SLOT ORIGINAL =====
        resetState(c);
    }

    // ===== SELEÇÃO DE ALVO (Echo) =====
    private static LivingEntity findTarget(Minecraft c) {
        AABB box = c.player.getBoundingBox().inflate(RANGE, 400, RANGE);
        List<LivingEntity> list = c.level.getEntitiesOfClass(LivingEntity.class, box, e ->
                e != c.player && e.isAlive() && !e.isDeadOrDying()
                        && c.player.getY() > e.getY() + 0.3
                        && !e.isInvisible()
        );
        if (list.isEmpty()) return null;
        // Prioriza o mais próximo (Echo)
        return list.stream()
                .min((a, b) -> Double.compare(c.player.distanceToSqr(a), c.player.distanceToSqr(b)))
                .orElse(null);
    }

    // ===== MIRA WINDMOUSE (Echo) =====
    private static void applyWindMouse(Minecraft c, LivingEntity target) {
        Vec3 eye = c.player.getEyePosition();
        Vec3 targetPos = target.getEyePosition(c.getDeltaTracker().getGameTimeDeltaPartialTick(false));

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
            smoothYaw = c.player.getYRot();
            smoothPitch = c.player.getXRot();
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

        float maxTurn = MAX_TURN_SPEED + (R.nextFloat() - 0.5f) * 1.0f;

        float targetVelY = yDiff * attention;
        float targetVelP = pDiff * attention * 0.6f;

        // Limita velocidade
        targetVelY = Math.max(-maxTurn, Math.min(maxTurn, targetVelY));
        targetVelP = Math.max(-maxTurn * 0.6f, Math.min(maxTurn * 0.6f, targetVelP));

        // Inércia (WindMouse)
        float inertia = 0.85f;
        velYaw = velYaw * inertia + targetVelY * (1 - inertia);
        velPitch = velPitch * inertia + targetVelP * (1 - inertia);

        // Jitter (micro-movimentos)
        velYaw += (R.nextFloat() - 0.5f) * 0.12f;
        velPitch += (R.nextFloat() - 0.5f) * 0.08f;

        smoothYaw += velYaw;
        smoothPitch += velPitch;
        smoothPitch = Math.max(-90, Math.min(90, smoothPitch));

        c.player.setYRot(smoothYaw);
        c.player.setXRot(smoothPitch);
        c.player.yHeadRot = smoothYaw;
        c.player.yHeadRotO = smoothYaw;
    }

    // ===== MACHADO PARA SHIELD BREAK =====
    private static int findAxeSlot(Minecraft c) {
        for (int i = 0; i < 9; i++) {
            ItemStack s = c.player.getInventory().getItem(i);
            if (!s.isEmpty() && s.getItem() instanceof net.minecraft.world.item.AxeItem) return i;
        }
        return -1;
    }

    // ===== MELHOR MACE (Density + Breach) =====
    private static int findBestMaceSlot(Minecraft c) {
        RegistryAccess reg = c.level.registryAccess();
        var lookup = reg.lookupOrThrow(Registries.ENCHANTMENT);
        Holder<Enchantment> density = lookup.getOrThrow(Enchantments.DENSITY);
        Holder<Enchantment> breach = lookup.getOrThrow(Enchantments.BREACH);

        int best = -1, bestScore = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack s = c.player.getInventory().getItem(i);
            if (s.isEmpty() || !s.is(Items.MACE)) continue;
            int d = EnchantmentHelper.getItemEnchantmentLevel(density, s);
            int b = EnchantmentHelper.getItemEnchantmentLevel(breach, s);
            int score = d * 2 + b;
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        // Fallback: qualquer mace
        if (best == -1) {
            for (int i = 0; i < 9; i++) {
                ItemStack s = c.player.getInventory().getItem(i);
                if (!s.isEmpty() && s.is(Items.MACE)) {
                    best = i;
                    break;
                }
            }
        }
        return best;
    }

    // ===== RESET =====
    private static void resetState(Minecraft c) {
        if (isSwapped && originalSlot != -1 && c.player != null) {
            c.player.getInventory().setSelectedSlot(originalSlot);
        }
        originalSlot = -1;
        isSwapped = false;
        swapDelay = 0;
        lastTargetPos = null;
        if (Float.isNaN(smoothYaw)) {
            smoothYaw = c.player != null ? c.player.getYRot() : 0;
            smoothPitch = c.player != null ? c.player.getXRot() : 0;
            velYaw = 0;
            velPitch = 0;
        }
    }
}
