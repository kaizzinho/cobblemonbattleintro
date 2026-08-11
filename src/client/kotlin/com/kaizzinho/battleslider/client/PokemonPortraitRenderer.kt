package com.kaizzinho.battleslider.client

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.kaizzinho.battleslider.client.config.BattleSliderConfig
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.ingame.InventoryScreen
import org.slf4j.LoggerFactory
import java.util.Collections
import kotlin.math.min
import kotlin.math.roundToInt


object PokemonPortraitRenderer {

    private val logger =
        LoggerFactory.getLogger(
            "battleslider/PokemonPortraitRenderer"
        )

    private val loggedFits =
        Collections.synchronizedSet(
            mutableSetOf<java.util.UUID>()
        )


// once model render fails keep using the fallback
    private val failed3dEntities =
        Collections.synchronizedSet(
            mutableSetOf<java.util.UUID>()
        )

    private val missingFallbackEntities =
        Collections.synchronizedSet(
            mutableSetOf<java.util.UUID>()
        )

// tries the picked portrait mode and fails soft
    fun render(
        ctx: DrawContext,
        entity: PokemonEntity,
        centerX: Int,
        barTop: Int,
        barBottom: Int
    ): Boolean {
        return when (
            BattleSliderConfig.pokemonPortraitMode
        ) {
            BattleSliderConfig.PokemonPortraitMode.DISABLED ->
                false

            BattleSliderConfig.PokemonPortraitMode.AUTOMATIC ->
                render3dThen2d(
                    ctx,
                    entity,
                    centerX,
                    barTop,
                    barBottom
                )

            BattleSliderConfig.PokemonPortraitMode.THREE_D_ONLY ->
                render3dIfAvailable(
                    ctx,
                    entity,
                    centerX,
                    barTop,
                    barBottom
                )

            BattleSliderConfig.PokemonPortraitMode.TWO_D_PREFERRED ->
                render2dIfAvailable(
                    ctx,
                    entity,
                    centerX,
                    barTop,
                    barBottom
                ) ||
                    render3dIfAvailable(
                        ctx,
                        entity,
                        centerX,
                        barTop,
                        barBottom
                    )

            BattleSliderConfig.PokemonPortraitMode.TWO_D_ONLY ->
                render2dIfAvailable(
                    ctx,
                    entity,
                    centerX,
                    barTop,
                    barBottom
                )
        }.also { rendered ->
            if (
                !rendered &&
                BattleSliderConfig.debugLogging &&
                missingFallbackEntities.add(entity.uuid)
            ) {
                logger.info(
                    "[PokemonPortrait] No usable portrait for species={} dex={} shiny={} mode={}; intro will continue without a Pokémon portrait",
                    entity.pokemon.species.name,
                    entity.pokemon.species.nationalPokedexNumber,
                    entity.pokemon.shiny,
                    BattleSliderConfig.pokemonPortraitMode
                )
            }
        }
    }

    private fun render3dThen2d(
        ctx: DrawContext,
        entity: PokemonEntity,
        centerX: Int,
        barTop: Int,
        barBottom: Int
    ): Boolean =
        render3dIfAvailable(
            ctx,
            entity,
            centerX,
            barTop,
            barBottom
        ) ||
            render2dIfAvailable(
                ctx,
                entity,
                centerX,
                barTop,
                barBottom
            )

    private fun render3dIfAvailable(
        ctx: DrawContext,
        entity: PokemonEntity,
        centerX: Int,
        barTop: Int,
        barBottom: Int
    ): Boolean {
        if (entity.uuid in failed3dEntities) {
            return false
        }

        if (
            tryRender3d(
                ctx,
                entity,
                centerX,
                barTop,
                barBottom
            )
        ) {
            return true
        }

        failed3dEntities.add(entity.uuid)
        return false
    }

    private fun render2dIfAvailable(
        ctx: DrawContext,
        entity: PokemonEntity,
        centerX: Int,
        barTop: Int,
        barBottom: Int
    ): Boolean {
        val sprite =
            PokemonSpriteResolver.resolve(entity)
                ?: return false

        return PokemonSpriteRenderer.render(
            ctx,
            sprite,
            centerX,
            barTop,
            barBottom
        )
    }


    private fun tryRender3d(
        ctx: DrawContext,
        entity: PokemonEntity,
        centerX: Int,
        barTop: Int,
        barBottom: Int
    ): Boolean {
        return try {
            val barHeight =
                (barBottom - barTop).coerceAtLeast(1)

            val padding =
                (barHeight / 18).coerceAtLeast(3)

            val slotHalfWidth =
                (barHeight * 0.95f)
                    .roundToInt()
                    .coerceAtLeast(28)

            val slotLeft = centerX - slotHalfWidth
            val slotRight = centerX + slotHalfWidth
            val slotTop = barTop + padding
            val slotBottom = barBottom - padding

            val screenWidth =
                MinecraftClient.getInstance()
                    .window
                    .scaledWidth

            val clipLeft =
                slotLeft.coerceAtLeast(0)

            val clipRight =
                slotRight.coerceAtMost(screenWidth)

            if (clipLeft >= clipRight) {
                return true
            }

            val modelWidth =
                entity.width.coerceAtLeast(0.15f)

            val modelHeight =
                entity.height.coerceAtLeast(0.15f)

            val availableWidth =
                (
                    slotRight -
                        slotLeft -
                        padding * 2
                    ).coerceAtLeast(1)

            val availableHeight =
                (
                    slotBottom -
                        slotTop -
                        padding * 2
                    ).coerceAtLeast(1)

            val widthFit =
                availableWidth / modelWidth

            val heightFit =
                availableHeight / modelHeight

            val maxTinyPokemonScale =
                barHeight * 1.50f

            val scale =
                (
                    min(widthFit, heightFit) *
                        0.82f
                    )
                    .coerceAtMost(
                        maxTinyPokemonScale
                    )
                    .coerceAtLeast(2f)
                    .roundToInt()

            if (
                BattleSliderConfig.debugLogging &&
                loggedFits.add(entity.uuid)
            ) {
                logger.info(
                    "[PokemonPortrait] species={} width={} height={} scale={} slot={}x{}",
                    entity.pokemon.species.name,
                    "%.2f".format(modelWidth),
                    "%.2f".format(modelHeight),
                    scale,
                    slotRight - slotLeft,
                    slotBottom - slotTop
                )
            }

            val centerXf =
                (slotLeft + slotRight) / 2f

            val centerYf =
                (slotTop + slotBottom) / 2f

            val inwardLook =
                (barHeight * 0.18f)
                    .coerceIn(14f, 28f)

            ctx.enableScissor(
                clipLeft,
                barTop,
                clipRight,
                barBottom
            )

            try {
                InventoryScreen.drawEntity(
                    ctx,
                    slotLeft,
                    slotTop,
                    slotRight,
                    slotBottom,
                    scale,
                    0.0f,
                    centerXf - inwardLook,
                    centerYf,
                    entity
                )
            } finally {
                ctx.disableScissor()
            }

            true
        } catch (error: Throwable) {
            runCatching {
                ctx.disableScissor()
            }

            logger.warn(
                "[PokemonPortrait] 3D render failed for {} (dex={} shiny={}): {}",
                entity.pokemon.species.name,
                entity.pokemon.species.nationalPokedexNumber,
                entity.pokemon.shiny,
                error.message
                    ?: error.javaClass.simpleName
            )

            false
        }
    }
}
