package com.kaizzinho.battleslider.mixin.client;

import com.cobblemon.mod.common.client.gui.battle.BattleGUI;
import com.kaizzinho.battleslider.client.BattleIntroOverlay;
import net.minecraft.client.gui.DrawContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses Cobblemon's BattleGUI render (italic trainer name title card)
 * while our battle intro animation is playing.
 */
@Mixin(value = BattleGUI.class, remap = false)
public abstract class BattleGUIMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void suppressDuringAnimation(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (BattleIntroOverlay.INSTANCE.isAnimating()) {
            ci.cancel();
        }
    }
}
