package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.vehicle.TntMinecartEntity;   // ← Yarn: TntMinecartEntity
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Box;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import java.util.Comparator;
import java.util.Random;
import java.util.UUID;

public class XbowCart {

    public static boolean enabled = false;

    private enum Phase {
        IDLE,
        SEL_RAIL,  AIM_RAIL,  PLACE_RAIL, WAIT_RAIL,
        SEL_CART,  AIM_CART,  PLACE_CART, WAIT_CART,
        SEL_FIRE,  AIM_FIRE,  IGNITE,     WAIT_FIRE,
        SEL_XBOW,  AIM_XBOW,  SHOOT,      COOLDOWN
    }

    private static final Random RNG = new Random();
    private static final UUID SESSION_ID = UUID.randomUUID();

    private static Phase    phase     = Phase.IDLE;
    private static int      timer     = 0;
    private static int      aimTick   = 0;
    private static int      waitRetry = 0;
    private static int      savedSlot = -1;
    private static BlockPos railPos   = null;
    private static BlockPos firePos   = null;
    private static Vec3d    railAim   = null;
    private static Vec3d    cartAim   = null;
    private static Vec3d    fireAim   = null;
    private static Vec3d    xbowAim   = null;
    private static TntMinecartEntity cart = null;   // ← Yarn

    private static final float PYT  = 3.5f;
    private static final float PPT  = 4.0f;
    private static final float SYT  = 4.5f;
    private static final float SPT  = 5.0f;
    private static final int   AMAX = 10;
    private static final double CR  = 3.2;
    private static final double DIST_MAX = 4.5;

    public static void register() { /* no-op: ClientBase.ModuleManager handles tick */ }

    public static void onTick(Minecraft mc) {
        if (!enabled || mc.player == null || mc.world == null) return;
        if (mc.currentScreen != null) return;

        RotationManager.samplePlayerGcd(mc);

        switch (phase) {
            case IDLE -> tickIdle(mc);

            case SEL_RAIL -> {
                int slot = InventoryManager.findRail(mc);
                if (slot < 0) { hardReset(mc); return; }
                sel(mc, slot); timer = 2; go(Phase.AIM_RAIL);
            }
            case AIM_RAIL -> tickFlick(mc, railAim, PYT, PPT, Phase.PLACE_RAIL);
            case PLACE_RAIL -> {
                if (--timer > 0) return;
                InteractionManager.simulateClickUse(mc, InteractionManager.InteractionPriority.HIGH);
                timer = 2; go(Phase.WAIT_RAIL);
            }
            case WAIT_RAIL -> tickWait(mc, Phase.SEL_CART, () -> isRailAt(mc, railPos));

            case SEL_CART -> {
                int slot = InventoryManager.findItem(mc, Items.TNT_MINECART);
                if (slot < 0) { hardReset(mc); return; }
                sel(mc, slot); timer = 2; go(Phase.AIM_CART);
            }
            case AIM_CART -> tickFlick(mc, cartAim, PYT, PPT, Phase.PLACE_CART);
            case PLACE_CART -> {
                if (--timer > 0) return;
                InteractionManager.simulateClickUse(mc, InteractionManager.InteractionPriority.HIGH);
                timer = 4; go(Phase.WAIT_CART);
            }
            case WAIT_CART -> tickWaitCart(mc);

            case SEL_FIRE -> {
                int slot = InventoryManager.findFireSource(mc);
                if (slot < 0) { hardReset(mc); return; }
                sel(mc, slot); timer = 2; go(Phase.AIM_FIRE);
            }
            case AIM_FIRE  -> tickFlick(mc, fireAim, PYT, PPT, Phase.IGNITE);
            case IGNITE -> {
                if (--timer > 0) return;
                InteractionManager.simulateClickUse(mc, InteractionManager.InteractionPriority.HIGH);
                timer = 2; go(Phase.WAIT_FIRE);
            }
            case WAIT_FIRE -> tickWait(mc, Phase.SEL_XBOW, () -> isFireAt(mc, firePos));

            case SEL_XBOW -> {
                int slot = InventoryManager.findChargedCrossbow(mc);
                if (slot < 0) { hardReset(mc); return; }
                sel(mc, slot); timer = 2; go(Phase.AIM_XBOW);
            }
            case AIM_XBOW -> tickFlick(mc, liveAim(mc), SYT, SPT, Phase.SHOOT);
            case SHOOT    -> tickShoot(mc);
            case COOLDOWN -> { if (--timer <= 0) hardReset(mc); }
        }
    }

    private static void tickIdle(Minecraft mc) {
        if (!mc.options.attackKey.isPressed()) return;
        if (mc.crosshairTarget == null || mc.crosshairTarget.getType() != HitResult.Type.BLOCK) return;

        BlockHitResult bhr = (BlockHitResult) mc.crosshairTarget;
        BlockPos hit = bhr.getBlockPos();
        Direction dir = bhr.getSide();

        if (dir == Direction.DOWN) {
            BlockPos below = hit.down();
            if (!mc.world.getBlockState(below).isSolidBlock(mc.world, below)) return;
        }

        railPos = computeRailPos(mc, hit);
        if (railPos == null) return;
        if (blockDist(mc, railPos) > DIST_MAX) return;

        firePos = computeFirePos(mc, railPos);
        if (firePos == null) firePos = computeFirePosFallback(mc, railPos);
        if (firePos == null) return;

        if (!InventoryManager.validateSequenceInventory(mc)) return;

        railAim = Vec3d.ofCenter(railPos);
        cartAim = Vec3d.ofCenter(railPos).add(0, 0.15, 0);
        fireAim = Vec3d.ofCenter(firePos).add(0, 0.50, 0);
        xbowAim = Vec3d.ofCenter(railPos).add(0, 0.85, 0);

        InventoryManager.saveCurrentSlot(mc);
        savedSlot = SlotAccessor.get(mc);
        aimTick = 0; waitRetry = 0; cart = null;
        SafetyWatchdog.startGlobal();
        go(Phase.SEL_RAIL);
    }

    private static void tickFlick(Minecraft mc, Vec3d target, float yt, float pt, Phase next) {
        if (target == null) { hardReset(mc); return; }
        if (--timer > 0) return;
        aimTick++;
        float ang  = angDist(mc, target);
        float t    = Math.min(1.0f, (float) aimTick / AMAX);
        float ease = 1.0f - (1.0f - t) * (1.0f - t) * (1.0f - t);
        float near = ang < 8.0f ? (0.28f + 0.72f * ang / 8.0f) : 1.0f;
        float spd  = (0.72f + 0.28f * ease) * near;
        RotationManager.setEasingMode(RotationManager.EasingMode.SWIGHT_HIGH_SENS);
        RotationManager.smoothTo(mc, target, spd);
        boolean ok = RotationManager.isAligned(mc, target, yt, pt);
        if (ok || aimTick >= AMAX) {
            if (!ok) RotationManager.snapTo(mc, target);
            aimTick = 0; timer = 1; go(next);
        }
    }

    private static void tickWait(Minecraft mc, Phase next, java.util.function.BooleanSupplier check) {
        if (timer > 0) { timer--; return; }
        if (check.getAsBoolean()) { waitRetry = 0; go(next); return; }
        if (!SafetyWatchdog.onRetry("WAIT")) { hardReset(mc); return; }
        waitRetry++;
        timer = 2;
    }

    private static void tickWaitCart(Minecraft mc) {
        if (timer > 0) { timer--; return; }
        TntMinecartEntity found = findCart(mc, railPos, CR);   // ← Yarn
        if (found != null) {
            if (found.getPos().distanceTo(Vec3d.ofCenter(railPos)) > 0.6) {
                SafetyWatchdog.onRetry("WAIT_CART_POS");
                timer = 2; return;
            }
            cart    = found;
            xbowAim = aimThroughFire(mc, found);
            waitRetry = 0;
            go(Phase.SEL_FIRE);
            return;
        }
        if (!SafetyWatchdog.onRetry("WAIT_CART")) { hardReset(mc); return; }
        waitRetry++;
        timer = 3;
    }

    private static void tickShoot(Minecraft mc) {
        if (--timer > 0) return;
        int slot = InventoryManager.findChargedCrossbow(mc);
        if (slot < 0) { hardReset(mc); return; }
        sel(mc, slot);
        ItemStack held = mc.player.getMainHandStack();
        if (held.isEmpty() || !(held.getItem() instanceof CrossbowItem) || !CrossbowItem.isCharged(held)) {
            hardReset(mc); return;
        }
        if (cart != null && !cart.isAlive()) { hardReset(mc); return; }
        Vec3d live = liveAim(mc);
        if (live != null && !RotationManager.isAligned(mc, live, SYT, SPT)) {
            RotationManager.snapTo(mc, live);
        }
        InteractionManager.simulateClickUse(mc, InteractionManager.InteractionPriority.IMMEDIATE);
        timer = 20 + RNG.nextInt(6);
        go(Phase.COOLDOWN);
    }

    private static Vec3d liveAim(Minecraft mc) {
        if (cart != null && cart.isAlive()) return aimThroughFire(mc, cart);
        if (railPos != null) {
            TntMinecartEntity found = findCart(mc, railPos, CR);   // ← Yarn
            if (found != null) { cart = found; return aimThroughFire(mc, found); }
        }
        return xbowAim;
    }

    private static Vec3d aimThroughFire(Minecraft mc, TntMinecartEntity c) {   // ← Yarn
        Vec3d eye   = mc.player.getEyePos();
        Vec3d cpos  = c.getPos().add(0, 0.4, 0);
        Vec3d dir   = cpos.subtract(eye).normalize();
        return cpos.add(dir.multiply(1.2));
    }

    private static Vec3d predictCart(TntMinecartEntity c) {   // ← Yarn
        Vec3d p = c.getPos(); Vec3d v = c.getVelocity();
        return p.add(v.x * 1.9, 0.85, v.z * 1.9);
    }

    private static float angDist(Minecraft mc, Vec3d aim) {
        float ye = RotationManager.computeYawError(mc, aim);
        float pe = RotationManager.computePitchError(mc, aim);
        return (float) Math.sqrt(ye * ye + pe * pe);
    }

    private static BlockPos computeRailPos(Minecraft mc, BlockPos hit) {
        BlockPos up = hit.up();
        if (mc.world.getBlockState(up).isAir()) return up;
        for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos adj = hit.offset(d);
            if (mc.world.getBlockState(adj).isAir() && mc.world.getBlockState(adj.down()).isSolidBlock(mc.world, adj.down()))
                return adj;
        }
        return null;
    }

    private static BlockPos computeFirePos(Minecraft mc, BlockPos rail) {
        for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos p = rail.offset(d);
            if (mc.world.getBlockState(p).isAir() && blockDist(mc, p) <= DIST_MAX) return p;
        }
        for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos p = rail.offset(d).down();
            if (mc.world.getBlockState(p).isAir() && blockDist(mc, p) <= DIST_MAX) return p;
        }
        return null;
    }

    private static BlockPos computeFirePosFallback(Minecraft mc, BlockPos rail) {
        BlockPos above = rail.up();
        if (mc.world.getBlockState(above).isAir() && blockDist(mc, above) <= DIST_MAX) return above;
        for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos adj = rail.offset(d).up();
            if (mc.world.getBlockState(adj).isAir() && blockDist(mc, adj) <= DIST_MAX) return adj;
        }
        return null;
    }

    private static boolean isRailAt(Minecraft mc, BlockPos pos) {
        if (mc.world == null || pos == null) return false;
        var b = mc.world.getBlockState(pos).getBlock();
        return b == Blocks.RAIL || b == Blocks.POWERED_RAIL || b == Blocks.DETECTOR_RAIL || b == Blocks.ACTIVATOR_RAIL;
    }

    private static boolean isFireAt(Minecraft mc, BlockPos pos) {
        if (mc.world == null || pos == null) return false;
        var b = mc.world.getBlockState(pos).getBlock();
        return b == Blocks.FIRE || b == Blocks.SOUL_FIRE || b == Blocks.CAMPFIRE || b == Blocks.SOUL_CAMPFIRE;
    }

    private static TntMinecartEntity findCart(Minecraft mc, BlockPos near, double r) {   // ← Yarn
        if (mc.world == null || near == null) return null;
        Vec3d c = Vec3d.ofCenter(near);
        return mc.world.getEntitiesByClass(TntMinecartEntity.class,
                new Box(c.x - r, c.y - r, c.z - r, c.x + r, c.y + r, c.z + r), Entity::isAlive)
            .stream().min(Comparator.comparingDouble(e -> e.getPos().distanceTo(c))).orElse(null);
    }

    private static double blockDist(Minecraft mc, BlockPos pos) {
        if (mc.player == null) return 999;
        Vec3d eye = mc.player.getEyePos();
        return Math.sqrt(pos.getSquaredDistance(eye.x, eye.y, eye.z));
    }

    private static void sel(Minecraft mc, int slot) {
        if (slot >= 0 && slot < 9 && mc.player != null) InventoryManager.selectSlot(mc, slot);
    }

    private static void go(Phase next) { phase = next; timer = 0; aimTick = 0; }

    private static void hardReset(Minecraft mc) {
        if (mc != null && mc.player != null && savedSlot >= 0 && savedSlot < 9)
            SlotAccessor.set(mc, savedSlot);
        InventoryManager.restoreSavedSlot(mc);
        RotationManager.reset();
        SafetyWatchdog.reset();
        phase = Phase.IDLE; timer = 0; aimTick = 0; waitRetry = 0; savedSlot = -1;
        railPos = null; firePos = null;
        railAim = cartAim = fireAim = xbowAim = null;
        cart = null;
    }

    public static void purgePipelineRegistry() { hardReset(null); }
}
