package com.kaizzinho.battleintroduction.client

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import com.kaizzinho.battleintroduction.client.config.BattleIntroductionConfig


class BattleIntroductionClient : ClientModInitializer {

    private val logger = LoggerFactory.getLogger("battleintroduction/BattleIntroductionClient")

    override fun onInitializeClient() {
        logger.info("Initializing BattleIntroduction client components")


        BattleIntroductionConfig.load()

        BattleIntroOverlay.register()
        BattleIntroClientCoordinator.register()
        BattleIntroductionKeybinds.register()

        if (BattleIntroductionConfig.debugLogging) {
            val keywords = listOf(
                "cobble",
                "pokemon",
                "fight",
                "animation",
                "entity",
                "model",
                "render",
                "sodium",
                "iris",
                "wildboss",
                "rct"
            )

            val relevantMods = FabricLoader.getInstance().allMods
                .map { it.metadata }
                .filter { metadata ->
                    val haystack =
                        "${metadata.id} ${metadata.name}".lowercase()
                    keywords.any { it in haystack }
                }
                .sortedBy { it.id }

            logger.info(
                "[FACING-ENV] relevant loaded mods count={}",
                relevantMods.size
            )

            relevantMods.forEach { metadata ->
                logger.info(
                    "[FACING-ENV] id={} name={} version={}",
                    metadata.id,
                    metadata.name,
                    metadata.version.friendlyString
                )
            }
        }

        logger.info("BattleIntroduction client components initialized")
    }
}
