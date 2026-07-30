package com.kaizzinho.battleslider.client

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

    /**
     * Renders a fully static "paper doll" portrait built directly from the
     * entity's skin texture -- head, body, and both arms, front-facing only
     * -- instead of rendering the actual 3D entity model.
     *
     * Why: after several rounds fighting EntityRenderDispatcher/model-render
     * internals (undocumented scissor/scale/rotation behavior, live
     * animation state bleeding through, and eventually a render that
     * produced nothing at all), it became clear the 3D pipeline is the
     * wrong tool here. A player/trainer skin PNG already contains a fixed,
     * pre-baked front-facing "pose" as UV-mapped rectangles (this is just
     * how the standard 64x64 skin format works) -- we only need to sample
     * those rectangles and draw them as flat 2D quads next to each other.
     * This is fully static by construction (it's a texture file, there's no
     * "live" entity state to bleed through), always front-facing (we only
     * ever sample the front UV regions), and uses only drawTexture calls we
     * already fully understand from the bar rendering itself.
     *
     * RCT trainers use this exact same code path: their renderer (confirmed
     * via decompile) is a plain PlayerEntityRenderer + PlayerEntityModel, so
     * their skin PNGs already follow the same standard layout as a real
     * player skin.
     *
     * NOTE: assumes the modern 64x64 skin format (separate left/right
     * arms/legs). Legacy 64x32 skins aren't handled -- extremely rare in
     * practice at this point, but flag it if you hit one.
     *
     * x                   = horizontal center of the character's portrait slot
     * barTop / barBottom  = the bar's vertical bounds (hard clip region)
     */
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
        val slim = (entity as? AbstractClientPlayerEntity)?.skinTextures?.model == SkinTextures.Model.SLIM

        val barH = barBottom - barTop
        val halfW = (barH * 0.9f).toInt()
        drawContext.enableScissor(x - halfW, barTop, x + halfW, barBottom)

        // -- Skin-pixel-space dimensions (standard 64x64 skin layout) --
        val headSize = 8
        val bodyW = 8
        val bodyH = 12
        val armW = if (slim) 3 else 4
        val contentH = headSize + bodyH               // 20
        val contentW = armW + bodyW + armW             // 16 (wide) or 14 (slim)

        // One number to change if the portrait reads too big/small relative
        // to the bar.
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
        val rightArmX = startX.toInt()
        val leftArmX = bodyX + bodyWPx

        // Right leg / left leg omitted deliberately -- a bust portrait
        // (head + torso + arms) matches the classic VS-screen framing
        // better than a full body crammed into a short bar.

        // Right arm (base + sleeve overlay)
        blit(drawContext, texture, rightArmX, bodyY, armWPx, bodyHPx, 44f, 20f, armW, bodyH)
        blit(drawContext, texture, rightArmX, bodyY, armWPx, bodyHPx, 44f, 36f, armW, bodyH)

        // Left arm (base + sleeve overlay)
        blit(drawContext, texture, leftArmX, bodyY, armWPx, bodyHPx, 36f, 52f, armW, bodyH)
        blit(drawContext, texture, leftArmX, bodyY, armWPx, bodyHPx, 52f, 52f, armW, bodyH)

        // Body (base + jacket overlay)
        blit(drawContext, texture, bodyX, bodyY, bodyWPx, bodyHPx, 20f, 20f, bodyW, bodyH)
        blit(drawContext, texture, bodyX, bodyY, bodyWPx, bodyHPx, 20f, 36f, bodyW, bodyH)

        // Head (base + hat overlay) -- drawn last so it's never occluded
        blit(drawContext, texture, headX, headY, headPx, headPx, 8f, 8f, headSize, headSize)
        blit(drawContext, texture, headX, headY, headPx, headPx, 40f, 8f, headSize, headSize)

        drawContext.disableScissor()
    }

    private fun blit(
        ctx: DrawContext,
        texture: Identifier,
        x: Int, y: Int, w: Int, h: Int,
        u: Float, v: Float, regionW: Int, regionH: Int
    ) {
        ctx.drawTexture(texture, x, y, w, h, u, v, regionW, regionH, 64, 64)
    }

    /** Resolves a skin texture for non-player entities (RCT TrainerMob etc.) via their own renderer. */
    @Suppress("UNCHECKED_CAST")
    private fun resolveTexture(entity: LivingEntity): Identifier? {
        return try {
            val client = MinecraftClient.getInstance()
            val renderer = client.entityRenderDispatcher.getRenderer(entity) as? EntityRenderer<LivingEntity>
            renderer?.getTexture(entity)
        } catch (_: Exception) {
            null
        }
    }
}