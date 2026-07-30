package com.kaizzinho.battleslider.client

import com.cobblemon.mod.common.api.events.CobblemonEvents
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.minecraft.client.MinecraftClient

@Environment(EnvType.CLIENT)
object BattleHandler {

    fun register() {
        CobblemonEvents.BATTLE_STARTED_POST.subscribe { event ->
            activeBattleId = event.battle.battleId
            onBattleStart(event.battle.actors.toList())
        }
    }

    var activeBattleId: java.util.UUID? = null
        private set

    private fun onBattleStart(actors: List<BattleActor>) {
        val client = MinecraftClient.getInstance()
        val localPlayer = client.player ?: return

        // Find which actor belongs to the local player
        val localActor = actors
            .filterIsInstance<PlayerBattleActor>()
            .firstOrNull { actor ->
                actor.getPlayerUUIDs().contains(localPlayer.uuid)
            } ?: return  // local player isn't in this battle (spectator etc.)

        val opponentActor = actors.firstOrNull { it != localActor } ?: return

        // Skip wild Pokémon battles — animation only for trainers and PvP
        if (opponentActor.type == com.cobblemon.mod.common.api.battles.model.actor.ActorType.WILD) return

        BattleIntroOverlay.trigger(localActor, opponentActor)


    }
    fun resolveOpponentEntity(opponentActor: BattleActor): net.minecraft.entity.LivingEntity? {
        val client = MinecraftClient.getInstance()
        val world = client.world ?: return null

        // Case 1: PvP — opponent is another player
        if (opponentActor is PlayerBattleActor) {
            val uuid = opponentActor.getPlayerUUIDs().firstOrNull() ?: return null
            return world.players.firstOrNull { it.uuid == uuid }
        }

        // Case 2: RCT Trainer NPC — scan loaded entities by UUID
        // The actor's UUID on BattleActor base class matches the trainer entity's UUID
        return world.entities
            .filterIsInstance<net.minecraft.entity.LivingEntity>()
            .firstOrNull { it.uuid == opponentActor.uuid }
    }
}