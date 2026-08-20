package com.example.mixin;

import com.example.AutoMace;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.List;
import java.util.Random;

@Mixin(Minecraft.class)
public class MixinAutoMace {
    private static final Random R = new Random();
    private static LivingEntity target = null;
    private static int origSlot = -1;
    private static int swapDelay = 0;
    private static int stage = 0;
    private static long lastAttack = 0;
    private static Vec3 lastPos = null;
    private static float sy = Float.NaN, sp = Float.NaN;

    @Inject(method = "tick", at = @At("HEAD"))
    private void onTick(CallbackInfo ci) {
        Minecraft c = Minecraft.getInstance();
        if (c.player == null || c.level == null) return;
        if (!AutoMace.enabled) return;

        boolean falling = !c.player.onGround() && c.player.fallDistance >= 2.0f;
        if (!falling) { reset(c); return; }

        double range = c.player.fallDistance > 8.0 ? 12.0 : 7.0;
        if (target == null || !target.isAlive() || c.player.distanceTo(target) > range) {
            target = findTarget(c, range);
            if (target == null) { reset(c); return; }
            lastPos = target.position();
        }

        Vec3 eye = c.player.getEyePosition();
        Vec3 targetPos = target.getEyePosition(c.getDeltaTracker().getGameTimeDeltaPartialTick(false));
        if (lastPos != null) {
            Vec3 vel = target.position().subtract(lastPos);
            targetPos = targetPos.add(vel.scale(0.5));
        }
        lastPos = target.position();

        double dx = targetPos.x - eye.x, dy = targetPos.y - eye.y, dz = targetPos.z - eye.z;
        double dist = Math.sqrt(dx*dx + dz*dz);
        float tYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float tPitch = (float) -Math.toDegrees(Math.atan2(dy, dist));

        if (Float.isNaN(sy) || Float.isNaN(sp)) { sy = c.player.getYRot(); sp = c.player.getXRot(); }

        float yDiff = tYaw - sy; while(yDiff>180)yDiff-=360; while(yDiff<-180)yDiff+=360;
        float pDiff = tPitch - sp; while(pDiff>180)pDiff-=360; while(pDiff<-180)pDiff+=360;

        float maxTurn = 6f + (R.nextFloat()-0.5f)*1.5f;
        float attn = 0.7f + (float)(1.0/(dist+0.5))*0.2f; if(attn>0.9f) attn=0.9f;
        float overshoot = 0.03f + R.nextFloat()*0.04f;
        float stepY = yDiff*attn + yDiff*overshoot;
        float stepP = pDiff*attn*0.6f + pDiff*overshoot*0.6f;
        stepY = Math.max(-maxTurn, Math.min(maxTurn, stepY));
        stepP = Math.max(-maxTurn*0.6f, Math.min(maxTurn*0.6f, stepP));
        stepY += R.nextGaussian()*0.08f; stepP += R.nextGaussian()*0.06f;

        sy += stepY; sp += stepP; sp = Math.max(-90, Math.min(90, sp));
        c.player.setYRot(sy); c.player.setXRot(sp); c.player.yHeadRot = sy;

        if (c.player.distanceTo(target) > 3.0) return;
        if (System.currentTimeMillis() - lastAttack < 150) return;
        if (c.player.getAttackStrengthScale(0f) < 0.9f) return;
        if (R.nextInt(100) < 3) return;

        ItemStack main = c.player.getMainHandItem();
        boolean hasMace = !main.isEmpty() && main.is(Items.MACE);

        if (!hasMace) {
            int slot = findBestMace(c);
            if (slot == -1) { reset(c); return; }
            if (origSlot == -1) origSlot = c.player.getInventory().getSelectedSlot();
            c.player.getInventory().setSelectedSlot(slot);
            stage = 1; swapDelay = 2; return;
        }

        if (stage == 0 || stage == 2) {
            if (R.nextInt(100) < 5) c.player.setYRot(c.player.getYRot() + (R.nextFloat()-0.5f)*2f);
            c.gameMode.attack(c.player, target);
            c.player.swing(InteractionHand.MAIN_HAND);
            lastAttack = System.currentTimeMillis();
            reset(c);
        }

        if (stage == 1) { swapDelay--; if(swapDelay<=0) stage=2; }
    }

    private void reset(Minecraft c) {
        if (origSlot != -1 && c.player != null) c.player.getInventory().setSelectedSlot(origSlot);
        target = null; origSlot = -1; swapDelay = 0; stage = 0; lastPos = null;
        if (Float.isNaN(sy)) { sy = c.player != null ? c.player.getYRot() : 0; sp = c.player != null ? c.player.getXRot() : 0; }
    }

    private LivingEntity findTarget(Minecraft c, double range) {
        AABB box = c.player.getBoundingBox().inflate(range, 400, range);
        List<LivingEntity> list = c.level.getEntitiesOfClass(LivingEntity.class, box, e ->
            e != c.player && e.isAlive() && !e.isDeadOrDying() && c.player.getY() > e.getY() + 0.3 && !e.isInvisible());
        if(list.isEmpty()) return null;
        return list.stream().min((a,b)->Double.compare(c.player.distanceToSqr(a), c.player.distanceToSqr(b))).orElse(null);
    }

    private int findBestMace(Minecraft c) {
        RegistryAccess reg = c.level.registryAccess();
        var lookup = reg.lookupOrThrow(Registries.ENCHANTMENT);
        Holder<Enchantment> density = lookup.getOrThrow(Enchantments.DENSITY);
        Holder<Enchantment> breach = lookup.getOrThrow(Enchantments.BREACH);
        int best=-1, bestScore=-1;
        for(int i=0;i<9;i++){ ItemStack s=c.player.getInventory().getItem(i); if(s.isEmpty()||!s.is(Items.MACE)) continue; int d=EnchantmentHelper.getItemEnchantmentLevel(density,s); int b=EnchantmentHelper.getItemEnchantmentLevel(breach,s); int score=d*2+b; if(score>bestScore){bestScore=score; best=i;} }
        if(best==-1){ for(int i=0;i<9;i++){ ItemStack s=c.player.getInventory().getItem(i); if(!s.isEmpty()&&s.is(Items.MACE)){ best=i; break; } } }
        return best;
    }
}
