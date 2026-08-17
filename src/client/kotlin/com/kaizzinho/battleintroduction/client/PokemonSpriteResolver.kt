package com.kaizzinho.battleintroduction.client

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import net.minecraft.client.MinecraftClient
import net.minecraft.util.Identifier


object PokemonSpriteResolver {

    const val TEXTURE_SIZE = 96

    private const val NAMESPACE = "battleintroduction"
    private const val LEGACY_NAMESPACE = "battleslider"
    private const val FRONT_DIRECTORY = "textures/pokemon/front"
    private const val SHINY_DIRECTORY = "textures/pokemon/shiny"

    data class ResolvedSprite(
        val texture: Identifier,
        val dexNumber: Int,
        val shiny: Boolean
    )

    data class ResourcePackAvailability(
        val normalSpriteCount: Int,
        val shinySpriteCount: Int
    ) {
        val hasAnySprites: Boolean
            get() = normalSpriteCount > 0 || shinySpriteCount > 0
    }

    fun resolve(entity: PokemonEntity): ResolvedSprite? {
        val pokemon = entity.pokemon
        return resolve(
            dexNumber = pokemon.species.nationalPokedexNumber,
            shiny = pokemon.shiny
        )
    }

    fun resolve(
        dexNumber: Int,
        shiny: Boolean
    ): ResolvedSprite? {
        if (dexNumber <= 0) {
            return null
        }

        val resourceManager =
            MinecraftClient.getInstance().resourceManager

        for (texture in candidateIds(dexNumber, shiny)) {
            if (resourceManager.getResource(texture).isPresent) {
                return ResolvedSprite(
                    texture = texture,
                    dexNumber = dexNumber,
                    shiny = shiny
                )
            }
        }

        return null
    }


// checks live resources so any matching pack can work
    fun inspectResourcePackAvailability(): ResourcePackAvailability {
        val resourceManager =
            MinecraftClient.getInstance().resourceManager

        val normal = resourceManager.findResources(FRONT_DIRECTORY) { id ->
            isCompatibleSpritePath(id, FRONT_DIRECTORY)
        }

        val shiny = resourceManager.findResources(SHINY_DIRECTORY) { id ->
            isCompatibleSpritePath(id, SHINY_DIRECTORY)
        }

        return ResourcePackAvailability(
            normalSpriteCount = normal.size,
            shinySpriteCount = shiny.size
        )
    }

    fun hasAnyCompatibleSpriteResources(): Boolean =
        inspectResourcePackAvailability().hasAnySprites

    private fun candidateIds(
        dexNumber: Int,
        shiny: Boolean
    ): List<Identifier> {
        val directory =
            if (shiny) SHINY_DIRECTORY else FRONT_DIRECTORY

        val ordinaryName = "$dexNumber.png"
        val paddedName =
            dexNumber.toString().padStart(4, '0') + ".png"

        return listOf(
            Identifier.of(
                NAMESPACE,
                "$directory/$ordinaryName"
            ),
            Identifier.of(
                NAMESPACE,
                "$directory/$paddedName"
            ),
            Identifier.of(
                LEGACY_NAMESPACE,
                "$directory/$ordinaryName"
            ),
            Identifier.of(
                LEGACY_NAMESPACE,
                "$directory/$paddedName"
            )
        ).distinct()
    }

    private fun isCompatibleSpritePath(
        id: Identifier,
        directory: String
    ): Boolean {
        if (id.namespace != NAMESPACE && id.namespace != LEGACY_NAMESPACE) {
            return false
        }

        val prefix = "$directory/"
        if (!id.path.startsWith(prefix)) {
            return false
        }

        val filename = id.path.removePrefix(prefix)


        if ('/' in filename || !filename.endsWith(".png")) {
            return false
        }

        val numericPart = filename.removeSuffix(".png")
        return numericPart.isNotEmpty() &&
            numericPart.all(Char::isDigit)
    }
}
