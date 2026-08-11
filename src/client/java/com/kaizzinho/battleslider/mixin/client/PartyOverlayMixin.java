package com.kaizzinho.battleslider.mixin.client;

import com.cobblemon.mod.common.client.gui.PartyOverlay;
import com.kaizzinho.battleslider.client.BattleIntroOverlay;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


@Mixin(value = PartyOverlay.class, remap = false, priority = 2500)
public abstract class PartyOverlayMixin {

    @Inject(
            method = "render",
            at = @At("HEAD"),
            cancellable = true,
            require = 0,
            remap = true
    )
    private void battleslider$suppressDuringAnimation(
            DrawContext context,
            RenderTickCounter tickCounter,
            CallbackInfo ci
    ) {
        if (BattleIntroOverlay.INSTANCE.isVisualTransitionActive()) {
            ci.cancel();
        }
    }
}
