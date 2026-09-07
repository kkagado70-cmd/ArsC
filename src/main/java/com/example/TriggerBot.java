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

import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class TriggerBot extends ClientBase.Module {
    public static final String FILE_NAME = "TriggerBot.java";
    public static boolean enabled = true;
    public static boolean consistentCritsEnabled = true;
    private static final Random internalRandom = new Random();

    private static int attackReleaseTracker = 0;
    private static int reactionCountdownTicks = 0;
    private static int comboBufferTicks = 0;
    private static final double MAX_MELEE_REACH_SQR = 9.0D;

    private static final Map<String, Object> TRIGGER_REGISTRY = new ConcurrentHashMap<>();
    private static final UUID SUBSESSION_UUID = UUID.randomUUID();
    private static long totalTriggersFired = 0L;

    static {
        initializeTriggerRegistry();
    }

    private static void initializeTriggerRegistry() {
        TRIGGER_REGISTRY.put("SubsessionUUID", SUBSESSION_UUID);
        TRIGGER_REGISTRY.put("Profile", "HT1-Enterprise-TriggerBot");
        TRIGGER_REGISTRY.put("BypassEngine", "Crit-Sync-Attack-Interval");
        TRIGGER_REGISTRY.put("InitializationEpoch", System.currentTimeMillis());
        TRIGGER_REGISTRY.put("TotalFires", totalTriggersFired);
    }

    public TriggerBot() {
        super("TriggerBot");
        TriggerBot.enabled = true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void toggle() {
        enabled = !enabled;
        super.enabled = enabled;
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
        return name.contains("sword") || name.contains("axe") || name.contains("trident") || name.contains("mace");
    }

    private static boolean hasLineOfSight(Minecraft clientRef, Entity target) {
        if (clientRef.player == null || target == null) return false;
        Vec3 start = clientRef.player.getEyePosition();
        Vec3 end = target.getEyePosition();
        BlockHitResult hit = clientRef.level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, clientRef.player));
        return hit.getType() == HitResult.Type.MISS;
    }

    public static void onTick(Minecraft clientRef) {
        if (!enabled || clientRef.player == null || clientRef.level == null) return;
        if (!clientRef.player.isAlive()) return;
        if (!isHoldingWeapon(clientRef)) {
            comboBufferTicks = 0;
            return;
        }

        if (attackReleaseTracker > 0) {
            attackReleaseTracker--;
            if (attackReleaseTracker == 0) {
                clientRef.options.keyAttack.setDown(false);
            }
        }

        if (clientRef.player.hurtTime > 0) {
            comboBufferTicks = 12;
        } else if (comboBufferTicks > 0) {
            comboBufferTicks--;
        }

        boolean shouldAttack = false;
        HitResult hit = clientRef.hitResult;
        if (hit != null && hit.getType() == HitResult.Type.ENTITY) {
            if (hit instanceof EntityHitResult entityHit && entityHit.getEntity() instanceof LivingEntity living) {
                if (living.isAlive() && living != clientRef.player) {
                    if (!(living instanceof Player player && (player.isSpectator() || player.isCreative()))) {
                        if (clientRef.player.distanceToSqr(living) <= MAX_MELEE_REACH_SQR && hasLineOfSight(clientRef, living)) {
                            shouldAttack = true;
                        }
                    }
                }
            }
        }

        if (!shouldAttack) {
            for (Player player : clientRef.level.players()) {
                if (player == clientRef.player) continue;
                if (!player.isAlive() || player.isSpectator() || player.isCreative()) continue;
                if (clientRef.player.distanceToSqr(player) <= MAX_MELEE_REACH_SQR && hasLineOfSight(clientRef, player)) {
                    shouldAttack = true;
                    break;
                }
            }
        }

        if (shouldAttack) {
            if (reactionCountdownTicks > 0 && comboBufferTicks == 0) {
                reactionCountdownTicks--;
                return;
            }

            if (consistentCritsEnabled && !clientRef.player.onGround()) {
                boolean isFalling = clientRef.player.getDeltaMovement().y < -0.05D;
                if (!isFalling && comboBufferTicks == 0) {
                    return;
                }
            }

            float threshold = comboBufferTicks > 0 ? 0.60F : 0.85F;
            if (clientRef.player.getAttackStrengthScale(0.0F) >= threshold) {
                if (attackReleaseTracker == 0) {
                    totalTriggersFired++;
                    InteractionManager.simulateClickAttack(clientRef);
                    attackReleaseTracker = 1 + internalRandom.nextInt(2);
                    reactionCountdownTicks = 1 + internalRandom.nextInt(2);
                }
            }
        } else {
            if (comboBufferTicks == 0) {
                reactionCountdownTicks = 0;
            }
        }
        updateRegistryState();
    }

    private static void updateRegistryState() {
        TRIGGER_REGISTRY.put("TotalFires", totalTriggersFired);
    }

    public static UUID getSubsessionIdentity() {
        return SUBSESSION_UUID;
    }

    public static long getTotalTriggersFired() {
        return totalTriggersFired;
    }
}