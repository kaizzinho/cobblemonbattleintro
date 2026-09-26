package com.kaizzinho.battleintroduction.mixin.client;

import com.cobblemon.mod.common.client.keybind.keybinds.PartySendBinding;
import com.kaizzinho.battleintroduction.client.BattleIntroOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = PartySendBinding.class, remap = false)
public abstract class PartySendBindingMixin {

    @Inject(method = "onRelease", at = @At("HEAD"), cancellable = true)
    private void battleintroduction$blockManualSendOut(CallbackInfo ci) {
        if (!BattleIntroOverlay.INSTANCE.isManualSendOutLocked()) return;

        PartySendBinding.INSTANCE.setWasDown(false);
        PartySendBinding.INSTANCE.setCanApplyChange(true);
        PartySendBinding.INSTANCE.setHeldDownSeconds(0.0F);
        ci.cancel();
    }
}
