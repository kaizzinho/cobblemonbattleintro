package com.kaizzinho.battleintroduction.client.config

import com.kaizzinho.battleintroduction.client.PokemonSpriteResolver
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.client.gui.widget.CyclingButtonWidget
import net.minecraft.text.Text


// vanilla ui keeps mod menu optional
class BattleIntroductionConfigScreen(
    private val parent: Screen?
) : Screen(Text.translatable("battleintroduction.config.title")) {

    private enum class Page(val translationKey: String) {
        GENERAL("battleintroduction.config.tab.general"),
        ENCOUNTERS("battleintroduction.config.tab.encounters"),
        VISUALS("battleintroduction.config.tab.visuals"),
        PORTRAITS("battleintroduction.config.tab.portraits"),
        AUDIO("battleintroduction.config.tab.audio"),
        ADVANCED("battleintroduction.config.tab.advanced")
    }

    private var page = Page.GENERAL
    private var working = BattleIntroductionConfig.snapshot()
    private var saveError = false

    private var panelLeft = 0
    private var panelTop = 0
    private var panelWidth = 0
    private var panelHeight = 0
    private var contentTop = 0

    private var spriteAvailability =
        PokemonSpriteResolver.ResourcePackAvailability(0, 0)

    override fun init() {
        panelWidth =
            (width - 24)
                .coerceAtMost(430)
                .coerceAtLeast(300)

        panelHeight =
            (height - 16)
                .coerceAtMost(248)
                .coerceAtLeast(218)

        panelLeft = (width - panelWidth) / 2
        panelTop = (height - panelHeight) / 2

        addTabs()

        contentTop = panelTop + 62

        when (page) {
            Page.GENERAL -> addGeneralPage()
            Page.ENCOUNTERS -> addEncountersPage()
            Page.VISUALS -> addVisualsPage()
            Page.PORTRAITS -> addPortraitsPage()
            Page.AUDIO -> addAudioPage()
            Page.ADVANCED -> addAdvancedPage()
        }

        addFooterButtons()
    }

    private fun addTabs() {
        val margin = 10
        val gap = 2
        val available = panelWidth - margin * 2
        val tabWidth = (available - gap * (Page.entries.size - 1)) / Page.entries.size
        val tabY = panelTop + 34

        Page.entries.forEachIndexed { index, candidate ->
            val x = panelLeft + margin + index * (tabWidth + gap)

            val button = ButtonWidget.builder(
                Text.translatable(candidate.translationKey)
            ) {
                if (page != candidate) {
                    page = candidate
                    saveError = false
                    clearAndInit()
                }
            }
                .dimensions(x, tabY, tabWidth, 20)
                .build()

            button.active = page != candidate
            addDrawableChild(button)
        }
    }

    private fun addGeneralPage() {
        var row = 0

        addBooleanOption(
            row++,
            "battleintroduction.config.enable_intros",
            working.enableBattleIntros
        ) { working.enableBattleIntros = it }

        addBooleanOption(
            row++,
            "battleintroduction.config.allow_skipping",
            working.allowSkipping
        ) { working.allowSkipping = it }

        addBooleanOption(
            row++,
            "battleintroduction.config.show_skip_prompt",
            working.showSkipPrompt
        ) { working.showSkipPrompt = it }

        addBooleanOption(
            row++,
            "battleintroduction.config.show_names",
            working.showNameBadges
        ) { working.showNameBadges = it }

        addBooleanOption(
            row,
            "battleintroduction.config.show_party_balls",
            working.showPartyBalls
        ) { working.showPartyBalls = it }
    }

    private fun addEncountersPage() {
        var row = 0

        addBooleanOption(
            row++,
            "battleintroduction.config.trainer_intros",
            working.trainerBattleIntros
        ) { working.trainerBattleIntros = it }

        addBooleanOption(
            row++,
            "battleintroduction.config.pvp_intros",
            working.pvpBattleIntros
        ) { working.pvpBattleIntros = it }

        addBooleanOption(
            row++,
            "battleintroduction.config.wildboss_intros",
            working.wildBossBattleIntros
        ) { working.wildBossBattleIntros = it }

        addBooleanOption(
            row++,
            "battleintroduction.config.legendary_intros",
            working.legendaryBattleIntros
        ) { working.legendaryBattleIntros = it }

        addBooleanOption(
            row,
            "battleintroduction.config.mythical_intros",
            working.mythicalBattleIntros
        ) { working.mythicalBattleIntros = it }
    }

    private fun addVisualsPage() {
        var row = 0

        addEnumOption(
            row++,
            "battleintroduction.config.animation_speed",
            BattleIntroductionConfig.AnimationSpeed.entries,
            BattleIntroductionConfig.AnimationSpeed.from(working.animationSpeed),
            { value ->
                Text.translatable(
                    "battleintroduction.config.value.animation_speed.${value.configValue}"
                )
            }
        ) { working.animationSpeed = it.configValue }

        addEnumOption(
            row++,
            "battleintroduction.config.flash_intensity",
            BattleIntroductionConfig.FlashIntensity.entries,
            BattleIntroductionConfig.FlashIntensity.from(working.flashIntensity),
            { value ->
                Text.translatable(
                    "battleintroduction.config.value.flash.${value.configValue}"
                )
            }
        ) { working.flashIntensity = it.configValue }

        val holdValues =
            listOf(250, 500, 800, 1000, 1300, 1600, 2000, 2500)

        addEnumOption(
            row++,
            "battleintroduction.config.hold_duration",
            holdValues,
            closestHoldValue(working.holdDurationMs, holdValues),
            { value ->
                Text.literal(
                    if (value % 1000 == 0) {
                        "${value / 1000}s"
                    } else {
                        String.format(
                            java.util.Locale.ROOT,
                            "%.2f",
                            value / 1000.0
                        )
                            .trimEnd('0')
                            .trimEnd('.') + "s"
                    }
                )
            }
        ) { working.holdDurationMs = it }

        addEnumOption(
            row++,
            "battleintroduction.config.particles",
            BattleIntroductionConfig.ParticleDensity.entries,
            BattleIntroductionConfig.ParticleDensity.from(working.particleDensity),
            { value ->
                Text.translatable(
                    "battleintroduction.config.value.particles.${value.configValue}"
                )
            }
        ) { working.particleDensity = it.configValue }
    }

    private fun addPortraitsPage() {
        spriteAvailability =
            runCatching {
                PokemonSpriteResolver.inspectResourcePackAvailability()
            }.getOrDefault(
                PokemonSpriteResolver.ResourcePackAvailability(0, 0)
            )

        var row = 0

        addEnumOption(
            row++,
            "battleintroduction.config.trainer_portraits",
            BattleIntroductionConfig.TrainerPortraitMode.entries,
            BattleIntroductionConfig.TrainerPortraitMode.from(
                working.trainerPortraitMode
            ),
            { value ->
                Text.translatable(
                    "battleintroduction.config.value.trainer_portrait.${value.configValue}"
                )
            }
        ) { working.trainerPortraitMode = it.configValue }

        addEnumOption(
            row,
            "battleintroduction.config.pokemon_portraits",
            BattleIntroductionConfig.PokemonPortraitMode.entries,
            BattleIntroductionConfig.PokemonPortraitMode.from(
                working.pokemonPortraitMode
            ),
            { value ->
                Text.translatable(
                    "battleintroduction.config.value.pokemon_portrait.${value.configValue}"
                )
            }
        ) { working.pokemonPortraitMode = it.configValue }
    }

    private fun addAudioPage() {
        var row = 0

        addBooleanOption(
            row++,
            "battleintroduction.config.ui_sounds",
            working.uiTransitionSounds
        ) { working.uiTransitionSounds = it }

        addBooleanOption(
            row++,
            "battleintroduction.config.team_ball_sound",
            working.teamBallLineupSound
        ) { working.teamBallLineupSound = it }

        val volumes = (0..200 step 25).map { it / 100f }

        addEnumOption(
            row,
            "battleintroduction.config.team_ball_volume",
            volumes,
            closestVolume(working.teamBallLineupVolume, volumes),
            { value ->
                Text.literal("${(value * 100f).toInt()}%")
            }
        ) { working.teamBallLineupVolume = it }
    }

    private fun addAdvancedPage() {
        addBooleanOption(
            0,
            "battleintroduction.config.debug_logging",
            working.debugLogging
        ) { working.debugLogging = it }

        val reset = ButtonWidget.builder(
            Text.translatable("battleintroduction.config.reset_defaults")
        ) {
            val preservedOverrides =
                LinkedHashMap(
                    working.rctTrainerRoleOverrides.orEmpty()
                )

            working =
                BattleIntroductionConfig.defaultValues().copy(
                    rctTrainerRoleOverrides = preservedOverrides
                )

            saveError = false
            clearAndInit()
        }
            .dimensions(
                optionX(),
                rowY(3),
                optionWidth(),
                20
            )
            .build()

        addDrawableChild(reset)
    }

    private fun addFooterButtons() {
        val footerY = panelTop + panelHeight - 27
        val gap = 6
        val buttonWidth = 100
        val total = buttonWidth * 2 + gap
        val startX = width / 2 - total / 2

        addDrawableChild(
            ButtonWidget.builder(
                Text.translatable("battleintroduction.config.save")
            ) {
                saveError =
                    !BattleIntroductionConfig.applyAndSave(working)

                if (!saveError) {
                    client?.setScreen(parent)
                }
            }
                .dimensions(startX, footerY, buttonWidth, 20)
                .build()
        )

        addDrawableChild(
            ButtonWidget.builder(
                Text.translatable("gui.cancel")
            ) {
                client?.setScreen(parent)
            }
                .dimensions(
                    startX + buttonWidth + gap,
                    footerY,
                    buttonWidth,
                    20
                )
                .build()
        )
    }

    private fun addBooleanOption(
        row: Int,
        translationKey: String,
        initial: Boolean,
        setter: (Boolean) -> Unit
    ) {
        val widget =
            CyclingButtonWidget.onOffBuilder(initial)
                .build(
                    optionX(),
                    rowY(row),
                    optionWidth(),
                    20,
                    Text.translatable(translationKey)
                ) { _, value ->
                    setter(value)
                }

        addDrawableChild(widget)
    }

    private fun <T> addEnumOption(
        row: Int,
        translationKey: String,
        values: Collection<T>,
        initial: T,
        valueText: (T) -> Text,
        setter: (T) -> Unit
    ) {
        val widget =
            CyclingButtonWidget.builder<T> { value ->
                valueText(value)
            }
                .values(values)
                .initially(initial)
                .build(
                    optionX(),
                    rowY(row),
                    optionWidth(),
                    20,
                    Text.translatable(translationKey)
                ) { _, value ->
                    setter(value)
                }

        addDrawableChild(widget)
    }

    private fun optionX(): Int =
        panelLeft + 24

    private fun optionWidth(): Int =
        panelWidth - 48

    private fun rowY(row: Int): Int =
        contentTop + row * 23

    private fun closestHoldValue(
        current: Int,
        values: List<Int>
    ): Int =
        values.minByOrNull {
            kotlin.math.abs(it - current)
        } ?: 1300

    private fun closestVolume(
        current: Float,
        values: List<Float>
    ): Float =
        values.minByOrNull {
            kotlin.math.abs(it - current)
        } ?: 1.25f

    override fun render(
        context: DrawContext,
        mouseX: Int,
        mouseY: Int,
        delta: Float
    ) {
        context.fill(
            0,
            0,
            width,
            height,
            0x66090A0E
        )

        context.fill(
            panelLeft,
            panelTop,
            panelLeft + panelWidth,
            panelTop + panelHeight,
            0xE8141418.toInt()
        )
        context.fill(
            panelLeft,
            panelTop,
            panelLeft + panelWidth,
            panelTop + 1,
            0xFFB8B8C8.toInt()
        )
        context.fill(
            panelLeft,
            panelTop + panelHeight - 1,
            panelLeft + panelWidth,
            panelTop + panelHeight,
            0xFF50505C.toInt()
        )

        drawCentered(
            context,
            title,
            panelTop + 10,
            0xFFFFFFFF.toInt(),
            true
        )

        drawCentered(
            context,
            Text.translatable(
                "battleintroduction.config.page.${page.name.lowercase()}"
            ),
            panelTop + 23,
            0xFFB9B9C4.toInt(),
            false
        )

        if (page == Page.PORTRAITS) {
            renderSpritePackStatus(context)
        }

        if (page == Page.ADVANCED) {
            renderAdvancedInfo(context)
        }

        super.render(context, mouseX, mouseY, delta)

        if (saveError) {
            drawCentered(
                context,
                Text.translatable(
                    "battleintroduction.config.save_failed"
                ),
                panelTop + panelHeight - 39,
                0xFFFF5555.toInt(),
                true
            )
        }
    }

    override fun renderBackground(
        context: DrawContext,
        mouseX: Int,
        mouseY: Int,
        delta: Float
    ) {
// no blur so old screens never bleed through
    }

    override fun applyBlur(delta: Float) {
// blur stays off for this screen
    }

    private fun renderSpritePackStatus(context: DrawContext) {
        val y = rowY(3)

        val status = if (spriteAvailability.hasAnySprites) {
            Text.translatable(
                "battleintroduction.config.sprite_pack.detected",
                spriteAvailability.normalSpriteCount,
                spriteAvailability.shinySpriteCount
            )
        } else {
            Text.translatable(
                "battleintroduction.config.sprite_pack.missing"
            )
        }

        drawCentered(
            context,
            status,
            y,
            if (spriteAvailability.hasAnySprites) {
                0xFF55FF55.toInt()
            } else {
                0xFFFFAA00.toInt()
            },
            false
        )

        drawCentered(
            context,
            Text.translatable(
                "battleintroduction.config.sprite_pack.hint"
            ),
            y + 12,
            0xFF9A9AA6.toInt(),
            false
        )
    }

    private fun renderAdvancedInfo(context: DrawContext) {
        val overrideText =
            Text.translatable(
                "battleintroduction.config.rct_overrides",
                working.rctTrainerRoleOverrides.orEmpty().size
            )

        drawCentered(
            context,
            overrideText,
            rowY(1) + 6,
            0xFFAAAAAF.toInt(),
            false
        )

        drawCentered(
            context,
            Text.translatable(
                "battleintroduction.config.rct_overrides_hint"
            ),
            rowY(1) + 18,
            0xFF808088.toInt(),
            false
        )
    }

    private fun drawCentered(
        context: DrawContext,
        text: Text,
        y: Int,
        color: Int,
        shadow: Boolean
    ) {
        val x = width / 2 - textRenderer.getWidth(text) / 2

        context.drawText(
            textRenderer,
            text,
            x,
            y,
            color,
            shadow
        )
    }

    override fun close() {
        client?.setScreen(parent)
    }
}
