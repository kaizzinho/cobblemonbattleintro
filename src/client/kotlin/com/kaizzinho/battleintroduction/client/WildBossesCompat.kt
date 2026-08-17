package com.kaizzinho.battleintroduction.client

import com.cobblemon.mod.common.api.battles.model.actor.ActorType
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.kaizzinho.battleintroduction.client.config.BattleIntroductionConfig
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.entity.LivingEntity
import org.slf4j.LoggerFactory
import java.lang.reflect.Method

object WildBossesCompat {
    private const val MOD_ID = "wildbosses"
    private const val API_CLASS = "com.kaizzinho.wildbosses.api.WildBossIntegrationApi"
    private val logger = LoggerFactory.getLogger("battleintroduction/WildBossesCompat")
    private var compatibilityFailureLogged = false

    data class BossPresentation(
        val entity: PokemonEntity,
        val tierName: String,
        val scaledLevelOverride: Int?
    ) {
        val speciesName: String
            get() = entity.pokemon.species.name

        val level: Int
            get() = scaledLevelOverride?.takeIf { it > 0 } ?: entity.pokemon.level
    }

    private data class ApiMethods(
        val isBoss: Method,
        val getTierName: Method,
        val getScaledLevelByUuid: Method?
    )

    private val apiMethods: ApiMethods? by lazy {
        if (!FabricLoader.getInstance().isModLoaded(MOD_ID)) return@lazy null

        runCatching {
            val api = Class.forName(API_CLASS)
            ApiMethods(
                isBoss = api.getMethod("isBoss", PokemonEntity::class.java),
                getTierName = api.getMethod("getTierName", PokemonEntity::class.java),
                getScaledLevelByUuid = runCatching {
                    api.getMethod("getScaledLevel", java.util.UUID::class.java)
                }.getOrNull()
            )
        }.getOrElse {
            logCompatibilityFailure(it)
            null
        }
    }

    fun resolve(actor: BattleActor, entity: LivingEntity?): BossPresentation? {
        if (actor.type != ActorType.WILD) return null
        val pokemonEntity = entity as? PokemonEntity ?: return null
        val methods = apiMethods ?: return null

        return runCatching {
            val isBoss = methods.isBoss.invoke(null, pokemonEntity) as? Boolean ?: false
            if (!isBoss) return@runCatching null

            val tierName = (methods.getTierName.invoke(null, pokemonEntity) as? String)
                ?.takeIf { it.isNotBlank() }
                ?: "BOSS"

            val scaledLevelOverride = methods.getScaledLevelByUuid
                ?.let { method -> runCatching { method.invoke(null, pokemonEntity.uuid) }.getOrNull() }
                .let { value -> (value as? Number)?.toInt() }
                ?.takeIf { it > 0 }

            BossPresentation(pokemonEntity, tierName.uppercase(), scaledLevelOverride)
        }.getOrElse {
            logCompatibilityFailure(it)
            null
        }
    }

    private fun logCompatibilityFailure(error: Throwable) {
        if (compatibilityFailureLogged) return
        compatibilityFailureLogged = true
        logger.warn(
            "WildBosses integration disabled: {}",
            error.cause?.message ?: error.message ?: error.javaClass.simpleName
        )
    }

    fun debugBoss(boss: BossPresentation) {
        if (!BattleIntroductionConfig.debugLogging) return
        logger.info(
            "[BossCompat] WildBoss detected tier={} species={} level={} entity={}",
            boss.tierName,
            boss.speciesName,
            boss.level,
            boss.entity.uuid
        )
    }
}
