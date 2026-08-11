package com.kaizzinho.battleslider.mixin.client;

import com.kaizzinho.battleslider.client.BattleIntroOverlay;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


@Mixin(value = InGameHud.class, priority = 2000)
public abstract class InGameHudMixin {

    @Inject(
            method = "render",
            at = @At("HEAD"),
            cancellable = true
    )
    private void battleslider$renderExclusiveHud(
            DrawContext context,
            RenderTickCounter tickCounter,
            CallbackInfo ci
    ) {
        BattleIntroOverlay overlay = BattleIntroOverlay.INSTANCE;
        if (!overlay.isVisualTransitionActive()) {
            return;
        }


        overlay.renderExclusive(
                context,
                tickCounter.getTickDelta(true)
        );

        ci.cancel();
    }
}
