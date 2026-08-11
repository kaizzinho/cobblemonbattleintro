package com.kaizzinho.battleslider.client

import com.kaizzinho.battleslider.client.config.BattleSliderConfig
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.minecraft.client.MinecraftClient
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.util.InputUtil
import net.minecraft.text.Text
import org.lwjgl.glfw.GLFW
import org.slf4j.LoggerFactory


@Environment(EnvType.CLIENT)
object BattleSliderKeybinds {

    private val LOGGER =
        LoggerFactory.getLogger("battleslider/BattleSliderKeybinds")

    private const val RAW_PRESS_DEDUP_WINDOW_MS = 150L

    private lateinit var skipIntroKey: KeyBinding
    private var registered = false
    private var wasPhysicallyDown = false
    private var lastRawPressMs = Long.MIN_VALUE

    fun getSkipKeyText(): Text =
        if (::skipIntroKey.isInitialized) {
            skipIntroKey.boundKeyLocalizedText
        } else {
            Text.literal("V")
        }

    fun register() {
        if (registered) {
            LOGGER.warn(
                "BattleSliderKeybinds.register() ignored: already registered"
            )
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


        LOGGER.info(
            "Skip-intro keybind registered: translationKey={}, defaultKeyCode={}",
            skipIntroKey.translationKey,
            GLFW.GLFW_KEY_V
        )

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            var queuedPressDetected = false
            while (skipIntroKey.wasPressed()) {
                queuedPressDetected = true
            }

            val physicallyDown = skipIntroKey.isPressed
            val risingEdgeDetected =
                physicallyDown && !wasPhysicallyDown
            wasPhysicallyDown = physicallyDown

            if (!queuedPressDetected && !risingEdgeDetected) {
                return@register
            }


            val rawAlreadyHandled =
                System.currentTimeMillis() - lastRawPressMs <=
                    RAW_PRESS_DEDUP_WINDOW_MS

            if (rawAlreadyHandled) {
                debugLog(
                    "Ignoring duplicate tick-level skip detection after raw key callback"
                )
                return@register
            }

            requestSkip(
                client = client,
                source = "client-tick",
                details = "queued=$queuedPressDetected, risingEdge=$risingEdgeDetected"
            )
        }
    }


    @JvmStatic
// raw input catches skip before battle screens eat the key
    fun onRawKeyPressed(keyCode: Int, scanCode: Int) {
        if (!registered || !::skipIntroKey.isInitialized) {
            return
        }

        if (!skipIntroKey.matchesKey(keyCode, scanCode)) {
            return
        }

        lastRawPressMs = System.currentTimeMillis()

        requestSkip(
            client = MinecraftClient.getInstance(),
            source = "raw-keyboard",
            details = "keyCode=$keyCode, scanCode=$scanCode"
        )
    }

    private fun requestSkip(
        client: MinecraftClient,
        source: String,
        details: String
    ) {
        debugLog(
            "Skip input detected: source={}, {}, screen={}, overlay={}",
            source,
            details,
            client.currentScreen?.javaClass?.simpleName ?: "none",
            BattleIntroOverlay.getDebugState()
        )


        client.execute {
            if (BattleIntroOverlay.canSkip()) {
                debugLog(
                    "Calling BattleIntroOverlay.skip() from {}",
                    source
                )
                BattleIntroOverlay.skip()
            } else {
                debugLog(
                    "Skip request from {} ignored because overlay is not " +
                        "currently skippable: {}",
                    source,
                    BattleIntroOverlay.getDebugState()
                )
            }
        }
    }

    private fun debugLog(message: String, vararg args: Any?) {
        if (BattleSliderConfig.debugLogging) {
            LOGGER.info(message, *args)
        }
    }
}
