package com.kaizzinho.battleslider.client

import net.fabricmc.api.ClientModInitializer
import org.slf4j.LoggerFactory
import com.kaizzinho.battleslider.client.config.BattleSliderConfig

/**
 * Client entrypoint for Battleslider.
 *
 * This registration is essential: merely having BattleSliderKeybinds.kt in
 * the project does not activate it. Fabric must load this class through the
 * client entrypoint declared in fabric.mod.json.
 */
class BattlesliderClient : ClientModInitializer {

    private val logger = LoggerFactory.getLogger("battleslider/BattlesliderClient")

    override fun onInitializeClient() {
        logger.info("Initializing Battleslider client components")

        // Load before registering components so every diagnostic logger sees
        // the configured value from the first client tick onward.
        BattleSliderConfig.load()

        BattleIntroOverlay.register()
        BattleHandler.register()
        BattleSliderKeybinds.register()

        logger.info("Battleslider client components initialized")
    }
}