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
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class AutoMace {
    // ============================================================
    // ENUMS E CONFIGURAÇÕES GLOBAIS
    // ============================================================
    public enum State {
        IDLE,
        TARGETING,
        APPROACHING,
        PRE_SMASH,
        SMASH_ATTACK,
        POST_SMASH,
        DELAY,
        RESET
    }

    public enum Mode {
        STEALTH,
        AGGRESSIVE,
        RANDOM
    }

    public static boolean enabled = false;
    public static Mode currentMode = Mode.STEALTH;

    // Estado principal
    private static State state = State.IDLE;
    private static long lastActionTime = 0;
    private static int originalSlot = -1;
    private static boolean isSwapped = false;
    private static int delayTicks = 0;
    private static LivingEntity activeTarget = null;
    private static int targetLockTicks = 0;
    private static final Random RANDOM = new Random();

    // Sistema de mira heurística
    private static float smoothYaw = Float.NaN;
    private static float smoothPitch = Float.NaN;
    private static float previousYaw = 0;
    private static float previousPitch = 0;
    private static final Queue<Float> yawHistory = new ConcurrentLinkedQueue<>();
    private static final Queue<Float> pitchHistory = new ConcurrentLinkedQueue<>();
    private static final int HISTORY_SIZE = 15;

    // Sistema de erro humano
    private static int missChance = 0;
    private static int hesitationTicks = 0;
    private static boolean isHesitating = false;
    private static int pingSimulationTicks = 0;
    private static int attackCooldownTicks = 0;

    // Sistema de previsão de movimento
    private static final Map<LivingEntity, Vec3> targetVelocities = new HashMap<>();
    private static final Map<LivingEntity, Long> lastVelocityUpdate = new HashMap<>();

    // Sistema de randomização heurística
    private static final Map<String, Integer> heuristicCounters = new HashMap<>();
    private static int totalTicks = 0;
    private static int successfulSmash = 0;
    private static int missedSmash = 0;

    // ============================================================
    // MÉTODOS PRINCIPAIS
    // ============================================================

    public static void onTick(Minecraft client) {
        if (!enabled || client.player == null || client.level == null) {
            if (isSwapped) resetState(client);
            return;
        }

        totalTicks++;

        // Processa delays e cooldowns
        if (attackCooldownTicks > 0) {
            attackCooldownTicks--;
            return;
        }
        if (pingSimulationTicks > 0) {
            pingSimulationTicks--;
            return;
        }
        if (delayTicks > 0) {
            delayTicks--;
            return;
        }
        if (hesitationTicks > 0) {
            hesitationTicks--;
            return;
        }

        // Atualiza alvo com sistema de lock com variação
        if (targetLockTicks <= 0 || activeTarget == null || !activeTarget.isAlive()) {
            activeTarget = findOptimalTarget(client);
            targetLockTicks = 5 + RANDOM.nextInt(12);
        } else {
            targetLockTicks--;
        }

        if (activeTarget == null) {
            resetState(client);
            return;
        }

        // Verifica se o alvo está abaixo (para smash)
        if (client.player.getY() <= activeTarget.getY() + 0.3) {
            activeTarget = null;
            resetState(client);
            return;
        }

        // Atualiza a velocidade do alvo para predição
        updateTargetVelocity(client, activeTarget);

        // Sistema de hesitação humana aleatória (anti-flag)
        if (RANDOM.nextInt(100) < 2 && !isHesitating && state == State.IDLE) {
            isHesitating = true;
            hesitationTicks = 2 + RANDOM.nextInt(6);
            return;
        }
        if (isHesitating && hesitationTicks <= 0) {
            isHesitating = false;
        }

        // Verifica se está caindo (com variação heurística)
        float fallThreshold = calculateFallThreshold(client);
        boolean isFalling = client.player.fallDistance >= fallThreshold
                && !client.player.onGround()
                && !client.player.isInWater();

        if (isFalling) {
            // ==========================================================
            // FASE DE QUEDA – PREPARAÇÃO PARA O SMASH
            // ==========================================================
            state = State.PRE_SMASH;

            // Aplica a mira heurística avançada
            applyHeuristicAim(client, activeTarget);

            // Verifica a distância de ataque com variação
            double hitDist = calculateHitDistance(client);
            if (client.player.distanceTo(activeTarget) <= hitDist) {
                // Verifica cooldown do jogo
                float strength = client.player.getAttackStrengthScale(0.0f);
                if (strength < 0.85f) return;

                // Sistema de erro humano dinâmico
                int missRate = calculateMissRate(client);
                if (RANDOM.nextInt(100) < missRate) {
                    missChance = 1 + RANDOM.nextInt(3);
                }
                if (missChance > 0) {
                    missChance--;
                    applyMissAim(client);
                    missedSmash++;
                    return;
                }

                // Shield break com machado (com delay)
                boolean isShielding = activeTarget instanceof Player p
                        && p.isUsingItem()
                        && p.getUseItem().getItem() instanceof ShieldItem;

                if (isShielding && state == State.PRE_SMASH) {
                    int axeSlot = findAxeSlot(client);
                    if (axeSlot != -1) {
                        if (originalSlot == -1) originalSlot = client.player.getInventory().getSelectedSlot();
                        client.player.getInventory().setSelectedSlot(axeSlot);
                        client.gameMode.attack(client.player, activeTarget);
                        client.player.swing(InteractionHand.MAIN_HAND);
                        isSwapped = true;
                        state = State.SMASH_ATTACK;
                        delayTicks = 3 + RANDOM.nextInt(5);
                        lastActionTime = System.currentTimeMillis();
                        successfulSmash++;
                        return;
                    }
                }

                // Ataque smash com maça
                if (state == State.SMASH_ATTACK || state == State.PRE_SMASH) {
                    boolean preferDensity = client.player.fallDistance > (6.0 + RANDOM.nextDouble() * 2.0);
                    int maceSlot = findBestMaceSlot(client, preferDensity);
                    if (maceSlot != -1) {
                        if (originalSlot == -1) originalSlot = client.player.getInventory().getSelectedSlot();
                        client.player.getInventory().setSelectedSlot(maceSlot);
                        isSwapped = true;
                    }

                    // Executa o ataque
                    client.gameMode.attack(client.player, activeTarget);
                    client.player.swing(InteractionHand.MAIN_HAND);
                    lastActionTime = System.currentTimeMillis();
                    state = State.POST_SMASH;

                    // Delays pós-ataque (simula ping e reação)
                    attackCooldownTicks = 2 + RANDOM.nextInt(4);
                    pingSimulationTicks = 1 + RANDOM.nextInt(3);
                    delayTicks = 5 + RANDOM.nextInt(10);
                    hesitationTicks = 2 + RANDOM.nextInt(5);

                    // Reseta o estado (volta ao slot original)
                    resetState(client);
                    successfulSmash++;
                }
            } else {
                // Se estiver longe, ajusta o estado para abordagem
                state = State.APPROACHING;
            }
        } else if (client.player.onGround()) {
            // Ao cair no chão, reseta suavemente
            if (state != State.IDLE && state != State.TARGETING) {
                delayTicks = 2 + RANDOM.nextInt(4);
                resetState(client);
            }
            hesitationTicks = 0;
            isHesitating = false;
        } else {
            // Caso não esteja caindo nem no chão (ex: pulando)
            if (state == State.PRE_SMASH || state == State.SMASH_ATTACK) {
                state = State.IDLE;
            }
        }

        // Atualiza contadores heurísticos
        updateHeuristics(client);
    }

    // ============================================================
    // MÉTODOS HEURÍSTICOS DE CÁLCULO
    // ============================================================

    private static float calculateFallThreshold(Minecraft client) {
        // Variação do threshold de queda baseado no modo e no ping simulado
        float base = 2.8f;
        float variation = 0.0f;

        switch (currentMode) {
            case STEALTH:
                variation = 0.3f + RANDOM.nextFloat() * 0.4f;
                break;
            case AGGRESSIVE:
                variation = 0.1f + RANDOM.nextFloat() * 0.2f;
                break;
            case RANDOM:
                variation = RANDOM.nextFloat() * 0.8f;
                break;
        }

        // Se estiver com ping simulado, aumenta o threshold
        if (pingSimulationTicks > 0) {
            variation += 0.2f;
        }

        return base + variation;
    }

    private static double calculateHitDistance(Minecraft client) {
        // Variação da distância de ataque baseada no modo e no ping
        double base = 2.85;
        double variation = 0.0;

        switch (currentMode) {
            case STEALTH:
                variation = 0.1 + RANDOM.nextDouble() * 0.2;
                break;
            case AGGRESSIVE:
                variation = 0.05 + RANDOM.nextDouble() * 0.1;
                break;
            case RANDOM:
                variation = RANDOM.nextDouble() * 0.35;
                break;
        }

        // Se o alvo está se movendo, aumenta a distância
        if (activeTarget != null && isTargetMoving()) {
            variation += 0.1;
        }

        return base + variation;
    }

    private static int calculateMissRate(Minecraft client) {
        // Taxa de erro dinâmica baseada em vários fatores
        int base = 2;
        int additional = 0;

        // Se está em modo STEALTH, erra mais para parecer humano
        if (currentMode == Mode.STEALTH) {
            additional += 2;
        }

        // Se o alvo está se movendo rápido, erra mais
        if (activeTarget != null && isTargetMovingFast()) {
            additional += 3;
        }

        // Se já está em estado de ataque, erra menos (já está focado)
        if (state == State.SMASH_ATTACK) {
            additional -= 1;
        }

        // Fator de cansaço (após vários ataques, erra mais)
        if (successfulSmash > 10) {
            additional += (successfulSmash / 10);
        }

        // Limita o máximo
        additional = Math.min(additional, 15);

        return base + additional;
    }

    private static boolean isTargetMoving() {
        if (activeTarget == null) return false;
        Vec3 vel = targetVelocities.getOrDefault(activeTarget, Vec3.ZERO);
        return vel.length() > 0.1;
    }

    private static boolean isTargetMovingFast() {
        if (activeTarget == null) return false;
        Vec3 vel = targetVelocities.getOrDefault(activeTarget, Vec3.ZERO);
        return vel.length() > 0.5;
    }

    // ============================================================
    // SISTEMA DE MIRA HEURÍSTICA AVANÇADA
    // ============================================================

    private static void applyHeuristicAim(Minecraft client, LivingEntity target) {
        Vec3 eyePos = client.player.getEyePosition();

        // Calcula a posição prevista do alvo com predição de movimento
        Vec3 predictedPos = predictTargetPosition(target);

        // Adiciona jitter gaussiano
        double heightOffset = 0.25 + RANDOM.nextGaussian() * 0.12 + 0.4;
        heightOffset = Math.max(0.1, Math.min(0.9, heightOffset));

        double jitterX = (RANDOM.nextGaussian()) * 0.045;
        double jitterY = (RANDOM.nextGaussian()) * 0.04;
        double jitterZ = (RANDOM.nextGaussian()) * 0.045;

        Vec3 targetPoint = new Vec3(
                predictedPos.x + jitterX,
                predictedPos.y + (target.getBbHeight() * heightOffset) + jitterY,
                predictedPos.z + jitterZ
        );

        double dx = targetPoint.x - eyePos.x;
        double dy = targetPoint.y - eyePos.y;
        double dz = targetPoint.z - eyePos.z;
        double dist = Math.sqrt(dx * dx + dz * dz);

        float rawYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
        float rawPitch = (float) -Math.toDegrees(Math.atan2(dy, dist));

        // Inicialização suave
        if (Float.isNaN(smoothYaw) || Float.isNaN(smoothPitch)) {
            smoothYaw = client.player.getYRot();
            smoothPitch = client.player.getXRot();
            previousYaw = smoothYaw;
            previousPitch = smoothPitch;
        }

        // Overshoot + correção (simula movimento humano)
        float overshootFactor = 0.08f + RANDOM.nextFloat() * 0.12f;
        float overshootYaw = (rawYaw - smoothYaw) * overshootFactor;
        float overshootPitch = (rawPitch - smoothPitch) * overshootFactor;

        // Aplica suavização com atenção adaptativa
        float attention = 0.5f + (float)(1.0 / (dist + 0.5)) * 0.4f;
        if (state == State.SMASH_ATTACK) {
            attention += 0.1f; // mais focado durante o ataque
        }

        float yawDiff = wrapAngle(rawYaw - smoothYaw);
        float pitchDiff = rawPitch - smoothPitch;

        // Limite de velocidade angular (simula sensibilidade)
        float maxTurn = 4.0f + RANDOM.nextFloat() * 3.0f;
        if (pingSimulationTicks > 0) {
            maxTurn *= 0.8f; // com ping, a mira fica mais lenta
        }

        yawDiff = Math.max(-maxTurn, Math.min(maxTurn, yawDiff * attention));
        pitchDiff = Math.max(-maxTurn * 0.65f, Math.min(maxTurn * 0.65f, pitchDiff * attention));

        // Adiciona overshoot
        yawDiff += overshootYaw * 0.4f;
        pitchDiff += overshootPitch * 0.4f;

        // Micro‑tremor (ruído térmico)
        float noiseYaw = (RANDOM.nextFloat() - 0.5f) * 0.14f;
        float noisePitch = (RANDOM.nextFloat() - 0.5f) * 0.1f;

        float finalYaw = smoothYaw + yawDiff + noiseYaw;
        float finalPitch = Math.max(-90.0f, Math.min(90.0f, smoothPitch + pitchDiff + noisePitch));

        // GCD (Golden Cookie) – simula o snapping do jogo
        Options opt = client.options;
        double sens = opt.sensitivity().get() * 0.6 + 0.2;
        double gcd = Math.pow(sens, 1.2);
        gcd = Math.max(0.04, Math.min(0.4, gcd));

        float deltaYaw = finalYaw - client.player.getYRot();
        float deltaPitch = finalPitch - client.player.getXRot();

        if (Math.abs(deltaYaw) > 0.015 || Math.abs(deltaPitch) > 0.015) {
            deltaYaw = (float) (Math.round(deltaYaw / gcd) * gcd);
            deltaPitch = (float) (Math.round(deltaPitch / gcd) * gcd);
        }

        smoothYaw = client.player.getYRot() + deltaYaw;
        smoothPitch = client.player.getXRot() + deltaPitch;

        // Aplica a rotação com suavização adicional
        client.player.setYRot(smoothYaw);
        client.player.setXRot(smoothPitch);
        client.player.yRotO = smoothYaw - (RANDOM.nextFloat() - 0.5f) * 0.7f;
        client.player.xRotO = smoothPitch - (RANDOM.nextFloat() - 0.5f) * 0.5f;
        client.player.yHeadRot = smoothYaw;
        client.player.yHeadRotO = smoothYaw;

        // Atualiza histórico
        updateHistory(smoothYaw, smoothPitch);

        previousYaw = smoothYaw;
        previousPitch = smoothPitch;
    }

    private static Vec3 predictTargetPosition(LivingEntity target) {
        Vec3 pos = target.position();
        Vec3 vel = targetVelocities.getOrDefault(target, Vec3.ZERO);

        // Fator de predição com variação
        double predFactor = 0.08 + RANDOM.nextDouble() * 0.12;

        // Se o alvo está se movendo rápido, aumenta a predição
        if (vel.length() > 0.5) {
            predFactor += 0.05;
        }

        return pos.add(vel.scale(predFactor));
    }

    private static void updateTargetVelocity(Minecraft client, LivingEntity target) {
        long now = System.currentTimeMillis();
        Vec3 currentPos = target.position();
        Vec3 previousPos = targetVelocities.getOrDefault(target, currentPos);

        long dt = lastVelocityUpdate.containsKey(target)
                ? now - lastVelocityUpdate.get(target)
                : 50;

        if (dt > 0) {
            Vec3 vel = currentPos.subtract(previousPos).scale(1000.0 / dt);
            targetVelocities.put(target, vel);
        }
        lastVelocityUpdate.put(target, now);
    }

    private static void applyMissAim(Minecraft client) {
        // Desvia a mira propositalmente (simula erro humano)
        float missYaw = (RANDOM.nextFloat() - 0.5f) * 30f;
        float missPitch = (RANDOM.nextFloat() - 0.5f) * 15f;
        client.player.setYRot(client.player.getYRot() + missYaw);
        client.player.setXRot(client.player.getXRot() + missPitch);
        client.player.yRotO = client.player.getYRot();
        client.player.xRotO = client.player.getXRot();
    }

    // ============================================================
    // SISTEMA DE SELEÇÃO DE ALVO HEURÍSTICO
    // ============================================================

    private static LivingEntity findOptimalTarget(Minecraft client) {
        AABB box = client.player.getBoundingBox().inflate(7.0, 400, 7.0);
        List<LivingEntity> entities = client.level.getEntitiesOfClass(LivingEntity.class, box, new Predicate<LivingEntity>() {
            @Override
            public boolean test(LivingEntity e) {
                return e != client.player
                        && e.isAlive()
                        && !e.isDeadOrDying()
                        && client.player.getY() > e.getY() + 0.3
                        && !e.isInvisible()
                        && e.getHealth() > 0;
            }
        });

        if (entities.isEmpty()) return null;

        // Score heurístico baseado em vários fatores
        return entities.stream()
                .max((a, b) -> {
                    double scoreA = calculateTargetScore(client, a);
                    double scoreB = calculateTargetScore(client, b);
                    return Double.compare(scoreA, scoreB);
                })
                .orElse(null);
    }

    private static double calculateTargetScore(Minecraft client, LivingEntity target) {
        double score = 100.0;

        // Prioriza alvos com menos vida
        score += (20 
