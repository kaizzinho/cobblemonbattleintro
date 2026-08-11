package com.kaizzinho.battleslider.client

import net.fabricmc.api.ClientModInitializer
import org.slf4j.LoggerFactory
import com.kaizzinho.battleslider.client.config.BattleSliderConfig


class BattlesliderClient : ClientModInitializer {

    private val logger = LoggerFactory.getLogger("battleslider/BattlesliderClient")

    override fun onInitializeClient() {
        logger.info("Initializing Battleslider client components")


        BattleSliderConfig.load()

        BattleIntroOverlay.register()
        BattleHandler.register()
        BattleSliderKeybinds.register()

        logger.info("Battleslider client components initialized")
    }
}
