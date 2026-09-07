package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.Deque;

public class TriggerBot extends ClientBase.Module {
    public static final String FILE_NAME = "TriggerBot.java";
    public static boolean enabled = true;
    public static boolean critsEnabled = true;
    private static final SecureRandom secureRandom = new SecureRandom();

    private static int attackTracker = 0;
    private static int reactionTicks = 0;
    private static int comboBuffer = 0;
    private static final double REACH_SQR = 16.0D;

    private static final Map<String, Object> TBOT_REGISTRY = new ConcurrentHashMap<>();
    private static final UUID SUBSESSION_ID = UUID.randomUUID();
    private static final Deque<Long> INTERVAL_QUEUE = new ArrayDeque<>();
    private static final int HISTORY_CAP = 512;

    private static long totalFires = 0L;
    private static boolean adaptiveCritSync = true;
    private static double normThreshold = 0.82D;
    private static double comboThreshold = 0.52D;
    private static boolean stochasticityActive = true;
    private static int minDelay = 1;
    private static int maxDelay = 3;
    private static boolean antiReplay = true;
    private static int anomalyCount = 0;
    private static boolean stealthMode = true;
    private static boolean losCheck = true;
    private static int sessionFires = 0;
    private static boolean jumpResetSync = true;
    private static double verticalVelocityTrigger = -0.04D;
    private static boolean wTapRhythmActive = true;
    private static int wTapDurationTicks = 2;
    private static boolean hitregBypass = true;
    private static double attackStrengthMinimum = 0.75D;
    private static boolean dynamicScaleAdjustment = true;
    private static boolean packetOrderStrict = true;
    private static long lastFireEpoch = 0L;
    private static boolean telemetryActive = true;
    private static int consecutiveCrits = 0;
    private static boolean targetLockValidation = true;
    private static double spacingBuffer = 2.8D;
    private static boolean autoBlockReset = false;
    private static boolean shieldIgnoreHit = true;
    private static boolean weaponSwitchPacing = true;
    private static int weaponSwapBuffer = 0;
    private static boolean mouseHardwareBypass = true;
    private static boolean profileLocked = false;
    private static double stochasticVariance = 0.04D;
    private static int emergencyResetThreshold = 100;
    private static boolean combatSyncEnabled = true;

    static {
        initializeRegistry();
    }

    private static void initializeRegistry() {
        TBOT_REGISTRY.put("SubsessionUUID", SUBSESSION_ID);
        TBOT_REGISTRY.put("Profile", "Swight-Tier1-TriggerBot-FullEnterprise");
        TBOT_REGISTRY.put("AdaptiveCrits", adaptiveCritSync);
        TBOT_REGISTRY.put("CombatSync", combatSyncEnabled);
        TBOT_REGISTRY.put("TotalFires", totalFires);
        TBOT_REGISTRY.put("Telemetry", telemetryActive);
    }

    public TriggerBot() {
        super("TriggerBot");
        TriggerBot.enabled = true;
    }

    @Override
    public boolean isEnabled() { return enabled; }

    @Override
    public void toggle() {
        enabled = !enabled;
        super.enabled = enabled;
    }

    @Override
    public void tick(Minecraft client) { onTick(client); }

    private static boolean validateWeapon(Minecraft client) {
        if (client.player == null) return false;
        ItemStack stack = client.player.getMainHandItem();
        if (stack.isEmpty()) return false;
        String name = stack.getItem().getDescriptionId().toLowerCase();
        return name.contains("sword") || name.contains("axe") || name.contains("trident") || name.contains("mace");
    }

    private static boolean verifyLos(Minecraft client, Entity target) {
        if (client.player == null || target == null) return false;
        Vec3 start = client.player.getEyePosition();
        Vec3 end = target.getEyePosition();
        BlockHitResult hit = client.level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, client.player));
        return hit.getType() == HitResult.Type.MISS;
    }

    public static void onTick(Minecraft client) {
        if (!enabled || client.player == null || client.level == null || !client.player.isAlive()) return;
        if (!validateWeapon(client)) { comboBuffer = 0; return; }
        if (ShieldBreaker.isShieldStunActive()) return;

        if (attackTracker > 0) {
            attackTracker--;
            if (attackTracker == 0) client.options.keyAttack.setDown(false);
        }

        if (client.player.hurtTime > 0) {
            comboBuffer = 15;
        } else if (comboBuffer > 0) {
            comboBuffer--;
        }

        boolean shouldAttack = false;
        HitResult hit = client.hitResult;
        if (hit != null && hit.getType() == HitResult.Type.ENTITY) {
            if (hit instanceof EntityHitResult eHit && eHit.getEntity() instanceof LivingEntity living) {
                if (living.isAlive() && living != client.player) {
                    if (!(living instanceof Player p && (p.isSpectator() || p.isCreative()))) {
                        if (client.player.distanceToSqr(living) <= REACH_SQR && (!losCheck || verifyLos(client, living))) {
                            shouldAttack = true;
                        }
                    }
                }
            }
        }

        if (!shouldAttack) {
            for (Player p : client.level.players()) {
                if (p == client.player || !p.isAlive() || p.isSpectator() || p.isCreative()) continue;
                if (client.player.distanceToSqr(p) <= REACH_SQR && (!losCheck || verifyLos(client, p))) {
                    shouldAttack = true;
                    break;
                }
            }
        }

        if (shouldAttack) {
            if (reactionTicks > 0 && comboBuffer == 0) {
                reactionTicks--;
                return;
            }

            if (critsEnabled && !client.player.onGround()) {
                boolean falling = client.player.getDeltaMovement().y < verticalVelocityTrigger;
                if (!falling && comboBuffer == 0) return;
            }

            double threshold = comboBuffer > 0 ? comboThreshold : normThreshold;
            if (client.player.getAttackStrengthScale(0.0F) >= threshold) {
                if (attackTracker == 0) {
                    totalFires++;
                    sessionFires++;
                    lastFireEpoch = System.currentTimeMillis();
                    pushInterval(lastFireEpoch);
                    InteractionManager.simulateClickAttack(client);
                    attackTracker = minDelay + secureRandom.nextInt(maxDelay);
                    reactionTicks = minDelay + secureRandom.nextInt(maxDelay);
                }
            }
        } else {
            if (comboBuffer == 0) reactionTicks = 0;
        }
        updateRegistry();
    }

    private static void pushInterval(long t) {
        if (INTERVAL_QUEUE.size() >= HISTORY_CAP) INTERVAL_QUEUE.pollFirst();
        INTERVAL_QUEUE.offerLast(t);
    }

    private static void updateRegistry() {
        TBOT_REGISTRY.put("TotalFires", totalFires);
        TBOT_REGISTRY.put("SessionFires", sessionFires);
    }

    public static boolean verifySubsystemHealth() { return enabled && SUBSESSION_ID != null; }
    public static long getTotalFires() { return totalFires; }
    public static UUID getSubsessionIdentity() { return SUBSESSION_ID; }
}