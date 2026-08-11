package com.kaizzinho.battleslider.mixin.client;

import com.cobblemon.mod.common.client.gui.battle.widgets.BattleMessagePane;
import com.kaizzinho.battleslider.client.BattleIntroOverlay;
import net.minecraft.client.gui.DrawContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


@Mixin(value = BattleMessagePane.class, remap = false, priority = 2600)
public abstract class BattleMessagePaneMixin {

    @Inject(
            method = "renderWidget",
            at = @At("HEAD"),
            cancellable = true,
            require = 0,
            remap = true
    )
    private void battleslider$suppressBattleMessagePane(
            DrawContext context,
            int mouseX,
            int mouseY,
            float delta,
            CallbackInfo ci
    ) {
        if (BattleIntroOverlay.INSTANCE.isVisualTransitionActive()) {
            ci.cancel();
        }
    }
}
