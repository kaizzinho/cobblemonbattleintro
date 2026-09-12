package com.kaizzinho.battleintroduction

import com.cobblemon.mod.common.api.events.CobblemonEvents
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor
import org.slf4j.LoggerFactory


object AutoRecallHandler {

    private val LOGGER =
        LoggerFactory.getLogger("battleintroduction/AutoRecallHandler")

    fun register() {
        CobblemonEvents.BATTLE_STARTED_PRE.subscribe { event ->


            event.battle.actors
                .filterIsInstance<PlayerBattleActor>()
                .forEach(::recallSafePlayerPokemon)
        }
    }

    private fun recallSafePlayerPokemon(actor: PlayerBattleActor) {
        actor.pokemonList.forEach { battlePokemon ->
            val pokemon = battlePokemon.effectedPokemon
            val entity = pokemon.entity ?: return@forEach


            if (entity.isRemoved) {
                return@forEach
            }


// never recall a mon carrying passengers
            if (entity.hasPassengers()) {
                LOGGER.debug(
                    "Skipping auto-recall for mounted Pokémon {} (entityId={}, passengers={})",
                    pokemon.uuid,
                    entity.id,
                    entity.passengerList.size
                )
                return@forEach
            }

            try {
                pokemon.recall()
            } catch (e: Exception) {


                LOGGER.warn(
                    "Could not auto-recall Pokémon {} before battle: {}",
                    pokemon.uuid,
                    e.message,
                    e
                )
            }
        }
    }
}
