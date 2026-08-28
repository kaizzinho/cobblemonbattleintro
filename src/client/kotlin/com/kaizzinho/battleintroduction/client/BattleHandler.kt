package com.kaizzinho.battleintroduction.client

import com.cobblemon.mod.common.api.battles.model.actor.BattleActor
import com.cobblemon.mod.common.api.battles.model.actor.EntityBackedBattleActor
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor
import com.kaizzinho.battleintroduction.client.config.BattleIntroductionConfig
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.minecraft.client.MinecraftClient
import net.minecraft.entity.LivingEntity
import org.slf4j.LoggerFactory

@Environment(EnvType.CLIENT)
object BattleHandler {

    private val logger =
        LoggerFactory.getLogger(
            "battleintroduction/BattleHandler"
        )

    fun resolveOpponentEntity(
        opponentActor: BattleActor
    ): LivingEntity? {
        val world =
            MinecraftClient.getInstance().world
                ?: return null

        resolvePlayer(
            opponentActor,
            world.players
        )?.let { return it }

        val actorEntity =
            (
                opponentActor as? EntityBackedBattleActor<*>
            )?.entity as? LivingEntity

        resolveActorEntity(
            actorEntity,
            world.entities
                .filterIsInstance<LivingEntity>()
        )?.let { return it }

        val fallbackUuid =
            actorEntity?.uuid
                ?: opponentActor.uuid
        val fallback =
            world.entities
                .filterIsInstance<LivingEntity>()
                .firstOrNull {
                    it.uuid == fallbackUuid
                }

        debugLog(
            "Opponent fallback resolution: actor={}, uuid={}, result={}",
            opponentActor.javaClass.name,
            fallbackUuid,
            fallback?.javaClass?.name ?: "<none>"
        )

        return fallback
    }

    private fun resolvePlayer(
        actor: BattleActor,
        players: List<LivingEntity>
    ): LivingEntity? {
        val playerActor =
            actor as? PlayerBattleActor
                ?: return null
        val uuid =
            playerActor.getPlayerUUIDs()
                .firstOrNull()
                ?: return null

        return players.firstOrNull {
            it.uuid == uuid
        }
    }

    private fun resolveActorEntity(
        actorEntity: LivingEntity?,
        entities: List<LivingEntity>
    ): LivingEntity? {
        if (actorEntity == null) {
            return null
        }

        val byEntityId =
            entities.firstOrNull {
                it.id == actorEntity.id
            }

        if (byEntityId != null) {
            debugLog(
                "Resolved opponent through EntityBackedBattleActor entity ID: entity={}",
                byEntityId.javaClass.name
            )
            return byEntityId
        }

        return entities.firstOrNull {
            it.uuid == actorEntity.uuid
        }
    }

    private fun debugLog(
        message: String,
        vararg args: Any?
    ) {
        if (BattleIntroductionConfig.debugLogging) {
            logger.info(
                message,
                *args
            )
        }
    }
}
