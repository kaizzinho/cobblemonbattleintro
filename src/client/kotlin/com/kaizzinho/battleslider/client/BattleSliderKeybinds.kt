package com.kaizzinho.battleslider.client

import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.util.InputUtil
import net.minecraft.text.Text
import org.lwjgl.glfw.GLFW
import org.slf4j.LoggerFactory
import com.kaizzinho.battleslider.client.config.BattleSliderConfig

/**
 * Registers and polls the intro-skip key.
 *
 * Detection uses both KeyBinding.wasPressed() and a rising-edge check on
 * KeyBinding.isPressed. The second path is intentionally redundant: it makes
 * the skip reliable even when another screen/mod consumes the queued press
 * count before END_CLIENT_TICK runs.
 */
@Environment(EnvType.CLIENT)
object BattleSliderKeybinds {

    private val LOGGER = LoggerFactory.getLogger("battleslider/BattleSliderKeybinds")

    private fun debugLog(message: String, vararg args: Any?) {
        if (BattleSliderConfig.debugLogging) {
            LOGGER.info(message, *args)
        }
    }

    private lateinit var skipIntroKey: KeyBinding
    private var registered = false
    private var wasPhysicallyDown = false

    fun getSkipKeyText(): Text =
        if (::skipIntroKey.isInitialized) skipIntroKey.boundKeyLocalizedText else Text.literal("V")

    fun register() {
        if (registered) {
            LOGGER.warn("BattleSliderKeybinds.register() ignored: already registered")
            return
        }
        registered = true

        skipIntroKey = KeyBindingHelper.registerKeyBinding(
            KeyBinding(
                "key.battleslider.skip_intro",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_V,
                "category.battleslider"
            )
        )

        // Do not resolve boundKeyLocalizedText here. Client entrypoints run before
        // Minecraft has finished initializing GLFW, and resolving a physical key
        // name can call GLFW.glfwGetKeyName(), producing a pre-init GLFW error.
        LOGGER.info(
            "Skip-intro keybind registered: translationKey={}, defaultKeyCode={}",
            skipIntroKey.translationKey,
            GLFW.GLFW_KEY_V
        )

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            // Standard Fabric/Minecraft press queue.
            var queuedPressDetected = false
            while (skipIntroKey.wasPressed()) {
                queuedPressDetected = true
            }

            // Rising-edge fallback. This fires once when the key changes from
            // released to held, not every tick while the player holds it.
            val physicallyDown = skipIntroKey.isPressed
            val risingEdgeDetected = physicallyDown && !wasPhysicallyDown
            wasPhysicallyDown = physicallyDown

            if (!queuedPressDetected && !risingEdgeDetected) {
                return@register
            }

            debugLog(
                "Skip key detected: queuedPress={}, risingEdge={}, screen={}, overlay={}",
                queuedPressDetected,
                risingEdgeDetected,
                client.currentScreen?.javaClass?.simpleName ?: "none",
                BattleIntroOverlay.getDebugState()
            )

            if (BattleIntroOverlay.canSkip()) {
                client.execute {
                    debugLog("Calling BattleIntroOverlay.skip()")
                    BattleIntroOverlay.skip()
                }
            } else {
                debugLog(
                    "Skip press detected, but the overlay is not currently skippable: {}",
                    BattleIntroOverlay.getDebugState()
                )
            }
        }
    }
}