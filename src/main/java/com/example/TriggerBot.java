package com.example;

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

public class TriggerBot extends ClientBase.Module {
    public static final String FILE_NAME = "TriggerBot.java";
    public static boolean enabled = true;
    public static boolean consistentCritsEnabled = true;
    private static final Random internalRandom = new Random();

    private static int attackReleaseTracker = 0;
    private static int reactionCountdownTicks = 0;
    private static int comboBufferTicks = 0;
    private static final double MAX_MELEE_REACH_SQR = 9.0D;

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
        return stack.getItem() instanceof SwordItem || stack.getItem() instanceof AxeItem || stack.getItem() instanceof TridentItem || name.contains("sword") || name.contains("axe") || name.contains("trident") || name.contains("mace");
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
    }
}