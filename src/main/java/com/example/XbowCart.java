package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.vehicle.minecart.MinecartTNT;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.InteractionHand;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import java.util.Comparator;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class XbowCart {

    public static boolean enabled = false;

    private enum Phase {
        IDLE,
        SEL_RAIL, AIM_RAIL, PLACE_RAIL, WAIT_RAIL,
        SEL_CART, AIM_CART, PLACE_CART, WAIT_CART,
        SEL_FIRE, AIM_FIRE, IGNITE, WAIT_FIRE,
        SEL_XBOW, AIM_XBOW, SHOOT, COOLDOWN
    }

    private static final Random RNG = new Random();
    private static final Map<String, Object> XBOW_REGISTRY = new ConcurrentHashMap<>();

    private static Phase       phase     = Phase.IDLE;
    private static int         timer     = 0;
    private static int         aimTick   = 0;
    private static int         waitRetry = 0;
    private static int         savedSlot = -1;
    private static BlockPos    railPos   = null;
    private static BlockPos    firePos   = null;
    private static Vec3        railAim   = null;
    private static Vec3        cartAim   = null;
    private static Vec3        fireAim   = null;
    private static Vec3        xbowAim   = null;
    private static MinecartTNT cart      = null;

    private static final float  PYT   = 3.5f;
    private static final float  PPT   = 4.0f;
    private static final float  SYT   = 4.5f;
    private static final float  SPT   = 5.0f;
    private static final int    AMAX  = 5;
    private static final double CR    = 3.2;
    private static final int    WMAX  = 8;

    static {
        XBOW_REGISTRY.put("UUID", UUID.randomUUID());
        XBOW_REGISTRY.put("Profile", "XbowCart-5Frame-Bypass");
    }

    public static void onTick(Minecraft mc) {
        if (!enabled || mc.player == null || mc.level == null) return;
        if (mc.screen != null) return;

        RotationManager.samplePlayerGcd(mc);

        switch (phase) {
            case IDLE     -> tickIdle(mc);
            case SEL_RAIL -> { sel(mc, InventoryManager.findRail(mc)); timer = 1; go(Phase.AIM_RAIL); }
            case AIM_RAIL -> tickFlick(mc, railAim, PYT, PPT, Phase.PLACE_RAIL);
            case PLACE_RAIL -> { if (--timer > 0) return; InteractionManager.simulateClickUse(mc, InteractionManager.InteractionPriority.HIGH); timer = 2; go(Phase.WAIT_RAIL); }
            case WAIT_RAIL -> tickWait(mc, Phase.SEL_CART, () -> isRailAt(mc, railPos));
            case SEL_CART -> { sel(mc, InventoryManager.findItem(mc, Items.TNT_MINECART)); timer = 1; go(Phase.AIM_CART); }
            case AIM_CART -> tickFlick(mc, cartAim, PYT, PPT, Phase.PLACE_CART);
            case PLACE_CART -> { if (--timer > 0) return; InteractionManager.simulateClickUse(mc, InteractionManager.InteractionPriority.HIGH); timer = 2; go(Phase.WAIT_CART); }
            case WAIT_CART -> tickWaitCart(mc);
            case SEL_FIRE -> { sel(mc, InventoryManager.findFireSource(mc)); timer = 1; go(Phase.AIM_FIRE); }
            case AIM_FIRE -> tickFlick(mc, fireAim, PYT, PPT, Phase.IGNITE);
            case IGNITE -> { if (--timer > 0) return; InteractionManager.simulateClickUse(mc, InteractionManager.InteractionPriority.HIGH); timer = 1; go(Phase.WAIT_FIRE); }
            case WAIT_FIRE -> tickWait(mc, Phase.SEL_XBOW, () -> isFireAt(mc, firePos));
            case SEL_XBOW -> { sel(mc, InventoryManager.findChargedCrossbow(mc)); timer = 1; go(Phase.AIM_XBOW); }
            case AIM_XBOW -> tickFlick(mc, liveAim(mc), SYT, SPT, Phase.SHOOT);
            case SHOOT -> tickShoot(mc);
            case COOLDOWN -> { if (--timer <= 0) hardReset(mc); }
        }

        XBOW_REGISTRY.put("Phase", phase.name());
    }

    public static void purgePipelineRegistry() { hardReset(null); }

    private static void tickIdle(Minecraft mc) {
        if (!mc.options.keyAttack.isDown()) return;
        if (mc.hitResult == null || mc.hitResult.getType() != HitResult.Type.BLOCK) return;
        BlockHitResult bhr = (BlockHitResult) mc.hitResult;
        if (bhr.getDirection() == Direction.DOWN) return;

        BlockPos hit = bhr.getBlockPos();
        railPos = computeRailPos(mc, hit);
        if (railPos == null || blockDist(mc, railPos) > 4.9) return;

        firePos = computeFirePos(mc, railPos);
        if (firePos == null) return;
        if (!InventoryManager.validateSequenceInventory(mc)) return;

        railAim = Vec3.atCenterOf(railPos).add(0, 0.55, 0);
        cartAim = Vec3.atCenterOf(railPos).add(0, 0.62, 0);
        fireAim = Vec3.atCenterOf(firePos).add(0, 0.50, 0);
        xbowAim = Vec3.atCenterOf(railPos).add(0, 0.85, 0);

        InventoryManager.saveCurrentSlot(mc);
        savedSlot = mc.player.getInventory().getSelectedSlot();
        aimTick = 0; waitRetry = 0; cart = null;
        SafetyWatchdog.startGlobal();
        go(Phase.SEL_RAIL);
    }

    private static void tickFlick(Minecraft mc, Vec3 target, float yt, float pt, Phase next) {
        if (target == null) { hardReset(mc); return; }
        if (--timer > 0) return;
        aimTick++;
        float ang = angDist(mc, target);
        float t   = Math.min(1.0f, (float) aimTick / AMAX);
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
        MinecartTNT found = findCart(mc, railPos, CR);
        if (found != null) {
            cart = found;
            xbowAim = predictCart(cart);
            waitRetry = 0;
            go(Phase.SEL_FIRE);
            return;
        }
        if (!SafetyWatchdog.onRetry("WAIT_CART")) { hardReset(mc); return; }
        InteractionManager.simulateClickUse(mc, InteractionManager.InteractionPriority.HIGH);
        timer = 2;
    }

    private static void tickShoot(Minecraft mc) {
        if (--timer > 0) return;
        int slot = InventoryManager.findChargedCrossbow(mc);
        if (slot < 0) { hardReset(mc); return; }
        sel(mc, slot);
        ItemStack held = mc.player.getMainHandItem();
        if (held.isEmpty() || !(held.getItem() instanceof CrossbowItem) || !CrossbowItem.isCharged(held)) {
            hardReset(mc); return;
        }
        if (cart != null && !cart.isAlive()) { hardReset(mc); return; }
        Vec3 live = liveAim(mc);
        if (live != null && !RotationManager.isAligned(mc, live, SYT, SPT)) RotationManager.snapTo(mc, live);
        InteractionManager.simulateClickUse(mc, InteractionManager.InteractionPriority.IMMEDIATE);
        timer = 20 + RNG.nextInt(6);
        go(Phase.COOLDOWN);
    }

    private static Vec3 liveAim(Minecraft mc) {
        if (cart != null && cart.isAlive()) return predictCart(cart);
        if (railPos != null) {
            MinecartTNT found = findCart(mc, railPos, CR);
            if (found != null) { cart = found; return predictCart(found); }
        }
        return xbowAim;
    }

    private static Vec3 predictCart(MinecartTNT c) {
        Vec3 p = c.position(); Vec3 v = c.getDeltaMovement();
        return p.add(v.x * 1.9, 0.85, v.z * 1.9);
    }

    private static float angDist(Minecraft mc, Vec3 aim) {
        float ye = RotationManager.computeYawError(mc, aim);
        float pe = RotationManager.computePitchError(mc, aim);
        return (float) Math.sqrt(ye * ye + pe * pe);
    }

    private static BlockPos computeRailPos(Minecraft mc, BlockPos hit) {
        BlockPos up = hit.above();
        if (mc.level.getBlockState(up).isAir()) return up;
        for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos adj = hit.relative(d);
            if (mc.level.getBlockState(adj).isAir() && mc.level.getBlockState(adj.below()).isSolidRender()) return adj;
        }
        return null;
    }

    private static BlockPos computeFirePos(Minecraft mc, BlockPos rail) {
        for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos p = rail.relative(d);
            if (mc.level.getBlockState(p).isAir() && blockDist(mc, p) <= 4.9) return p;
        }
        for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos p = rail.relative(d).below();
            if (mc.level.getBlockState(p).isAir() && blockDist(mc, p) <= 4.9) return p;
        }
        return null;
    }

    private static boolean isRailAt(Minecraft mc, BlockPos pos) {
        if (mc.level == null || pos == null) return false;
        var b = mc.level.getBlockState(pos).getBlock();
        return b == Blocks.RAIL || b == Blocks.POWERED_RAIL || b == Blocks.DETECTOR_RAIL || b == Blocks.ACTIVATOR_RAIL;
    }

    private static boolean isFireAt(Minecraft mc, BlockPos pos) {
        if (mc.level == null || pos == null) return false;
        var b = mc.level.getBlockState(pos).getBlock();
        return b == Blocks.FIRE || b == Blocks.SOUL_FIRE || b == Blocks.CAMPFIRE || b == Blocks.SOUL_CAMPFIRE;
    }

    private static MinecartTNT findCart(Minecraft mc, BlockPos near, double r) {
        if (mc.level == null || near == null) return null;
        Vec3 c = Vec3.atCenterOf(near);
        return mc.level.getEntitiesOfClass(MinecartTNT.class,
            new AABB(c.x - r, c.y - r, c.z - r, c.x + r, c.y + r, c.z + r), e -> e.isAlive())
            .stream().min(Comparator.comparingDouble(e -> e.position().distanceTo(c))).orElse(null);
    }

    private static double blockDist(Minecraft mc, BlockPos pos) {
        if (mc.player == null) return 999;
        Vec3 eye = mc.player.getEyePosition(1.0f);
        return Math.sqrt(pos.distToCenterSqr(eye.x, eye.y, eye.z));
    }

    private static void sel(Minecraft mc, int slot) {
        if (slot >= 0 && slot < 9 && mc.player != null) InventoryManager.selectSlot(mc, slot);
    }

    private static void go(Phase next) { phase = next; timer = 0; aimTick = 0; }

    private static void hardReset(Minecraft mc) {
        if (mc != null && mc.player != null && savedSlot >= 0 && savedSlot < 9)
            mc.player.getInventory().setSelectedSlot(savedSlot);
        InventoryManager.restoreSavedSlot(mc);
        RotationManager.reset();
        SafetyWatchdog.reset();
        phase = Phase.IDLE; timer = 0; aimTick = 0; waitRetry = 0; savedSlot = -1;
        railPos = null; firePos = null;
        railAim = cartAim = fireAim = xbowAim = null;
        cart = null;
    }

    /**
     * Registra este módulo no ClientTickEvents.END_CLIENT_TICK do Fabric.
     * Chamar uma vez durante a inicialização do mod (ex: ClientModInitializer.onInitializeClient()).
     *
     * Exemplo:
     *   XbowCart.register();
     */
    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(XbowCart::onTick);
    }

}