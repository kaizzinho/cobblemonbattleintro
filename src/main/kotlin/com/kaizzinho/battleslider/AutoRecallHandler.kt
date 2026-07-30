package com.kaizzinho.battleslider

import com.cobblemon.mod.common.api.battles.model.actor.BattleActor
import com.cobblemon.mod.common.api.events.CobblemonEvents

/**
 * Server-side cleanup for Pokémon that are already active in the world when a
 * trainer or PvP battle begins.
 *
 * BATTLE_STARTED_PRE is already late enough for Cobblemon to associate active
 * Pokémon with the incoming battle. Because of that, checking entity.battleId
 * can incorrectly reject the exact wandering companion we need to recall.
 *
 * Any party Pokémon that still has an active entity at this point is recalled.
 * Pokémon that are already inactive are left untouched.
 */
object AutoRecallHandler {

    fun register() {
        CobblemonEvents.BATTLE_STARTED_PRE.subscribe { event ->
            event.battle.actors.forEach(::recallActivePokemon)
        }
    }

    private fun recallActivePokemon(actor: BattleActor) {
        actor.pokemonList.forEach { battlePokemon ->
            val pokemon = battlePokemon.effectedPokemon

            // Pokemon.entity is only non-null while its state is active in-world.
            // Do not gate this on entity.battleId: Cobblemon may have assigned the
            // incoming battle ID before this PRE subscriber is invoked.
            if (pokemon.entity != null) {
                pokemon.recall()
            }
        }
    }
}