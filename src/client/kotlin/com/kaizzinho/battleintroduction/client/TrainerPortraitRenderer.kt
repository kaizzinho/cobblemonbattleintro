package com.kaizzinho.battleintroduction.client

import com.kaizzinho.battleintroduction.client.config.BattleIntroductionConfig
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.widget.PlayerSkinWidget
import net.minecraft.client.network.AbstractClientPlayerEntity
import net.minecraft.client.render.entity.EntityRenderer
import net.minecraft.client.render.entity.model.PlayerEntityModel
import net.minecraft.client.util.SkinTextures
import net.minecraft.entity.LivingEntity
import net.minecraft.util.Identifier
import org.slf4j.LoggerFactory
import java.lang.reflect.Modifier
import java.util.LinkedHashMap
import java.util.UUID
import java.util.function.Supplier
import kotlin.math.abs
import kotlin.math.roundToInt

@Environment(EnvType.CLIENT)
object TrainerPortraitRenderer {

    private val LOGGER = LoggerFactory.getLogger("battleintroduction/TrainerPortrait")
    private const val MAX_WIDGET_CACHE = 12

// cache keeps the skin widgets cheap
    private data class WidgetKey(
        val texture: Identifier,
        val model: SkinTextures.Model,
        val pose: TrainerSkinRenderer.Pose,
        val width: Int,
        val height: Int
    )

    private val widgetCache = object : LinkedHashMap<WidgetKey, ControlledSkinWidget>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<WidgetKey, ControlledSkinWidget>): Boolean =
            size > MAX_WIDGET_CACHE
    }
    private val failed3d = mutableSetOf<WidgetKey>()
    private val logged3d = mutableSetOf<UUID>()
    private val loggedFallback = mutableSetOf<WidgetKey>()

    fun render(
        ctx: DrawContext,
        skinId: Identifier?,
        pose: TrainerSkinRenderer.Pose,
        centerX: Int,
        barTop: Int,
        barBottom: Int,
        entity: LivingEntity
    ) {
        when (BattleIntroductionConfig.trainerPortraitMode) {
            BattleIntroductionConfig.TrainerPortraitMode.DISABLED ->
                return

            BattleIntroductionConfig.TrainerPortraitMode.TWO_D_ONLY -> {
                TrainerSkinRenderer.render(
                    ctx,
                    skinId,
                    pose,
                    centerX,
                    barTop,
                    barBottom,
                    entity
                )
            }

            BattleIntroductionConfig.TrainerPortraitMode.THREE_D_PREFERRED -> {
                if (
                    !render3d(
                        ctx,
                        skinId,
                        pose,
                        centerX,
                        barTop,
                        barBottom,
                        entity
                    )
                ) {
                    TrainerSkinRenderer.render(
                        ctx,
                        skinId,
                        pose,
                        centerX,
                        barTop,
                        barBottom,
                        entity
                    )
                }
            }
        }
    }

    private fun render3d(
        ctx: DrawContext,
        skinId: Identifier?,
        pose: TrainerSkinRenderer.Pose,
        centerX: Int,
        barTop: Int,
        barBottom: Int,
        entity: LivingEntity
    ): Boolean {
        val player = entity as? AbstractClientPlayerEntity
        val isTrainer = player == null && RctTrainerMetadataResolver.isTrainerEntity(entity)
        if (player == null && !isTrainer) return false

        val skin = if (player != null) {
            player.skinTextures
        } else {
            val texture = skinId ?: resolveTexture(entity) ?: return false
            SkinTextures(texture, null, null, null, SkinTextures.Model.WIDE, true)
        }

        val barHeight = (barBottom - barTop).coerceAtLeast(1)
        val widgetWidth = (barHeight * 1.28f).roundToInt().coerceAtLeast(72)
        val widgetHeight = (barHeight * 1.52f).roundToInt().coerceAtLeast(96)
        val key = WidgetKey(skin.texture, skin.model, pose, widgetWidth, widgetHeight)
        if (key in failed3d) return false

        val widget = widgetCache.getOrPut(key) {
            ControlledSkinWidget(widgetWidth, widgetHeight, skin, pose)
        }
        if (!widget.upperBodyReady) {
            failed3d.add(key)
            if (loggedFallback.add(key)) {
                LOGGER.warn(
                    "[TrainerPortrait] Could not prepare upper-body model for {}; using 2D skin fallback",
                    entity.name.string
                )
            }
            return false
        }

        val widgetX = centerX - widgetWidth / 2
        val widgetY = barTop + (barHeight * 0.005f).roundToInt()
        val clipHalfWidth = (barHeight * 0.82f).roundToInt().coerceAtLeast(36)

        return try {
            ctx.enableScissor(centerX - clipHalfWidth, barTop, centerX + clipHalfWidth, barBottom)
            widget.setPosition(widgetX, widgetY)
            widget.render(ctx, Int.MIN_VALUE, Int.MIN_VALUE, 0f)

            if (BattleIntroductionConfig.debugLogging && logged3d.add(entity.uuid)) {
                val mode = if (player != null) "player" else "trainer"
                LOGGER.info(
                    "[TrainerPortrait] 3D mode={} name={} model={} slot={}x{}",
                    mode,
                    entity.name.string,
                    skin.model,
                    widgetWidth,
                    widgetHeight
                )
            }
            true
        } catch (t: Throwable) {
            failed3d.add(key)
            if (loggedFallback.add(key)) {
                LOGGER.warn(
                    "[TrainerPortrait] 3D render failed for {}; using 2D skin fallback: {}",
                    entity.name.string,
                    t.toString()
                )
            }
            false
        } finally {
            ctx.disableScissor()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun resolveTexture(entity: LivingEntity): Identifier? = try {
        val renderer = MinecraftClient.getInstance().entityRenderDispatcher
            .getRenderer(entity) as? EntityRenderer<LivingEntity>
        renderer?.getTexture(entity)
    } catch (_: Throwable) {
        null
    }

    private class ControlledSkinWidget(
        width: Int,
        height: Int,
        skin: SkinTextures,
        pose: TrainerSkinRenderer.Pose
    ) : PlayerSkinWidget(
        width,
        height,
        MinecraftClient.getInstance().entityModelLoader,
        Supplier { skin }
    ) {
        val upperBodyReady: Boolean

        init {
            applyPresentationPose(pose)
            upperBodyReady = prepareUpperBodyModels()
        }

        private fun applyPresentationPose(pose: TrainerSkinRenderer.Pose) {
            val rotationFields = PlayerSkinWidget::class.java.declaredFields
                .filter { field ->
                    field.type == Float::class.javaPrimitiveType &&
                        !Modifier.isStatic(field.modifiers) &&
                        field.trySetAccessible()
                }

            val before = rotationFields.associateWith { field ->
                runCatching { field.getFloat(this) }.getOrNull()
            }


            onDrag(0.0, 0.0, -16.0, 0.0)

            if (pose != TrainerSkinRenderer.Pose.PLAYER) return

            val yawField = rotationFields
                .mapNotNull { field ->
                    val oldValue = before[field] ?: return@mapNotNull null
                    val newValue = runCatching { field.getFloat(this) }.getOrNull()
                        ?: return@mapNotNull null
                    field to abs(newValue - oldValue)
                }
                .maxByOrNull { it.second }
                ?.takeIf { it.second > 0.0001f }
                ?.first

            if (yawField != null) {
                val opponentYaw = runCatching { yawField.getFloat(this) }.getOrNull()
                if (opponentYaw != null) {
                    runCatching { yawField.setFloat(this, -opponentYaw) }
                        .onSuccess { return }
                }
            }


            onDrag(0.0, 0.0, 8.0, 0.0)
        }

        private fun prepareUpperBodyModels(): Boolean {
            val models = linkedSetOf<PlayerEntityModel<*>>()

            for (field in PlayerSkinWidget::class.java.declaredFields) {
                if (!field.trySetAccessible()) continue
                val value = runCatching { field.get(this) }.getOrNull() ?: continue

                if (value is PlayerEntityModel<*>) {
                    models += value
                    continue
                }

                for (nested in value.javaClass.declaredFields) {
                    if (!PlayerEntityModel::class.java.isAssignableFrom(nested.type)) continue
                    if (!nested.trySetAccessible()) continue
                    val model = runCatching { nested.get(value) }.getOrNull() as? PlayerEntityModel<*>
                    if (model != null) models += model
                }
            }

            for (model in models) {
                hideLowerBody(model)
            }

            return models.isNotEmpty()
        }

        private fun hideLowerBody(model: PlayerEntityModel<*>) {
            model.leftLeg.visible = false
            model.leftLeg.hidden = true
            model.rightLeg.visible = false
            model.rightLeg.hidden = true
            model.leftPants.visible = false
            model.leftPants.hidden = true
            model.rightPants.visible = false
            model.rightPants.hidden = true
        }
    }
}
