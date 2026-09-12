package com.kaizzinho.battleintroduction.client

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.texture.NativeImage
import org.slf4j.LoggerFactory
import java.util.Collections
import kotlin.math.min
import kotlin.math.roundToInt


object PokemonSpriteRenderer {

    private val logger =
        LoggerFactory.getLogger(
            "battleintroduction/PokemonSpriteRenderer"
        )

    private val failedTextures =
        Collections.synchronizedSet(mutableSetOf<String>())

    private val measuredFrames =
        Collections.synchronizedMap(
            mutableMapOf<String, SpriteFrame>()
        )

    private data class SpriteFrame(
        val textureWidth: Int,
        val textureHeight: Int,
        val contentU: Int,
        val contentV: Int,
        val contentWidth: Int,
        val contentHeight: Int
    )

    fun render(
        ctx: DrawContext,
        sprite: PokemonSpriteResolver.ResolvedSprite,
        centerX: Int,
        barTop: Int,
        barBottom: Int
    ): Boolean {
        val barHeight =
            (barBottom - barTop).coerceAtLeast(1)

        val outerPadding =
            (barHeight / 18).coerceAtLeast(3)

        val innerPadding =
            (barHeight / 16).coerceAtLeast(4)

        val slotHalfWidth =
            (barHeight * 0.95f)
                .roundToInt()
                .coerceAtLeast(28)

        val slotLeft = centerX - slotHalfWidth
        val slotRight = centerX + slotHalfWidth
        val slotTop = barTop + outerPadding
        val slotBottom = barBottom - outerPadding

        val screenWidth =
            MinecraftClient.getInstance()
                .window
                .scaledWidth

        val clipLeft = slotLeft.coerceAtLeast(0)
        val clipRight =
            slotRight.coerceAtMost(screenWidth)
        val clipTop = slotTop.coerceAtLeast(barTop)
        val clipBottom = slotBottom.coerceAtMost(barBottom)

        if (clipLeft >= clipRight || clipTop >= clipBottom) {
            return true
        }

        val frame = loadFrame(sprite) ?: return false

        val availableWidth =
            (slotRight - slotLeft - innerPadding * 2)
                .coerceAtLeast(1)

        val availableHeight =
            (slotBottom - slotTop - innerPadding * 2)
                .coerceAtLeast(1)


// fit visible pixels so wide sprites stay in the slot
        val scale = min(
            availableWidth.toFloat() / frame.contentWidth.toFloat(),
            availableHeight.toFloat() / frame.contentHeight.toFloat()
        ) * 0.96f

        val drawW =
            (frame.contentWidth * scale)
                .roundToInt()
                .coerceAtLeast(1)

        val drawH =
            (frame.contentHeight * scale)
                .roundToInt()
                .coerceAtLeast(1)

        val drawX = centerX - drawW / 2
        val drawY =
            slotTop +
                (slotBottom - slotTop - drawH) / 2

        return try {
            ctx.enableScissor(
                clipLeft,
                clipTop,
                clipRight,
                clipBottom
            )

            ctx.drawTexture(
                sprite.texture,
                drawX,
                drawY,
                drawW,
                drawH,
                frame.contentU.toFloat(),
                frame.contentV.toFloat(),
                frame.contentWidth,
                frame.contentHeight,
                frame.textureWidth,
                frame.textureHeight
            )

            true
        } catch (error: Throwable) {
            val key = sprite.texture.toString()

            if (failedTextures.add(key)) {
                logger.warn(
                    "[PokemonSprite] 2D render failed for dex={} shiny={} texture={}: {}",
                    sprite.dexNumber,
                    sprite.shiny,
                    sprite.texture,
                    error.message
                        ?: error.javaClass.simpleName
                )
            }

            false
        } finally {
            ctx.disableScissor()
        }
    }

    private fun loadFrame(
        sprite: PokemonSpriteResolver.ResolvedSprite
    ): SpriteFrame? {
        val key = sprite.texture.toString()

        measuredFrames[key]?.let { cached ->
            return cached
        }

        val resource =
            MinecraftClient.getInstance()
                .resourceManager
                .getResource(sprite.texture)
                .orElse(null)
                ?: return null

        return try {
            resource.inputStream.use { stream ->
                val image = NativeImage.read(stream)
                image.use {
                    val frame = measureOpaqueBounds(it)
                    measuredFrames[key] = frame
                    frame
                }
            }
        } catch (error: Throwable) {
            if (failedTextures.add(key)) {
                logger.warn(
                    "[PokemonSprite] Failed to inspect sprite bounds for dex={} shiny={} texture={}: {}",
                    sprite.dexNumber,
                    sprite.shiny,
                    sprite.texture,
                    error.message ?: error.javaClass.simpleName
                )
            }
            null
        }
    }

    private fun measureOpaqueBounds(
        image: NativeImage
    ): SpriteFrame {
        val textureWidth = image.width.coerceAtLeast(1)
        val textureHeight = image.height.coerceAtLeast(1)

        var minX = textureWidth
        var minY = textureHeight
        var maxX = -1
        var maxY = -1

        for (y in 0 until textureHeight) {
            for (x in 0 until textureWidth) {
                val alpha = image.getOpacity(x, y).toInt() and 0xFF
                if (alpha != 0) {
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                }
            }
        }

        if (maxX < minX || maxY < minY) {
            return SpriteFrame(
                textureWidth = textureWidth,
                textureHeight = textureHeight,
                contentU = 0,
                contentV = 0,
                contentWidth = textureWidth,
                contentHeight = textureHeight
            )
        }

        return SpriteFrame(
            textureWidth = textureWidth,
            textureHeight = textureHeight,
            contentU = minX,
            contentV = minY,
            contentWidth = (maxX - minX + 1).coerceAtLeast(1),
            contentHeight = (maxY - minY + 1).coerceAtLeast(1)
        )
    }
}
