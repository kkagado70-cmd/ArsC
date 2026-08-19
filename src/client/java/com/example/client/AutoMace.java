package net.fabricmc.example;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.Vec3;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

public class AutoMace {
    public enum Stage { IDLE, SLAM, DELAY }

    public static boolean enabled = false;
    private static Stage stage = Stage.IDLE;
    private static long lastActionTime = 0;
    private static int originalSlot = -1;
    private static boolean isSwapped = false;
    private static int humanDelayTicks = 0;
    private static LivingEntity activeTarget = null;
    private static int targetLockTicks = 0;
    private static final Random RANDOM = new Random();

    // Smoothing com ruído natural
    private static float smoothYaw = Float.NaN;
    private static float smoothPitch = Float.NaN;
    private static float lastYaw = 0;
    private static float lastPitch = 0;

    // Dados de "reações humanas"
    private static int missChance = 0;
    private static int reactionTicks = 0;

    public static void onTick(Minecraft client) {
        if (!enabled || client.player == null || client.level == null) {
            if (isSwapped) resetState(client);
            return;
        }

        // Delay humano entre ações (80-180ms)
        if (humanDelayTicks > 0) {
            humanDelayTicks--;
            return;
        }

        // Atualiza alvo com cooldown (5 ticks = 250ms)
        if (targetLockTicks <= 0 || activeTarget == null || !activeTarget.isAlive()) {
            activeTarget = findOptimalTarget(client);
            targetLockTicks = 5 + RANDOM.nextInt(8);
        } else {
            targetLockTicks--;
        }

        if (activeTarget == null) {
            resetState(client);
            return;
        }

        // Verifica se o alvo ainda está abaixo
        if (client.player.getY() <= activeTarget.getY()) {
            activeTarget = null;
            resetState(client);
            return;
        }

        boolean isFalling = client.player.fallDistance >= 2.8f + (RANDOM.nextFloat() * 0.4f) 
                            && !client.player.onGround() 
                            && !client.player.isInWater();

        if (isFalling) {
            // Aim com micro-desvios naturais
            applyHumanAim(client, activeTarget);

            // Distância com pequena variação (2.9-3.1)
            double hitDist = 2.9 + (RANDOM.nextDouble() * 0.2);
            if (client.player.distanceTo(activeTarget) <= hitDist) {
                // Verifica ataque com cooldown real do Minecraft
                float strength = client.player.getAttackStrengthScale(0.0f);
                if (strength < 0.85f) return;

                // Chance de "errar" proposital (2-5%)
                if (RANDOM.nextInt(100) < 3) {
                    missChance = 2;
                }

                if (missChance > 0) {
                    missChance--;
                    // Ataque com angulação errada
                    client.player.setYRot(client.player.getYRot() + (RANDOM.nextFloat() - 0.5f) * 15f);
                    return;
                }

                // Shield break com atraso humano
                boolean isShielding = activeTarget instanceof Player p 
                        && p.isUsingItem() 
                        && p.getUseItem().getItem() instanceof ShieldItem;

                if (isShielding && stage == Stage.IDLE) {
                    int axeSlot = findAxeSlot(client);
                    if (axeSlot != -1) {
                        if (originalSlot == -1) originalSlot = client.player.getInventory().getSelectedSlot();
                        client.player.getInventory().setSelectedSlot(axeSlot);
                        client.gameMode.attack(client.player, activeTarget);
                        client.player.swing(InteractionHand.MAIN_HAND);
                        isSwapped = true;
                        stage = Stage.SLAM;
                        // Delay realístico de troca (3-6 ticks)
                        humanDelayTicks = 3 + RANDOM.nextInt(4);
                        lastActionTime = System.currentTimeMillis();
                        return;
                    }
                }

                // Ataque com mace
                if (stage == Stage.SLAM || stage == Stage.IDLE) {
                    boolean preferDensity = client.player.fallDistance > 6.5 + (RANDOM.nextFloat() * 1.5f);
                    int maceSlot = findBestMaceSlot(client, preferDensity);
                    if (maceSlot != -1) {
                        if (originalSlot == -1) originalSlot = client.player.getInventory().getSelectedSlot();
                        client.player.getInventory().setSelectedSlot(maceSlot);
                        isSwapped = true;
                    }

                    client.gameMode.attack(client.player, activeTarget);
                    client.player.swing(InteractionHand.MAIN_HAND);
                    lastActionTime = System.currentTimeMillis();

                    // Delay pós-ataque (200-400ms)
                    humanDelayTicks = 4 + RANDOM.nextInt(8);
                    resetState(client);
                }
            }
        } else if (client.player.onGround()) {
            // Reseta com pequeno delay (simula distração)
            if (stage != Stage.IDLE) {
                humanDelayTicks = 2 + RANDOM.nextInt(5);
                resetState(client);
            }
        }
    }

    private static LivingEntity findOptimalTarget(Minecraft client) {
        var entities = client.level.getEntitiesOfClass(
            LivingEntity.class,
            client.player.getBoundingBox().inflate(6.5, 400, 6.5),
            e -> e != client.player && e.isAlive() && !e.isDeadOrDying() 
                 && client.player.getY() > e.getY() + 0.5
        );

        // Prioriza alvos com menos vida ou mais fracos
        return entities.stream()
            .min((a, b) -> {
                double scoreA = a.getHealth() - (a instanceof Player ? 10 : 0);
                double scoreB = b.getHealth() - (b instanceof Player ? 10 : 0);
                int distCompare = Double.compare(
                    client.player.distanceToSqr(a),
                    client.player.distanceToSqr(b)
                );
                if (Math.abs(scoreA - scoreB) > 5) {
                    return Double.compare(scoreA, scoreB);
                }
                return distCompare;
            })
            .orElse(null);
    }

    private static void applyHumanAim(Minecraft client, LivingEntity target) {
        Vec3 eyePos = client.player.getEyePosition();
        Random rand = ThreadLocalRandom.current();

        // Ponto de mira com variação natural (peito, cabeça, ombros)
        double heightOffset = 0.3 + rand.nextDouble() * 0.5;
        double jitterX = (rand.nextDouble() - 0.5) * 0.06;
        double jitterY = (rand.nextDouble() - 0.5) * 0.05;
        double jitterZ = (rand.nextDouble() - 0.5) * 0.06;

        // Variação de mira baseada no movimento do alvo
        Vec3 targetVel = new Vec3(
            target.getX() - target.xOld,
            target.getY() - target.yOld,
            target.getZ() - target.zOld
        );
        Vec3 predictedPos = target.position().add(targetVel.scale(0.15));

        Vec3 targetPoint = new Vec3(
            predictedPos.x + jitterX,
            predictedPos.y + (target.getBbHeight() * heightOffset) + jitterY,
            predictedPos.z + jitterZ
        );

        double dx = targetPoint.x - eyePos.x;
        double dy = targetPoint.y - eyePos.y;
        double dz = targetPoint.z - eyePos.z;
        double dist = Math.sqrt(dx * dx + dz * dz);

        float targetYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
        float targetPitch = (float) -Math.toDegrees(Math.atan2(dy, dist));

        // Inicialização suave
        if (Float.isNaN(smoothYaw) || Float.isNaN(smoothPitch)) {
            smoothYaw = client.player.getYRot();
            smoothPitch = client.player.getXRot();
            lastYaw = smoothYaw;
            lastPitch = smoothPitch;
        }

        // Fator de suavização baseado na distância e "atenção"
        float attention = 0.6f + (float)(1.0 / (dist + 0.5)) * 0.3f;
        float yawDiff = wrapAngle(targetYaw - smoothYaw);
        float pitchDiff = targetPitch - smoothPitch;

        // Limite de velocidade angular realístico (120-180 graus/segundo)
        float maxTurn = 5.0f + (rand.nextFloat() * 3.0f);
        yawDiff = Math.max(-maxTurn, Math.min(maxTurn, yawDiff * attention));
        pitchDiff = Math.max(-maxTurn * 0.6f, Math.min(maxTurn * 0.6f, pitchDiff * attention));

        // Aplica com ruído natural (micro-tremor)
        float noiseYaw = (rand.nextFloat() - 0.5f) * 0.08f;
        float noisePitch = (rand.nextFloat() - 0.5f) * 0.06f;

        float finalYaw = smoothYaw + yawDiff + noiseYaw;
        float finalPitch = Math.max(-90.0f, Math.min(90.0f, smoothPitch + pitchDiff + noisePitch));

        // GCD (Golden Cookie) com sensibilidade dinâmica
        double sens = client.options.sensitivity().get() * 0.6 + 0.2;
        double gcd = sens * sens * sens * 1.2;
        gcd = Math.max(0.05, Math.min(0.4, gcd)); // Limita para evitar snap

        float deltaYaw = finalYaw - client.player.getYRot();
        float deltaPitch = finalPitch - client.player.getXRot();

        // Aplica GCD apenas se movimento > threshold (evita micro-stutter)
        if (Math.abs(deltaYaw) > 0.01 || Math.abs(deltaPitch) > 0.01) {
            deltaYaw = (float) (Math.round(deltaYaw / gcd) * gcd);
            deltaPitch = (float) (Math.round(deltaPitch / gcd) * gcd);
        }

        smoothYaw = client.player.getYRot() + deltaYaw;
        smoothPitch = client.player.getXRot() + deltaPitch;

        // Atualiza rotação com suavização
        client.player.setYRot(smoothYaw);
        client.player.setXRot(smoothPitch);
        client.player.yRotO = smoothYaw - (rand.nextFloat() - 0.5f) * 0.5f;
        client.player.xRotO = smoothPitch - (rand.nextFloat() - 0.5f) * 0.3f;
        client.player.yHeadRot = smoothYaw;

        lastYaw = smoothYaw;
        lastPitch = smoothPitch;
    }

    private static float wrapAngle(float angle) {
        float wrapped = angle % 360.0f;
        if (wrapped >= 180.0f) wrapped -= 360.0f;
        if (wrapped < -180.0f) wrapped += 360.0f;
        return wrapped;
    }

    private static int findAxeSlot(Minecraft client) {
        // Procura machado com prioridade para os melhores
        int bestSlot = -1;
        int bestLevel = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (stack.getItem() instanceof AxeItem) {
                int level = stack.getEnchantmentLevel(Enchantments.SHARPNESS);
                if (level > bestLevel) {
                    bestLevel = level;
                    bestSlot = i;
                }
            }
        }
        return bestSlot;
    }

    private static int findBestMaceSlot(Minecraft client, boolean preferDensity) {
        int bestSlot = -1;
        int maxLevel = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (stack.isEmpty() || !stack.is(Items.MACE)) continue;

            var registry = client.level.registryAccess();
            var enchant = preferDensity ? registry.get(Enchantments.DENSITY) : registry.get(Enchantments.BREACH);
            int level = 0;
            if (enchant.isPresent()) {
                level = EnchantmentHelper.getItemEnchantmentLevel(enchant.get(), stack);
            }
            // Prioriza encantamentos, mas aceita mace sem encantamento com peso menor
            int weight = level * 10 + (stack.getEnchantmentLevel(Enchantments.SHARPNESS) * 2);
            if (weight > maxLevel) {
                maxLevel = weight;
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    private static void resetState(Minecraft client) {
        if (isSwapped && originalSlot != -1 && client.player != null) {
            // Delay antes de trocar de volta (simula hesitação)
            if (System.currentTimeMillis() - lastActionTime > 150) {
                client.player.getInventory().setSelectedSlot(originalSlot);
            }
        }
        originalSlot = -1;
        isSwapped = false;
        stage = Stage.IDLE;
        // Mantém smoothing para evitar snap
        if (Float.isNaN(smoothYaw)) {
            smoothYaw = client.player != null ? client.player.getYRot() : 0;
            smoothPitch = client.player != null ? client.player.getXRot() : 0;
        }
    }
                      }
