package com.kaizzinho.battleslider.client

import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.util.InputUtil
import org.lwjgl.glfw.GLFW

/**
 * Lets the player skip the intro animation with a keypress -- jumps straight
 * to BattleIntroOverlay's exit phase via BattleIntroOverlay.skip().
 *
 * Call BattleSliderKeybinds.register() from your client entrypoint
 * alongside BattleHandler.register() / BattleIntroOverlay setup.
 */
@Environment(EnvType.CLIENT)
object BattleSliderKeybinds {

    private lateinit var skipIntroKey: KeyBinding

    fun register() {
        skipIntroKey = KeyBindingHelper.registerKeyBinding(
            KeyBinding(
                "key.battleslider.skip_intro",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_V,
                "category.battleslider"
            )
        )

        ClientTickEvents.END_CLIENT_TICK.register { _ ->
            while (skipIntroKey.wasPressed()) {
                if (BattleIntroOverlay.isAnimating()) {
                    BattleIntroOverlay.skip()
                }
            }
        }
    }
}