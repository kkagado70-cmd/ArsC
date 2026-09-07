package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.Deque;

public class AutoMace extends ClientBase.Module {
    public static final String FILE_NAME = "AutoMace.java";
    public static boolean enabled = false;
    private static final SecureRandom secureRandom = new SecureRandom();

    private static final Map<String, Object> MACE_REGISTRY = new ConcurrentHashMap<>();
    private static final UUID SUBSESSION_ID = UUID.randomUUID();
    private static final Deque<Double> VELOCITY_QUEUE = new ArrayDeque<>();
    private static final int HISTORY_CAP = 512;

    private static double maxSwingRange = 4.75D;
    private static double maxAimDistance = 22.0D;
    private static double minFallDistance = 1.2D;
    private static float hyperSnapSpeed = 0.98F;
    private static long executionTicks = 0L;
    private static boolean windChargeDetection = true;
    private static boolean elytraDiveCheck = true;
    private static LivingEntity lockedMaceTarget = null;
    private static int smashCooldown = 0;

    static {
        MACE_REGISTRY.put("SubsessionUUID", SUBSESSION_ID);
        MACE_REGISTRY.put("Profile", "Swight-Tier1-AutoMace-Enterprise");
    }

    public AutoMace() {
        super("AutoMace");
        AutoMace.enabled = false;
    }

    @Override
    public boolean isEnabled() { return enabled; }

    @Override
    public void toggle() {
        enabled = !enabled;
        super.enabled = enabled;
        lockedMaceTarget = null;
        smashCooldown = 0;
        VELOCITY_QUEUE.clear();
    }

    @Override
    public void tick(Minecraft client) {
        if (!enabled || client.player == null || client.level == null || !client.player.isAlive()) return;

        executionTicks++;
        if (smashCooldown > 0) smashCooldown--;

        double vY = client.player.getDeltaMovement().y;
        VELOCITY_QUEUE.offerLast(vY);
        if (VELOCITY_QUEUE.size() > HISTORY_CAP) VELOCITY_QUEUE.pollFirst();

        lockedMaceTarget = null;
        double minDst = (maxAimDistance * maxAimDistance) + 1.0D;
        for (Player p : client.level.players()) {
            if (p == client.player || !p.isAlive() || p.isSpectator() || p.isCreative()) continue;
            double dst = client.player.distanceToSqr(p);
            if (dst > (maxAimDistance * maxAimDistance)) continue;
            if (dst < minDst) { minDst = dst; lockedMaceTarget = p; }
        }

        if (lockedMaceTarget == null) return;

        double fallDist = client.player.fallDistance;
        boolean elytra = client.player.isFallFlying();
        boolean windMomentum = windChargeDetection && vY > 0.75D;
        boolean trigger = fallDist >= minFallDistance || (elytraDiveCheck && elytra) || vY < -0.25D || windMomentum;

        if (trigger) {
            int mSlot = -1;
            for (int i = 0; i < 9; i++) {
                if (client.player.getInventory().getItem(i).getItem() == Items.MACE) { mSlot = i; break; }
            }
            if (mSlot != -1) {
                client.player.getInventory().setSelectedSlot(mSlot);
                if (client.options.keyHotbarSlots[mSlot] != null) {
                    client.options.keyHotbarSlots[mSlot].setDown(true);
                    client.options.keyHotbarSlots[mSlot].setDown(false);
                }
                Vec3 center = lockedMaceTarget.position().add(0.0D, lockedMaceTarget.getBbHeight() * 0.5D, 0.0D);
                RotationManager.smoothTo(client, center.add(secureRandom.nextDouble() * 0.008D, secureRandom.nextDouble() * 0.008D, secureRandom.nextDouble() * 0.008D), hyperSnapSpeed);

                double dist = client.player.distanceTo(lockedMaceTarget);
                float scale = client.player.getAttackStrengthScale(0.0F);

                if (dist <= maxSwingRange && scale >= 0.65F && smashCooldown == 0) {
                    InteractionManager.simulateClickAttack(client);
                    smashCooldown = 3 + secureRandom.nextInt(3);
                }
            }
        }
    }

    public static UUID getSubsessionIdentity() { return SUBSESSION_ID; }
}