package com.kaizzinho.battleslider.mixin.client;

import com.cobblemon.mod.common.client.gui.battle.BattleOverlay;
import com.kaizzinho.battleslider.client.BattleIntroOverlay;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses Cobblemon's BattleOverlay (trainer name title card in italic white)
 * while our battle intro animation is playing.
 */
@Mixin(value = BattleOverlay.class, remap = false)
public abstract class BattleOverlayMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void suppressDuringAnimation(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        if (BattleIntroOverlay.INSTANCE.isAnimating()) {
            ci.cancel();
        }
    }
}
