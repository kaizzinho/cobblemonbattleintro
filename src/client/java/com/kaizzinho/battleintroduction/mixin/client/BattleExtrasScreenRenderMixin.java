package com.kaizzinho.battleintroduction.mixin.client;

import com.kaizzinho.battleintroduction.client.BattleIntroOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


@Pseudo
@Mixin(
        targets = "name.modid.platform.FabricEventHandler",
        priority = 2500,
        remap = false
)
public abstract class BattleExtrasScreenRenderMixin {

    @Inject(
            method = "lambda$registerScreenRender$1",
            at = @At("HEAD"),
            cancellable = true,
            require = 0,
            remap = false
    )
    private static void battleintroduction$suppressBattleExtrasScreenRender(
            CallbackInfo ci
    ) {
        if (BattleIntroOverlay.INSTANCE.isVisualTransitionActive()) {
            ci.cancel();
        }
    }
}
