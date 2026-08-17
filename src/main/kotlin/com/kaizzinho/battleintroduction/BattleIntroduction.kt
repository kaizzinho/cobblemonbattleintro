package com.kaizzinho.battleintroduction

import net.fabricmc.api.ModInitializer
import org.slf4j.LoggerFactory

class BattleIntroduction : ModInitializer {

    override fun onInitialize() {
        LOGGER.info("Initializing BattleIntroduction common handlers")
        AutoRecallHandler.register()
    }

    companion object {
        private val LOGGER = LoggerFactory.getLogger("battleintroduction")
    }
}
