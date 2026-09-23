package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.vehicle.MinecartTNT;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.Random;

public class XbowCart extends ClientBase.Module {

    private enum Phase {
        IDLE,
        SELECT_RAIL, AIM_RAIL,   PLACE_RAIL,  WAIT_RAIL,
        SELECT_CART, AIM_CART,   PLACE_CART,  WAIT_CART,
        SELECT_FIRE, AIM_FIRE,   IGNITE,      WAIT_FIRE,
        SELECT_XBOW, AIM_XBOW,   SHOOT,       WAIT_SHOT,
        COOLDOWN
    }

    private static final float  FLICK_YAW_TOL    = 3.5f;
    private static final float  FLICK_PITCH_TOL  = 4.0f;
    private static final float  SHOOT_YAW_TOL    = 4.5f;
    private static final float  SHOOT_PITCH_TOL  = 5.0f;
    private static final double CART_SCAN_R       = 3.2;

    private static final double Y_RAIL  = 0.55;
    private static final double Y_CART  = 0.62;
    private static final double Y_FIRE  = 0.50;
    private static final double Y_XBOW  = 0.85;

    private static final int AIM_MAX_TICKS       = 8;
    private static final int AIM_FLICK_MAX_TICKS = 5;

    private Phase       phase       = Phase.IDLE;
    private int         timer       = 0;
    private int         aimTick     = 0;
    private int         savedSlot   = -1;

    private BlockPos    railPos     = null;
    private BlockPos    firePos     = null;
    private Vec3        railAim     = null;
    private Vec3        cartAim     = null;
    private Vec3        fireAim     = null;
    private Vec3        xbowAim     = null;
    private MinecartTNT cart        = null;

    private int railSlot = -1, cartSlot = -1, fireSlot = -1, xbowSlot = -1;

    private final Random rng = new Random();

    public XbowCart() { super("XbowCart", ClientBase.ModuleCategory.COMBAT); }

    @Override
    public void onEnable(Minecraft mc)  { super.onEnable(mc);  hardReset(mc); }
    @Override
    public void onDisable(Minecraft mc) { super.onDisable(mc); hardReset(mc); }

    @Override
    public void onTick(Minecraft mc) {
        super.onTick(mc);
        if (mc.player == null || mc.level == null) return;
        if (mc.screen != null && !(mc.screen instanceof ClickGUI)) return;

        SafetyWatchdog.update(mc);
        if (SafetyWatchdog.isTripped()) { if (phase != Phase.IDLE) hardReset(mc); return; }

        RotationManager.samplePlayerGcd(mc);

        switch (phase) {
            case IDLE        -> tickIdle(mc);
            case SELECT_RAIL -> { select(mc, railSlot); go(Phase.AIM_RAIL); }
            case AIM_RAIL    -> tickFlick(mc, railAim, FLICK_YAW_TOL, FLICK_PITCH_TOL, Phase.PLACE_RAIL, AIM_FLICK_MAX_TICKS);
            case PLACE_RAIL  -> tickPlace(mc, Phase.WAIT_RAIL, 2);
            case WAIT_RAIL   -> tickWait(mc, Phase.SELECT_CART, () -> RaycastManager.isRailAt(mc, railPos));
            case SELECT_CART -> { select(mc, cartSlot); go(Phase.AIM_CART); }
            case AIM_CART    -> tickFlick(mc, cartAim, FLICK_YAW_TOL, FLICK_PITCH_TOL, Phase.PLACE_CART, AIM_FLICK_MAX_TICKS);
            case PLACE_CART  -> tickPlace(mc, Phase.WAIT_CART, 2);
            case WAIT_CART   -> tickWaitCart(mc);
            case SELECT_FIRE -> { select(mc, freshFire(mc)); go(Phase.AIM_FIRE); }
            case AIM_FIRE    -> tickFlick(mc, fireAim, FLICK_YAW_TOL, FLICK_PITCH_TOL, Phase.IGNITE, AIM_FLICK_MAX_TICKS);
            case IGNITE      -> tickIgnite(mc);
            case WAIT_FIRE   -> tickWait(mc, Phase.SELECT_XBOW, () -> RaycastManager.isFireAt(mc, firePos));
            case SELECT_XBOW -> { select(mc, freshXbow(mc)); go(Phase.AIM_XBOW); }
            case AIM_XBOW    -> tickFlick(mc, liveAim(mc), SHOOT_YAW_TOL, SHOOT_PITCH_TOL, Phase.SHOOT, AIM_MAX_TICKS);
            case SHOOT       -> tickShoot(mc);
            case WAIT_SHOT   -> { if (--timer <= 0) go(Phase.COOLDOWN); }
            case COOLDOWN    -> { if (--timer <= 0) hardReset(mc); }
        }

        statusLabel = phase.name();
    }

    private void tickIdle(Minecraft mc) {
        if (!mc.options.keyAttack.isDown()) return;
        if (!InventoryManager.validateSequenceInventory(mc)) return;

        BlockHitResult bhr = RaycastManager.getValidHit(mc);
        if (bhr == null) return;

        BlockPos hit  = bhr.getBlockPos();
        Direction face = bhr.getDirection();
        if (face == Direction.DOWN) return;

        railPos = computeRailPos(mc, hit);
        if (railPos == null || RaycastManager.distanceToBlock(mc, railPos) > 4.9) return;

        firePos = pickFirePos(mc, railPos);
        if (firePos == null) return;

        railSlot = InventoryManager.findRail(mc);
        cartSlot = InventoryManager.findItem(mc, net.minecraft.world.item.Items.TNT_MINECART);
        fireSlot = InventoryManager.getBestFireSource(mc);
        xbowSlot = InventoryManager.findChargedCrossbow(mc);
        if (railSlot < 0 || cartSlot < 0 || fireSlot < 0 || xbowSlot < 0) return;

        railAim = Vec3.atCenterOf(railPos).add(0, Y_RAIL, 0);
        cartAim = Vec3.atCenterOf(railPos).add(0, Y_CART, 0);
        fireAim = Vec3.atCenterOf(firePos).add(0, Y_FIRE, 0);
        xbowAim = Vec3.atCenterOf(railPos).add(0, Y_XBOW, 0);

        InventoryManager.saveCurrentSlot(mc);
        savedSlot = mc.player.getInventory().getSelectedSlot();
        aimTick = 0;
        cart    = null;
        SafetyWatchdog.startGlobal();
        go(Phase.SELECT_RAIL);
    }

    private void tickFlick(Minecraft mc, Vec3 target, float yTol, float pTol, Phase next, int maxTicks) {
        if (target == null) { hardReset(mc); return; }
        if (--timer > 0) return;

        aimTick++;

        float angDist = angularDist(mc, target);
        float factor  = flickFactor(aimTick, maxTicks, angDist);
        RotationManager.smoothTo(mc, target, factor);

        boolean aligned = RotationManager.isAligned(mc, target, yTol, pTol);
        if (aligned || aimTick >= maxTicks) {
            if (!aligned) RotationManager.snapTo(mc, target);
            aimTick = 0;
            timer   = 1;
            go(next);
        }
    }

    private void tickPlace(Minecraft mc, Phase next, int waitAfter) {
        if (--timer > 0) return;
        InteractionManager.simulateClickUse(mc, InteractionManager.InteractionPriority.HIGH);
        timer = waitAfter;
        go(next);
    }

    private void tickWait(Minecraft mc, Phase next, java.util.function.BooleanSupplier check) {
        if (timer > 0) { timer--; return; }
        if (!check.getAsBoolean()) {
            if (!SafetyWatchdog.onRetry("WAIT")) { hardReset(mc); return; }
            timer = 2;
            return;
        }
        go(next);
    }

    private void tickWaitCart(Minecraft mc) {
        if (timer > 0) { timer--; return; }
        Optional<MinecartTNT> found = RaycastManager.findNearestEntity(mc, MinecartTNT.class, railPos, CART_SCAN_R);
        if (found.isEmpty()) {
            if (!SafetyWatchdog.onRetry("WAIT_CART")) { hardReset(mc); return; }
            InteractionManager.simulateClickUse(mc, InteractionManager.InteractionPriority.HIGH);
            timer = 2; return;
        }
        cart    = found.get();
        xbowAim = predictedCartAim(cart);
        go(Phase.SELECT_FIRE);
    }

    private void tickIgnite(Minecraft mc) {
        if (--timer > 0) return;
        if (mc.level.getBlockState(firePos).isAir()) {
            InteractionManager.simulateClickUse(mc, InteractionManager.InteractionPriority.HIGH);
        }
        timer = 1;
        go(Phase.WAIT_FIRE);
    }

    private void tickShoot(Minecraft mc) {
        if (--timer > 0) return;

        int fresh = freshXbow(mc);
        if (fresh < 0) { hardReset(mc); return; }
        select(mc, fresh);

        ItemStack held = mc.player.getMainHandItem();
        if (held.isEmpty() || !(held.getItem() instanceof CrossbowItem) || !CrossbowItem.isCharged(held)) {
            hardReset(mc); return;
        }
        if (cart != null && !cart.isAlive()) { hardReset(mc); return; }

        Vec3 live = liveAim(mc);
        if (live != null && !RotationManager.isAligned(mc, live, SHOOT_YAW_TOL, SHOOT_PITCH_TOL)) {
            RotationManager.snapTo(mc, live);
        }

        InteractionManager.simulateClickUse(mc, InteractionManager.InteractionPriority.IMMEDIATE);
        timer = 2 + rng.nextInt(3);
        go(Phase.WAIT_SHOT);
    }

    private float flickFactor(int tick, int maxTicks, float angDist) {
        float t        = (float) tick / Math.max(1, maxTicks);
        float easeOut  = 1.0f - (1.0f - t) * (1.0f - t) * (1.0f - t);
        float nearDecel = angDist < 10.0f ? (0.3f + 0.7f * (angDist / 10.0f)) : 1.0f;
        float speed     = 0.72f + 0.28f * easeOut;
        return speed * nearDecel;
    }

    private float angularDist(Minecraft mc, Vec3 target) {
        if (mc.player == null) return 180.0f;
        double dx = target.x - mc.player.getX();
        double dy = target.y - mc.player.getEyeY();
        double dz = target.z - mc.player.getZ();
        double h  = Math.max(1e-9, Math.sqrt(dx*dx + dz*dz));
        float ty  = (float)(Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float tp  = (float)(-Math.toDegrees(Math.atan2(dy, h)));
        float dy2 = Math.abs(net.minecraft.util.Mth.wrapDegrees(mc.player.getYRot() - ty));
        float dp2 = Math.abs(mc.player.getXRot() - tp);
        return (float) Math.sqrt(dy2*dy2 + dp2*dp2);
    }

    private Vec3 liveAim(Minecraft mc) {
        if (cart != null && cart.isAlive()) return predictedCartAim(cart);
        if (railPos != null) {
            Optional<MinecartTNT> found = RaycastManager.findNearestEntity(mc, MinecartTNT.class, railPos, CART_SCAN_R);
            if (found.isPresent()) { cart = found.get(); return predictedCartAim(cart); }
        }
        return xbowAim;
    }

    private Vec3 predictedCartAim(MinecartTNT c) {
        Vec3 pos = c.position();
        Vec3 vel = c.getDeltaMovement();
        return pos.add(vel.x * 1.9, Y_XBOW, vel.z * 1.9);
    }

    private BlockPos computeRailPos(Minecraft mc, BlockPos hit) {
        BlockPos up = hit.above();
        if (mc.level.getBlockState(up).isAir()) return up;
        for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos adj = hit.relative(d);
            if (mc.level.getBlockState(adj).isAir() && mc.level.getBlockState(adj.below()).isSolidRender()) return adj;
        }
        return null;
    }

    private BlockPos pickFirePos(Minecraft mc, BlockPos rail) {
        for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos p = rail.relative(d);
            if (mc.level.getBlockState(p).isAir() && RaycastManager.distanceToBlock(mc, p) <= 4.9) return p;
        }
        for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos p = rail.relative(d).below();
            if (mc.level.getBlockState(p).isAir() && RaycastManager.distanceToBlock(mc, p) <= 4.9) return p;
        }
        return null;
    }

    private void select(Minecraft mc, int slot) {
        if (slot >= 0 && slot < 9) InventoryManager.selectSlot(mc, slot);
    }

    private void go(Phase next) {
        phase   = next;
        timer   = 0;
        aimTick = 0;
    }

    private int freshFire(Minecraft mc)  { return InventoryManager.getBestFireSource(mc); }
    private int freshXbow(Minecraft mc)  { return InventoryManager.findChargedCrossbow(mc); }

    private void hardReset(Minecraft mc) {
        InventoryManager.restoreSavedSlot(mc);
        RotationManager.reset();
        InventoryManager.invalidateCache();
        SafetyWatchdog.reset();
        phase = Phase.IDLE; timer = 0; aimTick = 0; savedSlot = -1;
        railPos = null; firePos = null;
        railAim = cartAim = fireAim = xbowAim = null;
        cart = null;
        railSlot = cartSlot = fireSlot = xbowSlot = -1;
    }
}
