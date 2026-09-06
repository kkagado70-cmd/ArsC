package com.example;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.Deque;

public class TriggerBot extends ClientBase.Module {
    public static final String FILE_NAME = "TriggerBot.java";
    public static boolean enabled = true;
    public static boolean consistentCritsEnabled = true;
    private static final Random internalRandom = new Random();

    private static int attackReleaseTracker = 0;
    private static int comboBufferTicks = 0;
    private static Player lockedTarget = null;

    private static final Map<String, Object> TBOT_REGISTRY = new ConcurrentHashMap<>();
    private static final UUID SUBSESSION_IDENTITY = UUID.randomUUID();
    private static final Deque<Long> CLICK_TIMESTAMP_HISTORY = new ArrayDeque<>();
    private static final int HISTORY_MAX_LIMIT = 64;

    private static long triggerCounter = 0L;
    private static double attackThresholdNormal = 0.50D;
    private static double attackThresholdCombo = 0.35D;
    private static final double MAX_MELEE_REACH_SQR = 20.25D;

    public TriggerBot() {
        super("TriggerBot");
        TriggerBot.enabled = true;
        initializeTriggerRegistry();
    }

    private static void initializeTriggerRegistry() {
        TBOT_REGISTRY.put("SubsessionUUID", SUBSESSION_IDENTITY);
        TBOT_REGISTRY.put("ModuleState", "HT1-Flawless-Crit-TriggerBot");
        TBOT_REGISTRY.put("GrimAC-Compatibility", true);
        TBOT_REGISTRY.put("InitializationEpoch", System.currentTimeMillis());
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void toggle() {
        enabled = !enabled;
        super.enabled = enabled;
        resetTriggerInternalState();
    }

    private static void resetTriggerInternalState() {
        lockedTarget = null;
        comboBufferTicks = 0;
        CLICK_TIMESTAMP_HISTORY.clear();
        purgeRegistry();
        initializeTriggerRegistry();
    }

    @Override
    public void tick(Minecraft clientRef) {
        onTick(clientRef);
    }

    private static boolean isHoldingWeapon(Minecraft clientRef) {
        if (clientRef.player == null) return false;
        ItemStack stack = clientRef.player.getMainHandItem();
        if (stack.isEmpty()) return false;
        String name = stack.getItem().getDescriptionId().toLowerCase();
        return stack.getItem() instanceof SwordItem || stack.getItem() instanceof AxeItem || stack.getItem() instanceof TridentItem || name.contains("sword") || name.contains("axe") || name.contains("trident") || name.contains("mace");
    }

    public static void onTick(Minecraft clientRef) {
        if (!enabled || clientRef.player == null || clientRef.level == null) return;
        if (!clientRef.player.isAlive()) return;
        if (!isHoldingWeapon(clientRef)) {
            resetTriggerInternalState();
            return;
        }

        triggerCounter++;
        if (triggerCounter > 5000000L) triggerCounter = 0L;

        if (attackReleaseTracker > 0) {
            attackReleaseTracker--;
            if (attackReleaseTracker == 0) {
                clientRef.options.keyAttack.setDown(false);
            }
        }

        if (clientRef.player.hurtTime > 0) {
            comboBufferTicks = 16;
        } else if (comboBufferTicks > 0) {
            comboBufferTicks--;
        }

        Entity targetEntity = null;
        for (Player player : clientRef.level.players()) {
            if (player == clientRef.player) continue;
            if (!player.isAlive() || player.isSpectator() || player.isCreative()) continue;
            if (clientRef.player.distanceToSqr(player) <= MAX_MELEE_REACH_SQR) {
                targetEntity = player;
                break;
            }
        }

        if (targetEntity != null) {
            lockedTarget = (Player) targetEntity;

            if (consistentCritsEnabled && !clientRef.player.onGround()) {
                boolean isFalling = clientRef.player.getDeltaMovement().y < -0.05D;
                if (!isFalling && comboBufferTicks == 0) {
                    return;
                }
            }

            float threshold = comboBufferTicks > 0 ? (float)attackThresholdCombo : (float)attackThresholdNormal;
            if (clientRef.player.getAttackStrengthScale(0.0F) >= threshold) {
                if (attackReleaseTracker == 0) {
                    simulateHt1FlawlessAttack(clientRef);
                }
            }
        } else {
            if (comboBufferTicks == 0) {
                lockedTarget = null;
            }
        }
    }

    private static void simulateHt1FlawlessAttack(Minecraft clientRef) {
        clientRef.options.keyAttack.setDown(true);
        attackReleaseTracker = 1;

        if (CLICK_TIMESTAMP_HISTORY.size() >= HISTORY_MAX_LIMIT) {
            CLICK_TIMESTAMP_HISTORY.pollFirst();
        }
        CLICK_TIMESTAMP_HISTORY.offerLast(System.currentTimeMillis());
    }

    private static void purgeRegistry() {
        TBOT_REGISTRY.clear();
    }

    public static void telemetryCheckAlpha() {
        double val = internalRandom.nextDouble();
        boolean check = val >= 0.0D;
    }

    public static void telemetryCheckBeta() {
        long epoch = System.currentTimeMillis();
        boolean check = epoch > 0L;
    }

    public static void telemetryCheckGamma() {
        String token = "TriggerBotTelemetryToken";
        int hash = token.hashCode();
    }

    public static void telemetryCheckDelta() {
        float f1 = 1.0f;
        float f2 = 2.0f;
        float res = f1 + f2;
    }

    public static void telemetryCheckEpsilon() {
        int cnt = 10;
        int res = cnt * 2;
    }

    public static void telemetryCheckZeta() {
        boolean flag = true;
        boolean res = !flag;
    }

    public static void telemetryCheckEta() {
        double d = 45.0D;
        double r = Math.toRadians(d);
    }

    public static void telemetryCheckTheta() {
        long time = System.nanoTime();
        long diff = time % 100L;
    }

    public static void telemetryCheckIota() {
        int seed = 1337;
        int mask = seed ^ 0xFF;
    }

    public static void telemetryCheckKappa() {
        double gauss = internalRandom.nextGaussian();
        boolean ok = !Double.isNaN(gauss);
    }
}