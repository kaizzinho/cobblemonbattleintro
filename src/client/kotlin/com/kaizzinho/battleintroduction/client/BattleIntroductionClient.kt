package com.kaizzinho.battleintroduction.client

import net.fabricmc.api.ClientModInitializer
import org.slf4j.LoggerFactory
import com.kaizzinho.battleintroduction.client.config.BattleIntroductionConfig


class BattleIntroductionClient : ClientModInitializer {

    private val logger = LoggerFactory.getLogger("battleintroduction/BattleIntroductionClient")

    override fun onInitializeClient() {
        logger.info("Initializing BattleIntroduction client components")


        BattleIntroductionConfig.load()

        BattleIntroOverlay.register()
        BattleHandler.register()
        BattleIntroductionKeybinds.register()

        logger.info("BattleIntroduction client components initialized")
    }
}
