package com.kaizzinho.battleintroduction

import com.cobblemon.mod.common.api.battles.model.PokemonBattle
import com.cobblemon.mod.common.api.battles.model.actor.ActorType
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor
import com.cobblemon.mod.common.api.battles.model.actor.EntityBackedBattleActor
import com.cobblemon.mod.common.api.events.CobblemonEvents
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.entity.LivingEntity
import net.minecraft.registry.Registries
import org.slf4j.LoggerFactory
import java.lang.reflect.Method
import java.util.Locale
import java.util.UUID

object BattleIntroServerBridge {

    private const val WILDBOSSES_MOD_ID = "wildbosses"
    private const val RAID_DENS_MOD_ID = "cobblemonraiddens"
    private const val RAID_ACCESSOR_CLASS =
        "com.necro.raid.dens.common.util.IRaidAccessor"
    private const val RAID_HELPER_CLASS =
        "com.necro.raid.dens.common.raids.helpers.RaidHelper"
    private const val WILDBOSS_API_CLASS =
        "com.kaizzinho.wildbosses.api.WildBossIntegrationApi"
    private const val DEFAULT_TRAINER_BALL_ID =
        "cobblemon:poke_ball"

    private val logger =
        LoggerFactory.getLogger(
            "battleintroduction/ServerBridge"
        )

    private var registered = false

    private data class ServerPresentation(
        val kind: BattleIntroKind,
        val opponentEntity: LivingEntity?,
        val opponentPokemonEntity: PokemonEntity?,
        val opponentName: String,
        val detail: String = "",
        val level: Int = 0,
        val colorRgb: Int = 0,
        val fallbackPartySize: Int = 0
    )

    private data class WildBossApi(
        val isBoss: Method,
        val getTierName: Method,
        val getScaledLevel: Method?
    )

    private data class RaidAccessorApi(
        val accessorClass: Class<*>,
        val isRaidBoss: Method,
        val getRaidBoss: Method,
        val getRaidId: Method
    )

    private val wildBossApi: WildBossApi? by lazy {
        loadWildBossApi()
    }

    private val raidAccessorApi: RaidAccessorApi? by lazy {
        loadRaidAccessorApi()
    }

    fun register() {
        if (registered) {
            return
        }

        registered = true

        CobblemonEvents.BATTLE_STARTED_POST.subscribe { event ->
            sendBattleDescriptors(event.battle)
        }
    }

    private fun sendBattleDescriptors(
        battle: PokemonBattle
    ) {
        battle.actors
            .filterIsInstance<PlayerBattleActor>()
            .forEach { localActor ->
                sendDescriptor(
                    battle,
                    localActor
                )
            }
    }

    private fun sendDescriptor(
        battle: PokemonBattle,
        localActor: PlayerBattleActor
    ) {
        val player =
            localActor.entity
                ?: return

        if (
            !ServerPlayNetworking.canSend(
                player,
                BattleIntroStartPayload.ID
            )
        ) {
            return
        }

        val opponentActor =
            findOpponentActor(localActor)
                ?: return
        val presentation =
            classify(opponentActor)
                ?: return
        val payload =
            buildPayload(
                battleId = battle.battleId,
                opponentActor = opponentActor,
                presentation = presentation
            )

        ServerPlayNetworking.send(
            player,
            payload
        )

        logger.debug(
            "Sent intro descriptor battle={} player={} kind={} opponent={} detail={}",
            battle.battleId,
            player.gameProfile.name,
            payload.kind,
            payload.opponentName,
            payload.detail
        )
    }

    private fun findOpponentActor(
        localActor: PlayerBattleActor
    ): BattleActor? =
        localActor
            .getSide()
            .getOppositeSide()
            .actors
            .firstOrNull()

    private fun buildPayload(
        battleId: UUID,
        opponentActor: BattleActor,
        presentation: ServerPresentation
    ): BattleIntroStartPayload =
        BattleIntroStartPayload(
            battleId = battleId,
            kind = presentation.kind,
            opponentActorUuid = opponentActor.uuid,
            opponentEntityUuid = presentation.opponentEntity?.uuid,
            opponentPokemonUuid =
                presentation.opponentPokemonEntity
                    ?.pokemon
                    ?.uuid,
            opponentName = presentation.opponentName,
            detail = presentation.detail,
            level = presentation.level,
            colorRgb = presentation.colorRgb,
            opponentPokemonUuids =
                opponentPokemonUuids(opponentActor),
            opponentBallItemIds =
                opponentBallItemIds(
                    opponentActor,
                    presentation
                )
        )

    private fun opponentPokemonUuids(
        actor: BattleActor
    ): List<UUID> =
        actor.pokemonList
            .take(6)
            .map { it.uuid }

    private fun opponentBallItemIds(
        actor: BattleActor,
        presentation: ServerPresentation
    ): List<String> {
        val pokemon =
            actor.pokemonList
                .take(6)
        val resolved =
            pokemon
                .mapNotNull { battlePokemon ->
                    runCatching {
                        Registries.ITEM.getId(
                            battlePokemon
                                .effectedPokemon
                                .caughtBall
                                .item()
                        ).toString()
                    }.getOrNull()
                }

        if (presentation.kind != BattleIntroKind.TRAINER) {
            return resolved
        }

        val expectedSize =
            maxOf(
                pokemon.size,
                presentation.fallbackPartySize
            ).coerceIn(0, 6)

        if (expectedSize <= resolved.size) {
            return resolved
        }

        return resolved +
            List(expectedSize - resolved.size) {
                DEFAULT_TRAINER_BALL_ID
            }
    }

    private fun classify(
        opponentActor: BattleActor
    ): ServerPresentation? =
        when {
            opponentActor is PlayerBattleActor ->
                playerPresentation(opponentActor)

            opponentActor.type != ActorType.WILD ->
                trainerPresentation(opponentActor)

            else ->
                wildPresentation(opponentActor)
        }

    private fun playerPresentation(
        actor: BattleActor
    ): ServerPresentation =
        ServerPresentation(
            kind = BattleIntroKind.PVP,
            opponentEntity = actorEntity(actor),
            opponentPokemonEntity = null,
            opponentName =
                "Pokémon Trainer ${actor.getName().string}"
        )

    private fun trainerPresentation(
        actor: BattleActor
    ): ServerPresentation {
        val entity = actorEntity(actor)
        val rctMetadata =
            RctServerTrainerMetadata.resolve(entity)
        val actorName = actor.getName().string
        val opponentName =
            rctMetadata
                ?.displayName
                ?.takeIf { it.isNotBlank() }
                ?: actorName

        if (rctMetadata != null) {
            logger.debug(
                "Resolved RCT intro metadata trainerId={} name={} partySize={} actorPartySize={}",
                rctMetadata.trainerId,
                opponentName,
                rctMetadata.partySize,
                actor.pokemonList.size
            )
        }

        return ServerPresentation(
            kind = BattleIntroKind.TRAINER,
            opponentEntity = entity,
            opponentPokemonEntity = null,
            opponentName = opponentName,
            fallbackPartySize =
                rctMetadata
                    ?.partySize
                    ?: 0
        )
    }

    private fun wildPresentation(
        actor: BattleActor
    ): ServerPresentation? {
        val entity =
            actorEntity(actor) as? PokemonEntity
                ?: return null

        resolveRaid(entity)?.let {
            return it
        }

        resolveWildBoss(entity)?.let {
            return it
        }

        return resolveSpecialWild(entity)
    }

    private fun actorEntity(
        actor: BattleActor
    ): LivingEntity? =
        (
            actor as? EntityBackedBattleActor<*>
        )?.entity as? LivingEntity

    private fun resolveSpecialWild(
        entity: PokemonEntity
    ): ServerPresentation? {
        val pokemon = entity.pokemon
        val kind =
            specialKind(
                pokemon.species.labels
            ) ?: return null

        return ServerPresentation(
            kind = kind,
            opponentEntity = entity,
            opponentPokemonEntity = entity,
            opponentName = pokemon.species.name,
            detail =
                pokemon.primaryType
                    .showdownId
                    .lowercase(Locale.ROOT),
            level = pokemon.level
        )
    }

    private fun specialKind(
        labels: Set<String>
    ): BattleIntroKind? =
        when {
            labels.contains("mythical") ->
                BattleIntroKind.MYTHICAL

            labels.contains("legendary") ->
                BattleIntroKind.LEGENDARY

            else ->
                null
        }

    private fun resolveWildBoss(
        entity: PokemonEntity
    ): ServerPresentation? {
        val api = wildBossApi ?: return null

        return runCatching {
            resolveWildBoss(
                entity,
                api
            )
        }.onFailure {
            logger.debug(
                "WildBosses server classification failed: {}",
                failureMessage(it)
            )
        }.getOrNull()
    }

    private fun resolveWildBoss(
        entity: PokemonEntity,
        api: WildBossApi
    ): ServerPresentation? {
        val isBoss =
            api.isBoss.invoke(
                null,
                entity
            ) as? Boolean
                ?: false

        if (!isBoss) {
            return null
        }

        return ServerPresentation(
            kind = BattleIntroKind.WILD_BOSS,
            opponentEntity = entity,
            opponentPokemonEntity = entity,
            opponentName = entity.pokemon.species.name,
            detail = bossTier(api, entity),
            level = bossLevel(api, entity)
        )
    }

    private fun bossTier(
        api: WildBossApi,
        entity: PokemonEntity
    ): String =
        (
            api.getTierName.invoke(
                null,
                entity
            ) as? String
        )
            ?.takeIf { it.isNotBlank() }
            ?.uppercase(Locale.ROOT)
            ?: "BOSS"

    private fun bossLevel(
        api: WildBossApi,
        entity: PokemonEntity
    ): Int {
        val reflected =
            api.getScaledLevel
                ?.let { method ->
                    runCatching {
                        method.invoke(
                            null,
                            entity.uuid
                        )
                    }.getOrNull()
                } as? Number

        return reflected
            ?.toInt()
            ?.takeIf { it > 0 }
            ?: entity.pokemon.level
    }

    private fun resolveRaid(
        entity: PokemonEntity
    ): ServerPresentation? {
        val api =
            raidAccessorApi
                ?: return null

        if (!api.accessorClass.isInstance(entity)) {
            return null
        }

        return runCatching {
            resolveRaid(
                entity,
                api
            )
        }.onFailure {
            logger.debug(
                "Raid Dens server classification failed: {}",
                failureMessage(it)
            )
        }.getOrNull()
    }

    private fun resolveRaid(
        entity: PokemonEntity,
        api: RaidAccessorApi
    ): ServerPresentation? {
        val isRaidBoss =
            api.isRaidBoss.invoke(entity)
                as? Boolean
                ?: false

        if (!isRaidBoss) {
            return null
        }

        val raidBoss =
            resolveRaidBoss(
                entity,
                api
            ) ?: return null

        return buildRaidPresentation(
            entity,
            raidBoss
        )
    }

    private fun resolveRaidBoss(
        entity: PokemonEntity,
        api: RaidAccessorApi
    ): Any? {
        val direct =
            api.getRaidBoss.invoke(entity)

        if (direct != null) {
            return direct
        }

        val raidId =
            api.getRaidId.invoke(entity)
                as? UUID
                ?: return null

        return findRaidBoss(raidId)
    }

    private fun buildRaidPresentation(
        entity: PokemonEntity,
        raidBoss: Any
    ): ServerPresentation? {
        val tier =
            raidBoss.javaClass
                .getMethod("getTier")
                .invoke(raidBoss)
                ?: return null
        val stars =
            tier.javaClass
                .getMethod("getStars")
                .invoke(tier) as? String
                ?: "★"
        val raidType =
            raidBoss.javaClass
                .getMethod("getType")
                .invoke(raidBoss)
        val color =
            raidTypeColor(raidType)

        return ServerPresentation(
            kind = BattleIntroKind.RAID,
            opponentEntity = entity,
            opponentPokemonEntity = entity,
            opponentName = entity.pokemon.species.name,
            detail = stars.ifBlank { "★" },
            level = entity.pokemon.level,
            colorRgb = color
        )
    }

    private fun raidTypeColor(
        raidType: Any?
    ): Int =
        (
            raidType?.javaClass
                ?.getMethod("getColor")
                ?.invoke(raidType) as? Number
        )
            ?.toInt()
            ?.and(0xFFFFFF)
            ?: 0xC8B400

    private fun findRaidBoss(
        raidId: UUID
    ): Any? =
        runCatching {
            val helper =
                Class.forName(
                    RAID_HELPER_CLASS
                )
            val activeRaids =
                helper
                    .getField("ACTIVE_RAIDS")
                    .get(null) as? Map<*, *>
                    ?: return@runCatching null
            val instance =
                activeRaids[raidId]
                    ?: return@runCatching null

            instance.javaClass
                .getMethod("getRaidBoss")
                .invoke(instance)
        }.getOrNull()

    private fun loadWildBossApi(): WildBossApi? {
        if (!isLoaded(WILDBOSSES_MOD_ID)) {
            return null
        }

        return runCatching {
            val api =
                Class.forName(
                    WILDBOSS_API_CLASS
                )

            WildBossApi(
                isBoss =
                    api.getMethod(
                        "isBoss",
                        PokemonEntity::class.java
                    ),
                getTierName =
                    api.getMethod(
                        "getTierName",
                        PokemonEntity::class.java
                    ),
                getScaledLevel =
                    runCatching {
                        api.getMethod(
                            "getScaledLevel",
                            UUID::class.java
                        )
                    }.getOrNull()
            )
        }.onFailure {
            logger.warn(
                "Could not initialize WildBosses server integration: {}",
                failureMessage(it)
            )
        }.getOrNull()
    }

    private fun loadRaidAccessorApi(): RaidAccessorApi? {
        if (!isLoaded(RAID_DENS_MOD_ID)) {
            return null
        }

        return runCatching {
            val accessor =
                Class.forName(
                    RAID_ACCESSOR_CLASS
                )

            RaidAccessorApi(
                accessorClass = accessor,
                isRaidBoss =
                    accessor.getMethod(
                        "crd_isRaidBoss"
                    ),
                getRaidBoss =
                    accessor.getMethod(
                        "crd_getRaidBoss"
                    ),
                getRaidId =
                    accessor.getMethod(
                        "crd_getRaidId"
                    )
            )
        }.onFailure {
            logger.warn(
                "Could not initialize Raid Dens server integration: {}",
                failureMessage(it)
            )
        }.getOrNull()
    }

    private fun isLoaded(
        modId: String
    ): Boolean =
        FabricLoader.getInstance()
            .isModLoaded(modId)

    private fun failureMessage(
        error: Throwable
    ): String =
        error.cause?.message
            ?: error.message
            ?: error.javaClass.simpleName
}
