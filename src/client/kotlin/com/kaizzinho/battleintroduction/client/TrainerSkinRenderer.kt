package com.kaizzinho.battleintroduction.client

import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.network.AbstractClientPlayerEntity
import net.minecraft.client.render.entity.EntityRenderer
import net.minecraft.client.util.SkinTextures
import net.minecraft.entity.LivingEntity
import net.minecraft.util.Identifier

@Environment(EnvType.CLIENT)
object TrainerSkinRenderer {

    enum class Pose { PLAYER, OPPONENT }


// flat skin fallback keeps the pose stable
    fun render(
        drawContext: DrawContext,
        skinId: Identifier?,
        pose: Pose,
        x: Int,
        barTop: Int,
        barBottom: Int,
        entity: LivingEntity
    ) {
        val texture = skinId ?: resolveTexture(entity) ?: return
        val slim = (entity as? AbstractClientPlayerEntity)
            ?.skinTextures
            ?.model == SkinTextures.Model.SLIM

        val barH = barBottom - barTop
        val halfW = (barH * 0.9f).toInt()
        drawContext.enableScissor(x - halfW, barTop, x + halfW, barBottom)


        val headSize = 8
        val bodyW = 8
        val bodyH = 12
        val armW = if (slim) 3 else 4
        val contentH = headSize + bodyH
        val contentW = armW + bodyW + armW


        val pixelScale = (barH * 0.85f) / contentH

        val drawW = contentW * pixelScale
        val drawH = contentH * pixelScale
        val startX = x - drawW / 2f
        val startY = barTop + (barH - drawH) / 2f

        val headPx = (headSize * pixelScale).toInt()
        val bodyWPx = (bodyW * pixelScale).toInt()
        val bodyHPx = (bodyH * pixelScale).toInt()
        val armWPx = (armW * pixelScale).toInt()

        val bodyX = (startX + armW * pixelScale).toInt()
        val headX = bodyX + (bodyWPx - headPx) / 2
        val headY = startY.toInt()
        val bodyY = headY + headPx


        val rightArmX = bodyX - armWPx
        val leftArmX = bodyX + bodyWPx


        blit(drawContext, texture, rightArmX, bodyY, armWPx, bodyHPx, 44f, 20f, armW, bodyH)
        blit(drawContext, texture, rightArmX, bodyY, armWPx, bodyHPx, 44f, 36f, armW, bodyH)


        blit(drawContext, texture, leftArmX, bodyY, armWPx, bodyHPx, 36f, 52f, armW, bodyH)
        blit(drawContext, texture, leftArmX, bodyY, armWPx, bodyHPx, 52f, 52f, armW, bodyH)


        blit(drawContext, texture, bodyX, bodyY, bodyWPx, bodyHPx, 20f, 20f, bodyW, bodyH)
        blit(drawContext, texture, bodyX, bodyY, bodyWPx, bodyHPx, 20f, 36f, bodyW, bodyH)


        blit(drawContext, texture, headX, headY, headPx, headPx, 8f, 8f, headSize, headSize)
        blit(drawContext, texture, headX, headY, headPx, headPx, 40f, 8f, headSize, headSize)

        drawContext.disableScissor()
    }

    private fun blit(
        ctx: DrawContext,
        texture: Identifier,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        u: Float,
        v: Float,
        regionW: Int,
        regionH: Int
    ) {
        ctx.drawTexture(texture, x, y, w, h, u, v, regionW, regionH, 64, 64)
    }


    @Suppress("UNCHECKED_CAST")
    private fun resolveTexture(entity: LivingEntity): Identifier? {
        return try {
            val client = MinecraftClient.getInstance()
            val renderer = client.entityRenderDispatcher
                .getRenderer(entity) as? EntityRenderer<LivingEntity>
            renderer?.getTexture(entity)
        } catch (_: Exception) {
            null
        }
    }
}
