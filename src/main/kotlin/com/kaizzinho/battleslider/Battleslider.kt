package com.kaizzinho.battleslider

import net.fabricmc.api.ModInitializer
import org.slf4j.LoggerFactory

class Battleslider : ModInitializer {

    override fun onInitialize() {
        LOGGER.info("Initializing Battleslider common handlers")
        AutoRecallHandler.register()
    }

    companion object {
        private val LOGGER = LoggerFactory.getLogger("battleslider")
    }
}
