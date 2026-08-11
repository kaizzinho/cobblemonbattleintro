package com.kaizzinho.battleslider.client

import com.cobblemon.mod.common.api.battles.model.actor.ActorType
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor
import com.cobblemon.mod.common.api.battles.model.actor.EntityBackedBattleActor
import com.cobblemon.mod.common.api.events.CobblemonEvents
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor
import com.kaizzinho.battleslider.client.config.BattleSliderConfig
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.minecraft.client.MinecraftClient
import net.minecraft.entity.LivingEntity
import org.slf4j.LoggerFactory
import java.util.UUID

@Environment(EnvType.CLIENT)
object BattleHandler {

    private val LOGGER =
        LoggerFactory.getLogger("battleslider/BattleHandler")

    fun register() {
        CobblemonEvents.BATTLE_STARTED_POST.subscribe { event ->
            onBattleStart(
                event.battle.battleId,
                event.battle.actors.toList()
            )
        }
    }

    var activeBattleId: UUID? = null
        private set

    private fun onBattleStart(
        battleId: UUID,
        actors: List<BattleActor>
    ) {
        if (!BattleSliderConfig.enableBattleIntros) {
            activeBattleId = null
            return
        }

        val client = MinecraftClient.getInstance()

        val localPlayer = client.player ?: run {
            activeBattleId = null
            return
        }

        val localActor = actors
            .filterIsInstance<PlayerBattleActor>()
            .firstOrNull { actor ->
                localPlayer.uuid in actor.getPlayerUUIDs()
            }
            ?: run {
                activeBattleId = null
                return
            }

        val opponentActor =
            actors.firstOrNull { it !== localActor }
                ?: run {
                    activeBattleId = null
                    return
                }

        val isPvP = opponentActor is PlayerBattleActor

        if (
            isPvP &&
            !BattleSliderConfig.pvpBattleIntros
        ) {
            activeBattleId = null
            return
        }


        if (
            opponentActor.type != ActorType.WILD &&
            !isPvP &&
            !BattleSliderConfig.trainerBattleIntros
        ) {
            activeBattleId = null
            return
        }

        val opponentEntity =
            resolveOpponentEntity(opponentActor)


// boss rules win before special wild rules
        val wildBoss =
            if (
                opponentActor.type == ActorType.WILD &&
                BattleSliderConfig.wildBossBattleIntros
            ) {
                WildBossesCompat.resolve(
                    opponentActor,
                    opponentEntity
                )
            } else {
                null
            }

        var specialWild =
            if (
                opponentActor.type == ActorType.WILD &&
                wildBoss == null
            ) {
                SpecialWildPokemonResolver.resolve(
                    opponentActor,
                    opponentEntity
                )
            } else {
                null
            }

        specialWild =
            specialWild?.takeIf { presentation ->
                when (presentation.role) {
                    SpecialWildPokemonResolver.Role.LEGENDARY ->
                        BattleSliderConfig.legendaryBattleIntros

                    SpecialWildPokemonResolver.Role.MYTHICAL ->
                        BattleSliderConfig.mythicalBattleIntros
                }
            }

        if (
            opponentActor.type == ActorType.WILD &&
            wildBoss == null &&
            specialWild == null
        ) {
            activeBattleId = null
            return
        }

        activeBattleId = battleId
        wildBoss?.let(WildBossesCompat::debugBoss)

        debugLog(
            "Starting Battleslider intro: battleId={}, localActor={}, opponentActor={}, opponentType={}, wildBoss={}, specialWild={}",
            battleId,
            localActor.javaClass.name,
            opponentActor.javaClass.name,
            opponentActor.type,
            wildBoss?.tierName ?: "<none>",
            specialWild?.role ?: "<none>"
        )

        BattleIntroOverlay.trigger(
            localActor,
            opponentActor,
            wildBoss,
            specialWild
        )
    }

    fun resolveOpponentEntity(
        opponentActor: BattleActor
    ): LivingEntity? {
        val client = MinecraftClient.getInstance()
        val world = client.world ?: return null

        if (opponentActor is PlayerBattleActor) {
            val uuid =
                opponentActor.getPlayerUUIDs()
                    .firstOrNull()
                    ?: return null

            return world.players
                .firstOrNull { it.uuid == uuid }
        }

        val actorEntity =
            (
                opponentActor as? EntityBackedBattleActor<*>
            )?.entity as? LivingEntity

        if (actorEntity != null) {
            val byEntityId =
                world.getEntityById(actorEntity.id)
                    as? LivingEntity

            if (byEntityId != null) {
                debugLog(
                    "Resolved opponent through EntityBackedBattleActor entity ID: actor={}, entity={}",
                    opponentActor.javaClass.name,
                    byEntityId.javaClass.name
                )
                return byEntityId
            }

            val byActorEntityUuid =
                world.entities
                    .filterIsInstance<LivingEntity>()
                    .firstOrNull {
                        it.uuid == actorEntity.uuid
                    }

            if (byActorEntityUuid != null) {
                debugLog(
                    "Resolved opponent through EntityBackedBattleActor entity UUID: actor={}, entity={}",
                    opponentActor.javaClass.name,
                    byActorEntityUuid.javaClass.name
                )
                return byActorEntityUuid
            }
        }

        val fallbackUuid =
            actorEntity?.uuid ?: opponentActor.uuid

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

    private fun debugLog(
        message: String,
        vararg args: Any?
    ) {
        if (BattleSliderConfig.debugLogging) {
            LOGGER.info(message, *args)
        }
    }
}
