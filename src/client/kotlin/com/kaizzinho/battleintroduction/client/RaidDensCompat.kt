package com.kaizzinho.battleintroduction.client

import com.cobblemon.mod.common.api.battles.model.actor.ActorType
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor
import com.cobblemon.mod.common.api.battles.model.actor.EntityBackedBattleActor
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.kaizzinho.battleintroduction.client.config.BattleIntroductionConfig
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.entity.LivingEntity
import org.slf4j.LoggerFactory
import java.lang.reflect.Method
import java.util.UUID

object RaidDensCompat {
    private const val MOD_ID = "cobblemonraiddens"
    private const val ACCESSOR_CLASS =
        "com.necro.raid.dens.common.util.IRaidAccessor"
    private const val RAID_HELPER_CLASS =
        "com.necro.raid.dens.common.raids.helpers.RaidHelper"

    private val logger =
        LoggerFactory.getLogger("battleintroduction/RaidDensCompat")

    private var compatibilityFailureLogged = false

    data class RaidPresentation(
        val entity: PokemonEntity,
        val stars: String,
        val typeColorRgb: Int
    ) {
        val speciesName: String
            get() = entity.pokemon.species.name

        val level: Int
            get() = entity.pokemon.level
    }

    private data class AccessorMethods(
        val accessorClass: Class<*>,
        val isRaidBoss: Method,
        val getRaidBoss: Method,
        val getRaidId: Method
    )

    private data class Candidate(
        val source: String,
        val entity: PokemonEntity
    )

    private val accessorMethods: AccessorMethods? by lazy {
        if (!FabricLoader.getInstance().isModLoaded(MOD_ID)) {
            return@lazy null
        }

        runCatching {
            val accessor = Class.forName(ACCESSOR_CLASS)

            AccessorMethods(
                accessorClass = accessor,
                isRaidBoss = accessor.getMethod("crd_isRaidBoss"),
                getRaidBoss = accessor.getMethod("crd_getRaidBoss"),
                getRaidId = accessor.getMethod("crd_getRaidId")
            )
        }.getOrElse {
            logCompatibilityFailure(it)
            null
        }
    }

    fun resolve(
        actor: BattleActor,
        clientEntity: LivingEntity?
    ): RaidPresentation? {
        if (
            actor.type != ActorType.WILD ||
            !FabricLoader.getInstance().isModLoaded(MOD_ID)
        ) {
            return null
        }

        val renderEntity =
            clientEntity as? PokemonEntity
                ?: return null

        val actorEntity =
            (
                actor as? EntityBackedBattleActor<*>
            )?.entity as? PokemonEntity

        val candidates =
            buildList {
                if (actorEntity != null) {
                    add(
                        Candidate(
                            source = "actor_entity",
                            entity = actorEntity
                        )
                    )
                }

                if (
                    actorEntity == null ||
                    actorEntity !== renderEntity
                ) {
                    add(
                        Candidate(
                            source = "client_entity",
                            entity = renderEntity
                        )
                    )
                }
            }

        val methods =
            accessorMethods
                ?: return null

        candidates.forEach { candidate ->
            val resolved =
                resolveCandidate(
                    candidate = candidate,
                    renderEntity = renderEntity,
                    methods = methods
                )

            if (resolved != null) {
                return resolved
            }
        }

        debugLog(
            "[RaidDensCompat] no raid match actor={} actorEntityId={} clientEntityId={} clientSpecies={} clientAspects={}",
            actor.javaClass.name,
            actorEntity?.id ?: -1,
            renderEntity.id,
            renderEntity.pokemon.species.name,
            renderEntity.pokemon.aspects
        )

        return null
    }

    private fun resolveCandidate(
        candidate: Candidate,
        renderEntity: PokemonEntity,
        methods: AccessorMethods
    ): RaidPresentation? {
        val entity =
            candidate.entity

        if (!methods.accessorClass.isInstance(entity)) {
            debugLog(
                "[RaidDensCompat] candidate source={} entityId={} accessor=false species={} aspects={}",
                candidate.source,
                entity.id,
                entity.pokemon.species.name,
                entity.pokemon.aspects
            )
            return null
        }

        return runCatching {
            val isRaidBoss =
                methods.isRaidBoss.invoke(entity) as? Boolean
                    ?: false

            val raidId =
                methods.getRaidId.invoke(entity) as? UUID

            var raidBoss =
                methods.getRaidBoss.invoke(entity)

            if (
                raidBoss == null &&
                raidId != null
            ) {
                raidBoss =
                    findRaidBossFromActiveRaid(raidId)
            }

            debugLog(
                "[RaidDensCompat] candidate source={} entityId={} accessor=true isRaidBoss={} raidId={} raidBoss={} species={} aspects={}",
                candidate.source,
                entity.id,
                isRaidBoss,
                raidId,
                raidBoss?.javaClass?.name ?: "<none>",
                entity.pokemon.species.name,
                entity.pokemon.aspects
            )

            if (
                !isRaidBoss ||
                raidBoss == null
            ) {
                return@runCatching null
            }

            buildPresentation(
                renderEntity = renderEntity,
                raidBoss = raidBoss,
                source = candidate.source
            )
        }.getOrElse {
            logCompatibilityFailure(it)
            null
        }
    }

    private fun buildPresentation(
        renderEntity: PokemonEntity,
        raidBoss: Any,
        source: String
    ): RaidPresentation? {
        val tier =
            raidBoss.javaClass
                .getMethod("getTier")
                .invoke(raidBoss)
                ?: return null

        val stars =
            (
                tier.javaClass
                    .getMethod("getStars")
                    .invoke(tier) as? String
            )
                ?.takeIf { it.isNotBlank() }
                ?: "★"

        val raidType =
            raidBoss.javaClass
                .getMethod("getType")
                .invoke(raidBoss)

        val typeColor =
            (raidType?.javaClass
                ?.getMethod("getColor")
                ?.invoke(raidType) as? Number)
                ?.toInt()
                ?: 0xC8B400

        debugLog(
            "[RaidDensCompat] resolved source={} stars={} species={} level={} color=0x{} renderEntityId={}",
            source,
            stars,
            renderEntity.pokemon.species.name,
            renderEntity.pokemon.level,
            "%06X".format(typeColor and 0xFFFFFF),
            renderEntity.id
        )

        return RaidPresentation(
            entity = renderEntity,
            stars = stars,
            typeColorRgb = typeColor and 0xFFFFFF
        )
    }

    private fun findRaidBossFromActiveRaid(
        raidId: UUID
    ): Any? {
        return runCatching {
            val raidHelper =
                Class.forName(RAID_HELPER_CLASS)

            val activeRaids =
                raidHelper
                    .getField("ACTIVE_RAIDS")
                    .get(null) as? Map<*, *>
                    ?: return@runCatching null

            val raidInstance =
                activeRaids[raidId]
                    ?: return@runCatching null

            raidInstance.javaClass
                .getMethod("getRaidBoss")
                .invoke(raidInstance)
        }.getOrNull()
    }

    private fun logCompatibilityFailure(error: Throwable) {
        if (compatibilityFailureLogged) {
            return
        }

        compatibilityFailureLogged = true

        logger.warn(
            "Cobblemon Raid Dens integration disabled: {}",
            error.cause?.message
                ?: error.message
                ?: error.javaClass.simpleName
        )
    }

    fun debugRaid(raid: RaidPresentation) {
        if (!BattleIntroductionConfig.debugLogging) {
            return
        }

        logger.info(
            "[RaidDensCompat] raid detected stars={} species={} level={} color=0x{} entity={}",
            raid.stars,
            raid.speciesName,
            raid.level,
            "%06X".format(raid.typeColorRgb),
            raid.entity.uuid
        )
    }

    private fun debugLog(
        message: String,
        vararg args: Any?
    ) {
        if (BattleIntroductionConfig.debugLogging) {
            logger.info(message, *args)
        }
    }
}
