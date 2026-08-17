package com.kaizzinho.battleintroduction.client

import com.cobblemon.mod.common.api.battles.model.actor.ActorType
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.kaizzinho.battleintroduction.client.config.BattleIntroductionConfig
import net.minecraft.entity.LivingEntity
import org.slf4j.LoggerFactory
import java.util.Locale


object SpecialWildPokemonResolver {

    private val LOGGER =
        LoggerFactory.getLogger("battleintroduction/SpecialWildPokemonResolver")

    enum class Role(val displayName: String) {
        LEGENDARY("Legendary"),
        MYTHICAL("Mythical")
    }

    data class Presentation(
        val entity: PokemonEntity,
        val role: Role,
        val primaryTypeId: String,
        val baseColorRgb: Int
    ) {
        val speciesName: String
            get() = entity.pokemon.species.name

        val level: Int
            get() = entity.pokemon.level
    }

// uses cobblemon labels so custom packs can join in
    fun resolve(
        actor: BattleActor,
        entity: LivingEntity?
    ): Presentation? {
        if (actor.type != ActorType.WILD) return null

        val pokemonEntity = entity as? PokemonEntity ?: return null
        val pokemon = pokemonEntity.pokemon
        val labels = pokemon.species.labels


        val role = when {
            labels.contains("mythical") -> Role.MYTHICAL
            labels.contains("legendary") -> Role.LEGENDARY
            else -> return null
        }


        val primaryTypeId = pokemon.primaryType.showdownId
            .lowercase(Locale.ROOT)

        val presentation = Presentation(
            entity = pokemonEntity,
            role = role,
            primaryTypeId = primaryTypeId,
            baseColorRgb = colorForPrimaryType(primaryTypeId)
        )

        if (BattleIntroductionConfig.debugLogging) {
            LOGGER.info(
                "[SpecialWild] role={} species={} level={} primaryType={} color=0x{} entity={}",
                presentation.role,
                presentation.speciesName,
                presentation.level,
                presentation.primaryTypeId,
                "%06X".format(
                    Locale.ROOT,
                    presentation.baseColorRgb and 0xFFFFFF
                ),
                pokemonEntity.uuid
            )
        }

        return presentation
    }


    fun colorForPrimaryType(typeId: String): Int = when (
        typeId.lowercase(Locale.ROOT)
    ) {
        "normal" -> 0xA8A77A
        "fire" -> 0xE7602B
        "water" -> 0x285FC7
        "electric" -> 0xE7BE24
        "grass" -> 0x55A94F
        "ice" -> 0x63C7CF
        "fighting" -> 0xBE3B32
        "poison" -> 0x9946A8
        "ground" -> 0xC18A43
        "flying" -> 0x7C74D1
        "psychic" -> 0xE6507D
        "bug" -> 0x93A81C
        "rock" -> 0xA58D35
        "ghost" -> 0x6650A1
        "dragon" -> 0x5A3BE0
        "dark" -> 0x574A43
        "steel" -> 0x7F8CA5
        "fairy" -> 0xD46EA8
        else -> 0xC8B400
    }
}
