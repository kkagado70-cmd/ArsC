package com.example.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import java.util.Random;

public class XbowCart {
    public enum Stage {
        IDLE, PLACE_RAIL, PLACE_CART, LIGHT_FIRE, AIM_SOLVE, DISCHARGE, RESTORE
    }

    public static boolean enabled = false;
    private static boolean triggered = false;
    private static Stage stage = Stage.IDLE;
    private static int tickTimer = 0;
    private static BlockHitResult targetBlockHit = null;
    private static float targetYaw = 0.0f;
    private static float targetPitch = 0.0f;
    private static int originalSlot = -1;
    private static final Random RANDOM = new Random();
    private static float originalYaw = 0.0f;
    private static float originalPitch = 0.0f;

    // Constantes balísticas da flecha da besta (1.21.11)
    private static final double GRAVITY = 0.05;          // blocos/tick²
    private static final double DRAG = 0.99;             // multiplicador por tick
    private static final double ARROW_SPEED = 3.15;      // blocos/tick (velocidade inicial)

    public static void onTick(Minecraft client) {
        if (!enabled || client.player == null || client.level == null || client.gameMode == null) {
            if (stage != Stage.IDLE) reset(client, true);
            return;
        }

        // Ativação: olhando para o chão com um trilho na mão
        if (stage == Stage.IDLE) {
            ItemStack mainHand = client.player.getMainHandItem();
            boolean holdingRail = mainHand.is(Items.RAIL) || mainHand.is(Items.POWERED_RAIL) ||
                                  mainHand.is(Items.DETECTOR_RAIL) || mainHand.is(Items.ACTIVATOR_RAIL);

            if (holdingRail && client.hitResult instanceof BlockHitResult hit &&
                hit.getType() == HitResult.Type.BLOCK && hit.getDirection() == Direction.UP) {
                if (client.player.getEyePosition().distanceTo(hit.getLocation()) <= 6.0) {
                    triggered = true;
                }
            }

            if (triggered) {
                triggered = false;
                if (client.hitResult instanceof BlockHitResult hit) {
                    initiateCombo(client, hit);
                }
            }
            return;
        }

        if (tickTimer > 0) {
            tickTimer--;
            return;
        }

        processStateTransition(client);
    }

    private static boolean isRail(ItemStack s) {
        return s.is(Items.RAIL) || s.is(Items.POWERED_RAIL) ||
               s.is(Items.DETECTOR_RAIL) || s.is(Items.ACTIVATOR_RAIL);
    }

    private static int findItemSlot(Minecraft client, net.minecraft.world.item.Item item) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.is(item)) return i;
        }
        return -1;
    }

    private static int findChargedCrossbow(Minecraft client) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.is(Items.CROSSBOW) && CrossbowItem.isCharged(stack)) return i;
        }
        return -1;
    }

    private static int findCrossbow(Minecraft client) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.is(Items.CROSSBOW)) return i;
        }
        return -1;
    }

    private static int findRailSlot(Minecraft client) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (!stack.isEmpty() && isRail(stack)) return i;
        }
        return -1;
    }

    private static void initiateCombo(Minecraft client, BlockHitResult hit) {
        int railSlot = findRailSlot(client);
        int cartSlot = findItemSlot(client, Items.TNT_MINECART);
        int flintSlot = findItemSlot(client, Items.FLINT_AND_STEEL);
        int xbowSlot = findChargedCrossbow(client);

        if (railSlot == -1 || cartSlot == -1 || flintSlot == -1) return;

        if (xbowSlot == -1) {
            int crossbowSlot = findCrossbow(client);
            if (crossbowSlot != -1) {
                client.player.getInventory().setSelectedSlot(crossbowSlot);
                client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
                return;
            }
            return;
        }

        originalSlot = client.player.getInventory().getSelectedSlot();
        originalYaw = client.player.getYRot();
        originalPitch = client.player.getXRot();
        targetBlockHit = hit;
        stage = Stage.PLACE_RAIL;
        tickTimer = 1 + RANDOM.nextInt(2);
    }

    private static void processStateTransition(Minecraft client) {
        if (targetBlockHit == null) { reset(client, true); return; }

        BlockPos groundPos = targetBlockHit.getBlockPos();
        Direction clickedFace = targetBlockHit.getDirection();
        BlockPos railPos = groundPos.relative(clickedFace);

        // Torre de 2 blocos: carrinho no topo, fogo no bloco de baixo
        BlockPos towerTop = railPos.above(2);
        BlockPos firePos = towerTop.below(1);

        BlockHitResult railHit = new BlockHitResult(
            new Vec3(railPos.getX() + 0.5, railPos.getY() + 0.5, railPos.getZ() + 0.5),
            Direction.UP, railPos, false
        );

        BlockHitResult cartHit = new BlockHitResult(
            new Vec3(towerTop.getX() + 0.5, towerTop.getY() + 0.05, towerTop.getZ() + 0.5),
            Direction.UP, towerTop, false
        );

        BlockHitResult fireHit = new BlockHitResult(
            new Vec3(firePos.getX() + 0.5, firePos.getY() + 0.5, firePos.getZ() + 0.5),
            Direction.UP, firePos, false
        );

        int delay = 1 + RANDOM.nextInt(2);

        switch (stage) {
            case PLACE_RAIL -> {
                int rail = findRailSlot(client);
                if (rail != -1) {
                    client.player.getInventory().setSelectedSlot(rail);
                    client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, railHit);
                    client.player.swing(InteractionHand.MAIN_HAND);
                }
                stage = Stage.PLACE_CART;
                tickTimer = delay + RANDOM.nextInt(2);
            }
            case PLACE_CART -> {
                int cart = findItemSlot(client, Items.TNT_MINECART);
                if (cart != -1) {
                    client.player.getInventory().setSelectedSlot(cart);
                    client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, cartHit);
                    client.player.swing(InteractionHand.MAIN_HAND);
                }
                stage = Stage.LIGHT_FIRE;
                tickTimer = delay + RANDOM.nextInt(2);
            }
            case LIGHT_FIRE -> {
                int flint = findItemSlot(client, Items.FLINT_AND_STEEL);
                if (flint != -1) {
                    client.player.getInventory().setSelectedSlot(flint);
                    client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, fireHit);
                    client.player.swing(InteractionHand.MAIN_HAND);
                }
                // Calcula o ângulo exato para a flecha passar pelo fogo
                solveExactAngle(client, towerTop, firePos);
                stage = Stage.AIM_SOLVE;
                tickTimer = 1;
            }
            case AIM_SOLVE -> {
                int xbow = findChargedCrossbow(client);
                if (xbow != -1) {
                    client.player.getInventory().setSelectedSlot(xbow);
                    applySmoothAim(client, targetYaw, targetPitch);
                    stage = Stage.DISCHARGE;
                    tickTimer = 1;
                } else {
                    int crossbowSlot = findCrossbow(client);
                    if (crossbowSlot != -1) {
                        client.player.getInventory().setSelectedSlot(crossbowSlot);
                        client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
                        reset(client, true);
                    } else {
                        reset(client, true);
                    }
                }
            }
            case DISCHARGE -> {
                client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
                client.player.swing(InteractionHand.MAIN_HAND);
                stage = Stage.RESTORE;
                tickTimer = 1 + RANDOM.nextInt(2);
            }
            case RESTORE -> {
                reset(client, true);
            }
            default -> reset(client, false);
        }
    }

    // ============================================================
    // CÁLCULO BALÍSTICO EXATO (com arrasto e gravidade)
    // ============================================================
    private static void solveExactAngle(Minecraft client, BlockPos cartPos, BlockPos firePos) {
        Vec3 eyePos = client.player.getEyePosition();

        // Ponto de impacto: o TNT Minecart no topo da torre
        Vec3 target = new Vec3(
            cartPos.getX() + 0.5,
            cartPos.getY() + 0.22,
            cartPos.getZ() + 0.5
        );

        // Ponto que a flecha deve atravessar: o centro do fogo
        Vec3 fireCenter = new Vec3(
            firePos.getX() + 0.5,
            firePos.getY() + 0.5,
            firePos.getZ() + 0.5
        );

        // --- PASSO 1: Ângulo bruto para o carrinho (com balística) ---
        double[] rawAngles = solveBallisticAngle(eyePos, target, ARROW_SPEED);
        if (rawAngles == null) {
            // Fallback: mira direta no carrinho
            Vec3 dir = target.subtract(eyePos).normalize();
            targetYaw = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
            targetPitch = (float) Math.toDegrees(Math.asin(-dir.y));
            return;
        }

        double rawPitch = rawAngles[0];
        double rawYaw = rawAngles[1];

        // --- PASSO 2: Simula a trajetória com esse ângulo ---
        Vec3 simulated = simulateTrajectory(eyePos, rawYaw, rawPitch, ARROW_SPEED);

        // --- PASSO 3: Mede o desvio em relação ao fogo ---
        Vec3 toFire = fireCenter.subtract(eyePos);
        Vec3 toSim = simulated.subtract(eyePos);
        double angleError = Math.acos(
            Math.max(-1, Math.min(1, toFire.normalize().dot(toSim.normalize())))
        );

        // --- PASSO 4: Ajuste fino por busca linear (corrige o pitch) ---
        double bestPitch = rawPitch;
        double bestError = angleError;
        double pitchStep = 0.05; // 0.05 graus de precisão

        for (int i = -20; i <= 20; i++) {
            double testPitch = rawPitch + i * pitchStep;
            Vec3 testSim = simulateTrajectory(eyePos, rawYaw, testPitch, ARROW_SPEED);
            Vec3 toTest = testSim.subtract(eyePos);
            double testError = Math.acos(
                Math.max(-1, Math.min(1, toFire.normalize().dot(toTest.normalize())))
            );
            if (testError < bestError) {
                bestError = testError;
                bestPitch = testPitch;
            }
        }

        // --- PASSO 5: Ajuste fino do yaw (para centralizar no fogo) ---
        double bestYaw = rawYaw;
        double yawStep = 0.05;
        for (int i = -10; i <= 10; i++) {
            double testYaw = rawYaw + i * yawStep;
            Vec3 testSim = simulateTrajectory(eyePos, testYaw, bestPitch, ARROW_SPEED);
            Vec3 toTest = testSim.subtract(eyePos);
            double testError = Math.acos(
                Math.max(-1, Math.min(1, toFire.normalize().dot(toTest.normalize())))
            );
            if (testError < bestError) {
                bestError = testError;
                bestYaw = testYaw;
            }
        }

        // Converte para ângulos do Minecraft
        targetYaw = (float) Math.toDegrees(bestYaw);
        targetPitch = (float) Math.toDegrees(-bestPitch);

        // Pequeno ruído para humanizar
        targetYaw += (RANDOM.nextFloat() - 0.5f) * 0.3f;
        targetPitch += (RANDOM.nextFloat() - 0.5f) * 0.2f;
    }

    /**
     * Resolve o ângulo de lançamento para um projétil com gravidade e arrasto.
     * Retorna {pitch, yaw} em radianos, ou null se não houver solução.
     */
    private static double[] solveBallisticAngle(Vec3 origin, Vec3 target, double speed) {
        Vec3 delta = target.subtract(origin);
        double dx = delta.x;
        double dy = delta.y;
        double dz = delta.z;
        double distH = Math.sqrt(dx * dx + dz * dz);

        if (distH < 0.01) return null;

        double yaw = Math.atan2(-dx, dz);

        // Simplificação: ângulo de pitch para alcance horizontal com gravidade
        // Fórmula: pitch = arctan( (v² ± sqrt(v⁴ - g*(g*x² + 2*y*v²))) / (g*x) )
        // Adaptada para Minecraft com arrasto aproximado
        double g = GRAVITY;
        double v = speed;
        double x = distH;
        double y = dy;

        double v2 = v * v;
        double gx = g * x;
        double discriminant = v2 * v2 - g * (g * x * x + 2 * y * v2);

        if (discriminant < 0) {
            // Sem solução real: usa o ângulo máximo (45°)
            return new double[]{Math.PI / 4, yaw};
        }

        double sqrtDisc = Math.sqrt(discriminant);
        double pitch1 = Math.atan((v2 - sqrtDisc) / (gx));
        double pitch2 = Math.atan((v2 + sqrtDisc) / (gx));

        // Escolhe a solução com trajetória mais alta (passa pelo fogo)
        double pitch = Math.max(pitch1, pitch2);

        return new double[]{pitch, yaw};
    }

    /**
     * Simula a trajetória da flecha por 30 ticks (tempo suficiente para atingir o alvo).
     * Retorna a posição final aproximada.
     */
    private static Vec3 simulateTrajectory(Vec3 origin, double yaw, double pitch, double speed) {
        double vx = speed * Math.cos(pitch) * Math.sin(yaw);
        double vy = -speed * Math.sin(pitch);
        double vz = speed * Math.cos(pitch) * Math.cos(yaw);

        double x = origin.x;
        double y = origin.y;
        double z = origin.z;

        for (int tick = 0; tick < 40; tick++) {
            x += vx;
            y += vy;
            z += vz;

            vy -= GRAVITY;        // gravidade
            vx *= DRAG;           // arrasto
            vy *= DRAG;
            vz *= DRAG;
        }

        return new Vec3(x, y, z);
    }

    private static void applySmoothAim(Minecraft client, float yaw, float pitch) {
        float curYaw = client.player.getYRot();
        float curPitch = client.player.getXRot();

        float maxStep = 50.0f + RANDOM.nextFloat() * 10.0f;
        float steppedYaw = curYaw + Math.max(-maxStep, Math.min(maxStep, wrapAngle(yaw - curYaw)));
        float steppedPitch = curPitch + Math.max(-maxStep * 0.7f, Math.min(maxStep * 0.7f, pitch - curPitch));

        client.player.setYRot(steppedYaw);
        client.player.setXRot(Math.max(-90.0f, Math.min(90.0f, steppedPitch)));
        client.player.yRotO = steppedYaw;
        client.player.xRotO = steppedPitch;
        client.player.yHeadRot = steppedYaw;
        client.player.yHeadRotO = steppedYaw;
    }

    private static float wrapAngle(float angle) {
        float wrapped = angle % 360.0f;
        if (wrapped >= 180.0f) wrapped -= 360.0f;
        if (wrapped < -180.0f) wrapped += 360.0f;
        return wrapped;
    }

    private static void reset(Minecraft client, boolean restore) {
        if (restore && originalSlot != -1 && client.player != null) {
            client.player.getInventory().setSelectedSlot(originalSlot);
            client.player.setYRot(originalYaw);
            client.player.setXRot(originalPitch);
            client.player.yRotO = originalYaw;
            client.player.xRotO = originalPitch;
            client.player.yHeadRot = originalYaw;
            client.player.yHeadRotO = originalYaw;
        }
        stage = Stage.IDLE;
        targetBlockHit = null;
        tickTimer = 0;
        originalSlot = -1;
        triggered = false;
    }
          }
