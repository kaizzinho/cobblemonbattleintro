package com.kaizzinho.battleslider

import com.cobblemon.mod.common.api.battles.model.actor.BattleActor
import com.cobblemon.mod.common.api.events.CobblemonEvents

/**
 * Common (not client-only) module -- this runs server-side too, which is
 * required since recalling a Pokemon is server-authoritative game state.
 *
 * Hooks BATTLE_STARTED_PRE (fires before the battle's own send-out packet
 * sequence begins) and force-recalls any Pokemon that's already actively
 * spawned in the world for either participant (e.g. a companion Pokemon
 * walking beside its trainer) -- so every battle start goes through a clean
 * recall-then-send-out cycle, guaranteeing the throw animation always has
 * something to actually throw.
 *
 * Safety check: only recalls entities whose battleId is still null. A
 * Pokemon that's genuinely about to be sent INTO this battle would already
 * have its battleId set by the time this fires (if that assumption turns
 * out to be wrong in practice, this is the one guard to revisit).
 *
 * Register this from your common (not client-only) mod initializer, e.g.
 * inside Battleslider.kt's onInitialize().
 */
object AutoRecallHandler {

    fun register() {
        CobblemonEvents.BATTLE_STARTED_PRE.subscribe { event ->
            event.battle.actors.forEach { actor -> recallStrayPokemon(actor) }
        }
    }

    private fun recallStrayPokemon(actor: BattleActor) {
        actor.pokemonList.forEach { battlePokemon ->
            val pokemon = battlePokemon.effectedPokemon
            val entity = pokemon.entity
            if (entity != null && entity.battleId == null) {
                pokemon.recall()
            }
        }
    }
}
