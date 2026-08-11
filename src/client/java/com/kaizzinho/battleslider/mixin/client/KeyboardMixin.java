package com.kaizzinho.battleslider.mixin.client;

import com.kaizzinho.battleslider.client.BattleSliderKeybinds;
import net.minecraft.client.Keyboard;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


@Mixin(value = Keyboard.class, priority = 2500)
public abstract class KeyboardMixin {

    @Inject(
            method = "onKey(JIIII)V",
            at = @At("HEAD")
    )
    private void battleslider$onKey(
            long window,
            int keyCode,
            int scanCode,
            int action,
            int modifiers,
            CallbackInfo ci
    ) {
        if (action == GLFW.GLFW_PRESS) {
            BattleSliderKeybinds.onRawKeyPressed(keyCode, scanCode);
        }
    }
}
