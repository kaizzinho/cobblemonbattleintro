package com.kaizzinho.battleslider.client

import com.cobblemon.mod.common.api.battles.model.actor.BattleActor
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.network.AbstractClientPlayerEntity
import net.minecraft.entity.LivingEntity
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.registry.Registries
import net.minecraft.sound.SoundEvent
import net.minecraft.sound.SoundEvents
import net.minecraft.client.sound.PositionedSoundInstance
import net.minecraft.util.Identifier
import com.cobblemon.mod.common.api.scheduling.afterOnClient
import kotlin.math.atan2
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random
import org.slf4j.LoggerFactory
import com.kaizzinho.battleslider.client.config.BattleSliderConfig

@Environment(EnvType.CLIENT)
object BattleIntroOverlay {

    private val LOGGER = LoggerFactory.getLogger("battleslider/BattleIntroOverlay")
    private var registered = false


    private fun debugLog(message: String, vararg args: Any?) {
        if (BattleSliderConfig.debugLogging) {
            LOGGER.info(message, *args)
        }
    }

    enum class State { IDLE, FLICKER, BARS_SLIDE_IN, VS_APPEAR, CHARACTERS_SLIDE_IN, TEAM_BALLS_SLIDE_IN, HOLD, SLIDING_OUT }

    var state = State.IDLE
    private var progress = 0f
    private var lastTimeMs = 0L
    private var isFlushing = false


// keeps the whole intro pace in one spot
    private const val BASE_FLICKER_TOTAL_MS      = 2250L
    private const val BASE_BARS_SLIDE_MS         = 1250L
    private const val BASE_VS_APPEAR_MS          = 850L
    private const val BASE_CHARACTERS_SLIDE_MS   = 1250L
    private const val BASE_TEAM_BALLS_SLIDE_MS   = 650L
    private const val BASE_SLIDE_OUT_DURATION_MS = 1200L

    private fun scaledDuration(baseMs: Long): Long =
        (baseMs * BattleSliderConfig.animationDurationMultiplier)
            .toLong()
            .coerceAtLeast(1L)

    private fun flickerDurationMs(): Long = when (
        BattleSliderConfig.flashIntensity
    ) {
        BattleSliderConfig.FlashIntensity.OFF ->
            1L

        BattleSliderConfig.FlashIntensity.REDUCED ->
            scaledDuration(1100L)

        BattleSliderConfig.FlashIntensity.NORMAL ->
            scaledDuration(BASE_FLICKER_TOTAL_MS)
    }

    private fun barsSlideMs(): Long =
        scaledDuration(BASE_BARS_SLIDE_MS)

    private fun vsAppearMs(): Long =
        scaledDuration(BASE_VS_APPEAR_MS)

    private fun charactersSlideMs(): Long =
        scaledDuration(BASE_CHARACTERS_SLIDE_MS)

    private fun teamBallsSlideMs(): Long =
        if (
            BattleSliderConfig.showPartyBalls ||
            bossPresentation != null ||
            specialWildPresentation != null
        ) {
            scaledDuration(BASE_TEAM_BALLS_SLIDE_MS)
        } else {
            1L
        }

    private fun holdDurationMs(): Long =
        BattleSliderConfig.holdDurationMs
            .coerceAtLeast(1L)

    private fun slideOutDurationMs(): Long =
        scaledDuration(BASE_SLIDE_OUT_DURATION_MS)

    private data class PendingAction(
        val label: String,
        val queuedAtMs: Long,
        val action: () -> Unit
    )


    private val pendingCorePackets = java.util.concurrent.ConcurrentLinkedQueue<PendingAction>()
    private val pendingPlayerPackets = java.util.concurrent.ConcurrentLinkedQueue<PendingAction>()
    private val pendingOpponentPackets = java.util.concurrent.ConcurrentLinkedQueue<PendingAction>()

    private var introStartedAtMs = 0L
    private var debugSequence = 0L

    private val entityOwnership =
        java.util.concurrent.ConcurrentHashMap<Int, Boolean>()


    private data class BattleSpawnInfo(
        val isPlayerOwned: Boolean,
        val x: Double,
        val z: Double
    )

    private val battleSpawnInfo =
        java.util.concurrent.ConcurrentHashMap<Int, BattleSpawnInfo>()

    private const val PLAYER_STAGGER_DELAY_S = 2.5f

    private fun elapsedDebugMs(): Long =
        if (introStartedAtMs == 0L) 0L else System.currentTimeMillis() - introStartedAtMs

    private fun enqueue(
        queueName: String,
        queue: java.util.concurrent.ConcurrentLinkedQueue<PendingAction>,
        label: String,
        action: Runnable
    ) {
        val sequence = ++debugSequence
        val decoratedLabel = "#$sequence $label"
        val queuedAt = System.currentTimeMillis()
        queue.offer(PendingAction(decoratedLabel, queuedAt) { action.run() })
        debugLog(
            "[t+{}ms] QUEUE {} <- {} | core={}, opponent={}, player={}, state={}",
            elapsedDebugMs(),
            queueName,
            decoratedLabel,
            pendingCorePackets.size,
            pendingOpponentPackets.size,
            pendingPlayerPackets.size,
            state
        )
    }

    fun addPendingCorePacket(label: String, action: Runnable) =
        enqueue("CORE", pendingCorePackets, label, action)

    fun addPendingPlayerPacket(label: String, action: Runnable) =
        enqueue("PLAYER", pendingPlayerPackets, label, action)

    fun addPendingOpponentPacket(label: String, action: Runnable) =
        enqueue("OPPONENT", pendingOpponentPackets, label, action)

    fun addPendingPlayerPacket(action: Runnable) =
        addPendingPlayerPacket("unlabelled-player", action)

    fun addPendingOpponentPacket(action: Runnable) =
        addPendingOpponentPacket("unlabelled-opponent", action)


    fun addPendingPacket(action: Runnable) =
        addPendingCorePacket("unlabelled-core", action)

    fun setPendingBattlePacket(action: Runnable) =
        addPendingCorePacket("BattleInitializePacket", action)

    fun registerPokemonOwnership(entityId: Int, isPlayerOwned: Boolean) {
        entityOwnership[entityId] = isPlayerOwned
    }

    fun registerBattlePokemonSpawn(
        entityId: Int,
        isPlayerOwned: Boolean,
        x: Double,
        z: Double
    ) {
        entityOwnership[entityId] = isPlayerOwned
        battleSpawnInfo[entityId] = BattleSpawnInfo(isPlayerOwned, x, z)
    }

    fun isPlayerOwnedEntity(entityId: Int): Boolean? = entityOwnership[entityId]


    fun getDesiredBattlePokemonYaw(entityId: Int): Float? {
        val source = battleSpawnInfo[entityId] ?: return null

        val target = battleSpawnInfo
            .asSequence()
            .filter { (_, info) -> info.isPlayerOwned != source.isPlayerOwned }
            .minByOrNull { (_, info) ->
                val dx = info.x - source.x
                val dz = info.z - source.z
                dx * dx + dz * dz
            }
            ?.value
            ?: return null

        val dx = target.x - source.x
        val dz = target.z - source.z
        if (dx * dx + dz * dz < 1.0E-6) return null

        return (Math.toDegrees(atan2(dz, dx)) - 90.0).toFloat()
    }


    fun onBattlePokemonSpawned(entityId: Int) {
        refreshBattlePokemonFacing()

        afterOnClient(0.05f) {
            refreshBattlePokemonFacing()
        }

        afterOnClient(0.25f) {
            refreshBattlePokemonFacing()
        }
    }


    fun refreshBattlePokemonFacing() {
        val client = MinecraftClient.getInstance()
        client.execute {
            val world = client.world ?: return@execute

            battleSpawnInfo.keys.forEach { entityId ->
                val yaw = getDesiredBattlePokemonYaw(entityId)
                    ?: return@forEach
                val entity = world.getEntityById(entityId) as? LivingEntity
                    ?: return@forEach

                entity.setYaw(yaw)
                entity.setHeadYaw(yaw)
                entity.setBodyYaw(yaw)

                entity.prevHeadYaw = yaw
                entity.prevBodyYaw = yaw
            }
        }
    }


    fun isOpponentPokemon(pokemonUUID: java.util.UUID): Boolean = pokemonUUID in opponentPokemonUUIDs

    fun isLocalPokemon(pokemonUUID: java.util.UUID): Boolean = pokemonUUID in localPokemonUUIDs


    fun isSoundNearPlayer(x: Double, y: Double, z: Double): Boolean {
        val player = localEntityRef ?: return true
        val opponent = opponentEntityRef
        val distPlayer = player.pos.squaredDistanceTo(x, y, z)
        val distOpponent = opponent?.pos?.squaredDistanceTo(x, y, z) ?: Double.MAX_VALUE
        return distPlayer <= distOpponent
    }

    private var localSkinId:    Identifier? = null
    private var opponentSkinId: Identifier? = null
    private var opponentName:   String = ""
    private var isOpponentPlayer: Boolean = false
    private var localEntityRef:    LivingEntity? = null
    private var opponentEntityRef: LivingEntity? = null
    private var bossPresentation: WildBossesCompat.BossPresentation? = null
    private var specialWildPresentation: SpecialWildPokemonResolver.Presentation? = null

    private var localPokemonUUIDs = emptySet<java.util.UUID>()
    private var opponentPokemonUUIDs = emptySet<java.util.UUID>()

    private var localBallStacks: List<ItemStack> = emptyList()
    private var opponentBallStacks: List<ItemStack> = emptyList()

    private val EMPTY_BALL_TEXTURE = Identifier.of("battleslider", "textures/gui/empty_party_ball.png")
    private const val EMPTY_BALL_TEXTURE_SIZE = 16
    private val TEAM_BALL_LINEUP_SOUND = Identifier.of("battleslider", "team_ball_lineup")


    private var topColorA: Int = 0
    private var topColorB: Int = 0

    private data class Particle(var x: Float, var y: Float, var w: Float, var speed: Float, var alpha: Float)
    private val topParticles  = mutableListOf<Particle>()
    private val botParticles  = mutableListOf<Particle>()

    private val BOT_COLOR_A = argb(255, 0x1A, 0x8C, 0xE8)
    private val BOT_COLOR_B = argb(255, 0xCE, 0xEE, 0xFF)

    private val PVP_COLOR_A = argb(255, 0xE8, 0x6A, 0x00)
    private val PVP_COLOR_B = argb(255, 0xFF, 0xD8, 0x90)

    private val DEFAULT_COLOR_A = argb(255, 0xC8, 0xB4, 0x00)
    private val DEFAULT_COLOR_B = argb(255, 0xFF, 0xF6, 0xB8)

    private val BORDER_LIGHT = argb(255, 0xFF, 0xFF, 0xFF)
    private val BORDER_DARK  = argb(255, 0x88, 0x88, 0x88)

    private fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    fun register() {
        if (registered) {
            LOGGER.debug("BattleIntroOverlay.register() ignored: already registered")
            return
        }
        registered = true

        HudRenderCallback.EVENT.register { drawContext, tickCounter ->
            if (state != State.IDLE) render(drawContext, tickCounter.getTickDelta(true))
        }
        LOGGER.info("Battle intro HUD renderer registered")
    }

    fun trigger(
        localActor: BattleActor,
        opponentActor: BattleActor,
        wildBoss: WildBossesCompat.BossPresentation? = null,
        specialWild: SpecialWildPokemonResolver.Presentation? = null
    ) {
        if (isFlushing) return
        val client = MinecraftClient.getInstance()

        localSkinId    = getSkinId(client.player)
        isOpponentPlayer = opponentActor is PlayerBattleActor
        localEntityRef = client.player

        bossPresentation = wildBoss
        specialWildPresentation = specialWild

        val pokemonPresentationEntity =
            wildBoss?.entity ?: specialWild?.entity

        val oppEntity =
            pokemonPresentationEntity
                ?: BattleHandler.resolveOpponentEntity(opponentActor)

        opponentSkinId =
            if (pokemonPresentationEntity == null) getSkinId(oppEntity) else null

        opponentEntityRef = oppEntity
        opponentName = when {
            wildBoss != null ->
                "${formatBossTier(wildBoss.tierName)} Boss ${wildBoss.speciesName}"

            specialWild != null ->
                "${specialWild.role.displayName} ${specialWild.speciesName}"

            else ->
                resolveOpponentName(opponentActor, oppEntity)
        }

        localPokemonUUIDs = localActor.pokemonList.map { it.uuid }.toSet()
        opponentPokemonUUIDs = opponentActor.pokemonList.map { it.uuid }.toSet()
        localBallStacks = localActor.pokemonList.take(6).map { resolveBallStack(it.effectedPokemon.caughtBall) }
        opponentBallStacks =
            if (pokemonPresentationEntity == null) {
                opponentActor.pokemonList
                    .take(6)
                    .map {
                        resolveBallStack(
                            it.effectedPokemon.caughtBall
                        )
                    }
            } else {
                emptyList()
            }

        resolveTopBarColor(
            opponentActor,
            oppEntity,
            isOpponentPlayer,
            wildBoss,
            specialWild
        )

        seedParticles(topParticles)
        seedParticles(botParticles)

        pendingCorePackets.clear()
        pendingPlayerPackets.clear()
        pendingOpponentPackets.clear()
        entityOwnership.clear()
        battleSpawnInfo.clear()
        introStartedAtMs = System.currentTimeMillis()
        debugSequence = 0L

        progress   = 0f
        lastTimeMs = 0L
        state      = State.FLICKER

        debugLog(
            "[t+0ms] INTRO START | localParty={}, opponentParty={}, state={}",
            localPokemonUUIDs.size,
            opponentPokemonUUIDs.size,
            state
        )
    }

    private fun resolveTopBarColor(
        opponentActor: BattleActor,
        opponentEntity: LivingEntity?,
        isPvP: Boolean,
        wildBoss: WildBossesCompat.BossPresentation?,
        specialWild: SpecialWildPokemonResolver.Presentation?
    ) {
        if (wildBoss != null) {
            val color = bossTierColor(wildBoss.tierName)
            topColorA = brighten(color, 0.72f)
            topColorB = brighten(color, 1.45f)
            debugLog(
                "Boss slider palette: tier={} color=0x{}",
                wildBoss.tierName,
                "%06X".format(color and 0xFFFFFF)
            )
            return
        }

        if (specialWild != null) {
            val color = specialWild.baseColorRgb
            topColorA = brighten(color, 0.68f)
            topColorB = brighten(color, 1.42f)

            debugLog(
                "Special wild slider palette: role={} species={} primaryType={} color=0x{}",
                specialWild.role,
                specialWild.speciesName,
                specialWild.primaryTypeId,
                "%06X".format(color and 0xFFFFFF)
            )
            return
        }

        if (isPvP) {
            topColorA = PVP_COLOR_A
            topColorB = PVP_COLOR_B
            return
        }

        val classification =
            RctTrainerMetadataResolver.resolve(
                opponentActor,
                opponentEntity
            )

        val rctColor = classification?.let {
            RctTrainerMetadataResolver
                .resolveSliderColor(it)
        }

        if (classification != null && rctColor != null) {
            topColorA = brighten(rctColor, 0.7f)
            topColorB = brighten(rctColor, 1.5f)

            debugLog(
                "Opponent slider palette resolved from RCT: trainerId='{}', " +
                    "rawType='{}', optional={}, role={}, region={}, source={}, " +
                    "color=0x{}",
                classification.trainerId,
                classification.rawType,
                classification.optional,
                classification.role,
                classification.region ?: "<none>",
                classification.source,
                "%06X".format(rctColor and 0xFFFFFF)
            )
            return
        }

        topColorA = DEFAULT_COLOR_A
        topColorB = DEFAULT_COLOR_B

        debugLog(
            "Opponent slider palette fell back to default trainer colors: " +
                "actor={}, entity={}",
            opponentActor.javaClass.name,
            opponentEntity?.javaClass?.name ?: "<none>"
        )
    }

    private fun bossTierColor(tierName: String): Int = when (tierName.uppercase()) {
        "UNCOMMON" -> 0x3FA65A
        "RARE" -> 0x14B8A6
        "EPIC" -> 0x9B59E6
        "LEGENDARY" -> 0xF0A51A
        "MYTHIC" -> 0xFFFF55
        else -> 0xC8B400
    }

    private fun formatBossTier(tierName: String): String =
        tierName.lowercase().replaceFirstChar { it.uppercase() }

    private fun brighten(rgb: Int, factor: Float): Int {
        fun ch(shift: Int): Int {
            val v = (rgb shr shift and 0xFF)
            val out = if (factor <= 1f) (v * factor) else (v + (255 - v) * (factor - 1f))
            return out.toInt().coerceIn(0, 255)
        }
        return argb(255, ch(16), ch(8), ch(0))
    }

    private fun resolveOpponentName(actor: BattleActor, entity: LivingEntity?): String {
        if (actor is PlayerBattleActor) {
            val uuid = actor.getPlayerUUIDs().firstOrNull() ?: return "Pokémon Trainer"
            val name = MinecraftClient.getInstance().world
                ?.players?.firstOrNull { it.uuid == uuid }?.name?.string
            return "Pokémon Trainer ${name ?: "Unknown"}"
        }
        if (RctTrainerMetadataResolver.isTrainerEntity(entity)) {
            return entity?.name?.string ?: "???"
        }
        return entity?.name?.string ?: "???"
    }

    private fun getSkinId(entity: LivingEntity?): Identifier? {
        if (entity is AbstractClientPlayerEntity) return entity.skinTextures.texture
        return null
    }


    private fun resolveBallStack(ball: Any): ItemStack {
        val fallback = ItemStack(Registries.ITEM.get(Identifier.of("cobblemon", "poke_ball")))

        try {
            val methodNames = listOf("item", "getItem", "asItem", "getItemStack", "itemStack")
            for (name in methodNames) {
                val method = ball.javaClass.methods.firstOrNull {
                    it.name == name && it.parameterCount == 0
                } ?: continue

                when (val value = method.invoke(ball)) {
                    is ItemStack -> return value.copy()
                    is Item -> return ItemStack(value)
                    is Identifier -> {
                        val item = Registries.ITEM.get(value)
                        if (item != null) return ItemStack(item)
                    }
                    is String -> {
                        val id = Identifier.tryParse(value)
                        if (id != null) return ItemStack(Registries.ITEM.get(id))
                    }
                }
            }

            for (field in ball.javaClass.declaredFields) {
                field.isAccessible = true
                when (val value = field.get(ball)) {
                    is ItemStack -> return value.copy()
                    is Item -> return ItemStack(value)
                    is Identifier -> return ItemStack(Registries.ITEM.get(value))
                    is String -> {
                        val id = Identifier.tryParse(value)
                        if (id != null) return ItemStack(Registries.ITEM.get(id))
                    }
                }
            }
        } catch (e: Exception) {
            LOGGER.warn("Could not resolve caught-ball item from {}: {}", ball.javaClass.name, e.message)
        }

        return fallback
    }


    private fun playVanillaUiSound(
        sound: SoundEvent,
        pitch: Float = 1.0f
    ) {
        if (!BattleSliderConfig.uiTransitionSounds) {
            return
        }

        val client = MinecraftClient.getInstance()
        try {
            client.soundManager.play(
                PositionedSoundInstance.master(sound, pitch)
            )
        } catch (e: Exception) {
            LOGGER.warn(
                "Could not play vanilla UI sound: {}",
                e.message
            )
        }
    }

    private fun playTeamBallLineupSound() {
        if (
            !BattleSliderConfig.teamBallLineupSound ||
            !BattleSliderConfig.showPartyBalls
        ) {
            return
        }

        val client = MinecraftClient.getInstance()
        try {
            client.soundManager.play(
                PositionedSoundInstance.master(
                    SoundEvent.of(
                        TEAM_BALL_LINEUP_SOUND
                    ),
                    1.0f,
                    BattleSliderConfig.teamBallLineupVolume
                )
            )
        } catch (e: Exception) {
            LOGGER.debug(
                "Team-ball lineup sound unavailable: {}",
                e.message
            )
        }
    }


    fun renderExclusive(drawContext: DrawContext, tickDelta: Float) {
        if (state == State.IDLE || isFlushing) {
            return
        }

        render(drawContext, tickDelta)
    }

    private fun render(drawContext: DrawContext, tickDelta: Float) {
        val client = MinecraftClient.getInstance()
        val sw = client.window.scaledWidth
        val sh = client.window.scaledHeight

        val now = System.currentTimeMillis()
        val elapsed = if (lastTimeMs == 0L) 0L else now - lastTimeMs
        lastTimeMs = now

        when (state) {
            State.FLICKER -> {
                progress = (progress + elapsed.toFloat() / flickerDurationMs()).coerceAtMost(1f)
                if (progress >= 1f) {
                    progress = 0f
                    state = State.BARS_SLIDE_IN
                    playVanillaUiSound(SoundEvents.UI_TOAST_IN)
                }
            }
            State.BARS_SLIDE_IN -> {
                progress = (progress + elapsed.toFloat() / barsSlideMs()).coerceAtMost(1f)
                if (progress >= 1f) { progress = 0f; state = State.VS_APPEAR }
            }
            State.VS_APPEAR -> {
                progress = (progress + elapsed.toFloat() / vsAppearMs()).coerceAtMost(1f)
                if (progress >= 1f) { progress = 0f; state = State.CHARACTERS_SLIDE_IN }
            }
            State.CHARACTERS_SLIDE_IN -> {
                progress = (progress + elapsed.toFloat() / charactersSlideMs()).coerceAtMost(1f)
                if (progress >= 1f) {
                    progress = 0f
                    state = State.TEAM_BALLS_SLIDE_IN
                    playTeamBallLineupSound()
                }
            }
            State.TEAM_BALLS_SLIDE_IN -> {
                progress = (progress + elapsed.toFloat() / teamBallsSlideMs()).coerceAtMost(1f)
                if (progress >= 1f) { progress = 0f; state = State.HOLD }
            }
            State.HOLD -> {
                progress = (progress + elapsed.toFloat() / holdDurationMs()).coerceAtMost(1f)
                if (progress >= 1f) {
                    progress = 1f
                    state = State.SLIDING_OUT
                    playVanillaUiSound(SoundEvents.UI_TOAST_OUT)
                }
            }
            State.SLIDING_OUT -> {
                progress = (progress - elapsed.toFloat() / slideOutDurationMs()).coerceAtLeast(0f)
                if (progress <= 0f) {
                    finishIntroAndFlushPackets()
                    return
                }
            }
            State.IDLE -> return
        }

        val blackAlpha = when (state) {
            State.FLICKER ->
                computeFlickerAlpha(progress)

            State.BARS_SLIDE_IN -> {
                val maxAlpha = when (
                    BattleSliderConfig.flashIntensity
                ) {
                    BattleSliderConfig.FlashIntensity.OFF ->
                        0

                    BattleSliderConfig.FlashIntensity.REDUCED ->
                        150

                    BattleSliderConfig.FlashIntensity.NORMAL ->
                        255
                }

                (
                    maxAlpha *
                        (1f - easeOutCubic(progress))
                ).toInt()
            }

            else ->
                0
        }
        if (blackAlpha > 0) {
            drawContext.fill(0, 0, sw, sh, (blackAlpha shl 24))
        }
        if (state == State.FLICKER) {
            drawSkipPrompt(drawContext, sw, sh)
            return
        }

        val barsT = when (state) {
            State.BARS_SLIDE_IN -> easeOutCubic(progress)
            State.VS_APPEAR, State.CHARACTERS_SLIDE_IN, State.TEAM_BALLS_SLIDE_IN, State.HOLD -> 1f
            State.SLIDING_OUT -> easeOutCubic(progress)
            else -> 0f
        }

        val barH  = sh / 4
        val topY  = sh / 2 - barH - 1
        val botY  = sh / 2 + 1
        val borderThick = 2

        val topRight = (sw * barsT).toInt()
        drawJaggedBar(drawContext, 0, topRight, topY, topY + barH, topColorB, topColorA, leadingEdgeOnLeft = false)
        drawContext.fill(0, topY,              topRight, topY + borderThick, BORDER_LIGHT)
        drawContext.fill(0, topY + barH - borderThick, topRight, topY + barH, BORDER_DARK)

        val botLeft = (sw * (1f - barsT)).toInt()
        drawJaggedBar(drawContext, botLeft, sw, botY, botY + barH, BOT_COLOR_A, BOT_COLOR_B, leadingEdgeOnLeft = true)
        drawContext.fill(botLeft, botY,              sw, botY + borderThick, BORDER_LIGHT)
        drawContext.fill(botLeft, botY + barH - borderThick, sw, botY + barH, BORDER_DARK)

        val elapsedSec = elapsed / 1000f
        if (barsT > 0.1f) {
            updateAndDrawParticles(drawContext, topParticles,
                barLeft = 0f, barRight = topRight.toFloat(),
                barTop = topY.toFloat(), barBottom = (topY + barH).toFloat(),
                rtl = false, elapsedSec = elapsedSec, t = barsT)
            updateAndDrawParticles(drawContext, botParticles,
                barLeft = botLeft.toFloat(), barRight = sw.toFloat(),
                barTop = botY.toFloat(), barBottom = (botY + barH).toFloat(),
                rtl = true, elapsedSec = elapsedSec, t = barsT)
        }

        val vsT = when (state) {
            State.VS_APPEAR -> easeOutCubic(progress)
            State.CHARACTERS_SLIDE_IN, State.TEAM_BALLS_SLIDE_IN, State.HOLD -> 1f
            State.SLIDING_OUT -> if (progress > 0.4f) 1f else 0f
            else -> 0f
        }
        if (vsT > 0f) drawVS(drawContext, sw, sh, vsT)

        if (
            BattleSliderConfig.showNameBadges &&
            state != State.BARS_SLIDE_IN &&
            opponentName.isNotEmpty()
        ) {
            drawOpponentBadge(
                drawContext,
                topRight,
                topY
            )
            drawPlayerBadge(
                drawContext,
                botLeft,
                topY,
                barH
            )
        }

        val charT = when (state) {
            State.CHARACTERS_SLIDE_IN -> easeOutCubic(progress)
            State.TEAM_BALLS_SLIDE_IN, State.HOLD -> 1f
            State.SLIDING_OUT -> barsT
            else -> 0f
        }
        if (charT > 0f) {
            val restingOppX = sw * 3 / 4
            val restingPlrX = sw / 4
            val startOppX = -sw / 2
            val startPlrX = sw + sw / 2
            val oppX = (startOppX + (restingOppX - startOppX) * charT).toInt()
            val plrX = (startPlrX + (restingPlrX - startPlrX) * charT).toInt()

            val boss = bossPresentation
            val specialWild = specialWildPresentation
            val pokemonPortraitEntity =
                boss?.entity ?: specialWild?.entity
            val oppEntity = opponentEntityRef ?: localEntityRef

            if (pokemonPortraitEntity != null) {
                PokemonPortraitRenderer.render(
                    drawContext,
                    pokemonPortraitEntity,
                    oppX,
                    topY,
                    topY + barH
                )
            } else if (oppEntity != null) {
                TrainerPortraitRenderer.render(
                    drawContext,
                    opponentSkinId,
                    TrainerSkinRenderer.Pose.OPPONENT,
                    oppX,
                    topY,
                    topY + barH,
                    oppEntity
                )
            }
            val plrEntity = localEntityRef
            if (plrEntity != null) {
                TrainerPortraitRenderer.render(
                    drawContext,
                    localSkinId,
                    TrainerSkinRenderer.Pose.PLAYER,
                    plrX,
                    botY,
                    botY + barH,
                    plrEntity
                )
            }
        }


        val teamBallsT = when (state) {
            State.TEAM_BALLS_SLIDE_IN -> progress
            State.HOLD -> 1f
            State.SLIDING_OUT -> 1f
            else -> 0f
        }
        if (teamBallsT > 0f) {
            val boss = bossPresentation
            val specialWild = specialWildPresentation

            when {
                boss != null -> {
                    drawBossInfo(
                        ctx = drawContext,
                        boss = boss,
                        sw = sw,
                        barTop = topY,
                        barHeight = barH,
                        phaseProgress = teamBallsT,
                        exitProgress = if (state == State.SLIDING_OUT) barsT else 1f
                    )
                }

                specialWild != null -> {
                    drawSpecialWildInfo(
                        ctx = drawContext,
                        specialWild = specialWild,
                        sw = sw,
                        barTop = topY,
                        barHeight = barH,
                        phaseProgress = teamBallsT,
                        exitProgress = if (state == State.SLIDING_OUT) barsT else 1f
                    )
                }

                else -> {
                    if (BattleSliderConfig.showPartyBalls) {
                        drawTeamBalls(
                            ctx = drawContext,
                            stacks = opponentBallStacks,
                            isOpponent = true,
                            sw = sw,
                            barTop = topY,
                            barHeight = barH,
                            phaseProgress = teamBallsT,
                            exitProgress = if (
                                state == State.SLIDING_OUT
                            ) {
                                barsT
                            } else {
                                1f
                            }
                        )
                    }
                }
            }

            if (BattleSliderConfig.showPartyBalls) {
                drawTeamBalls(
                    ctx = drawContext,
                    stacks = localBallStacks,
                    isOpponent = false,
                    sw = sw,
                    barTop = botY,
                    barHeight = barH,
                    phaseProgress = teamBallsT,
                    exitProgress = if (
                        state == State.SLIDING_OUT
                    ) {
                        barsT
                    } else {
                        1f
                    }
                )
            }
        }

        if (state != State.SLIDING_OUT) {
            drawSkipPrompt(drawContext, sw, sh)
        }
    }


    private fun drawBossInfo(
        ctx: DrawContext,
        boss: WildBossesCompat.BossPresentation,
        sw: Int,
        barTop: Int,
        barHeight: Int,
        phaseProgress: Float,
        exitProgress: Float
    ) {
        val font = MinecraftClient.getInstance().textRenderer
        val text = "Scaled Level = Lv.${boss.level}"
        val textWidth = font.getWidth(text)
        val targetX = (sw * 3 / 8 - textWidth).coerceAtLeast(24)
        val startX = -textWidth - 24
        val enter = easeOutCubic(phaseProgress.coerceIn(0f, 1f))
        val visible = (enter * exitProgress.coerceIn(0f, 1f)).coerceIn(0f, 1f)
        val x = (startX + (targetX - startX) * visible).toInt()
        val y = barTop + (barHeight - font.fontHeight) / 2
        drawInfoBannerText(ctx, text, x, y)
    }

    private fun drawSpecialWildInfo(
        ctx: DrawContext,
        specialWild: SpecialWildPokemonResolver.Presentation,
        sw: Int,
        barTop: Int,
        barHeight: Int,
        phaseProgress: Float,
        exitProgress: Float
    ) {
        val font = MinecraftClient.getInstance().textRenderer
        val text = "Lv.${specialWild.level}"
        val textWidth = font.getWidth(text)
        val targetX = (sw * 3 / 8 - textWidth).coerceAtLeast(24)
        val startX = -textWidth - 24
        val enter = easeOutCubic(phaseProgress.coerceIn(0f, 1f))
        val visible =
            (enter * exitProgress.coerceIn(0f, 1f))
                .coerceIn(0f, 1f)
        val x =
            (startX + (targetX - startX) * visible)
                .toInt()
        val y =
            barTop + (barHeight - font.fontHeight) / 2
        drawInfoBannerText(ctx, text, x, y)
    }


// dark backing keeps bright bars readable
    private fun drawInfoBannerText(
        ctx: DrawContext,
        text: String,
        x: Int,
        y: Int
    ) {
        val font = MinecraftClient.getInstance().textRenderer
        val textWidth = font.getWidth(text)
        val padX = 6
        val padY = 3

        val left = x - padX
        val top = y - padY
        val right = x + textWidth + padX
        val bottom = y + font.fontHeight + padY


        ctx.fill(
            left - 2,
            top - 2,
            right + 2,
            bottom + 2,
            0x44111111
        )
        ctx.fill(
            left,
            top,
            right,
            bottom,
            0x99101010.toInt()
        )

        ctx.drawText(
            font,
            text,
            x,
            y,
            0xFFFFFFFF.toInt(),
            true
        )
    }

    private fun drawTeamBalls(
        ctx: DrawContext,
        stacks: List<ItemStack>,
        isOpponent: Boolean,
        sw: Int,
        barTop: Int,
        barHeight: Int,
        phaseProgress: Float,
        exitProgress: Float
    ) {
        val slotSize = 16
        val gap = 5
        val rowWidth = slotSize * 6 + gap * 5

        val baseStart = if (isOpponent) {
            (sw * 3 / 8 - rowWidth).coerceAtLeast(24)
        } else {
            (sw * 5 / 8).coerceAtMost(sw - rowWidth - 24)
        }
        val y = barTop + (barHeight - slotSize) / 2

        for (slot in 0 until 6) {
            val revealOrder = if (isOpponent) slot else 5 - slot
            val staggerStart = revealOrder * 0.075f
            val localT = ((phaseProgress - staggerStart) / (1f - 5f * 0.075f))
                .coerceIn(0f, 1f)
            if (localT <= 0f) continue

            val eased = easeOutBack(localT)
            val finalX = baseStart + slot * (slotSize + gap)
            val introOffset = if (isOpponent) {
                -((1f - eased) * 72f).toInt()
            } else {
                ((1f - eased) * 72f).toInt()
            }

            val exitOffset = if (isOpponent) {
                -((1f - exitProgress) * sw).toInt()
            } else {
                ((1f - exitProgress) * sw).toInt()
            }

            val x = finalX + introOffset + exitOffset
            if (slot < stacks.size && !stacks[slot].isEmpty) {
                drawScaledItem(ctx, stacks[slot], x, y, slotSize)
            } else {
                ctx.drawTexture(
                    EMPTY_BALL_TEXTURE,
                    x,
                    y,
                    0f,
                    0f,
                    16,
                    16,
                    EMPTY_BALL_TEXTURE_SIZE,
                    EMPTY_BALL_TEXTURE_SIZE
                )
            }
        }
    }

    private fun drawScaledItem(ctx: DrawContext, stack: ItemStack, x: Int, y: Int, size: Int) {
        ctx.drawItem(stack, x, y)
    }


    private fun easeOutBack(t: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val x = t - 1f
        return 1f + c3 * x * x * x + c1 * x * x
    }


    private fun drawSkipPrompt(
        ctx: DrawContext,
        sw: Int,
        sh: Int
    ) {
        if (
            !BattleSliderConfig.allowSkipping ||
            !BattleSliderConfig.showSkipPrompt
        ) {
            return
        }

        val font = MinecraftClient.getInstance().textRenderer
        val keyName = BattleSliderKeybinds.getSkipKeyText().string
        val label = "$keyName  Skip"
        val padX = 6
        val padY = 4
        val width = font.getWidth(label) + padX * 2
        val height = font.fontHeight + padY * 2
        val x = sw - width - 8
        val y = sh - height - 8

        ctx.fill(x, y, x + width, y + height, argb(170, 0x0A, 0x0A, 0x0A))
        ctx.fill(x, y, x + width, y + 1, argb(210, 0xFF, 0xFF, 0xFF))
        ctx.drawText(font, label, x + padX, y + padY, 0xFFFFFFFF.toInt(), true)
    }

    private fun computeFlickerAlpha(
        overallProgress: Float
    ): Int {
        val (flickCount, maxAlpha) = when (
            BattleSliderConfig.flashIntensity
        ) {
            BattleSliderConfig.FlashIntensity.OFF ->
                return 0

            BattleSliderConfig.FlashIntensity.REDUCED ->
                3 to 150

            BattleSliderConfig.FlashIntensity.NORMAL ->
                7 to 255
        }

        val flickPhase =
            (overallProgress * flickCount)
                .coerceAtMost(
                    flickCount.toFloat() - 0.0001f
                )

        val cycleIndex =
            flickPhase.toInt()
                .coerceIn(0, flickCount - 1)

        val cycleProgress =
            (flickPhase - cycleIndex)
                .coerceIn(0f, 1f)

        val isLastFlick =
            cycleIndex == flickCount - 1

        val pulse = if (isLastFlick) {
            cycleProgress
        } else {
            sin(
                (
                    cycleProgress *
                        Math.PI
                ).toFloat()
            ).coerceIn(0f, 1f)
        }

        return (pulse * maxAlpha)
            .toInt()
            .coerceIn(0, maxAlpha)
    }

    private const val GRADIENT_STEPS = 48
    private const val DITHER_BANDS = 6

    private fun drawPixelGradient(
        ctx: DrawContext, x1: Int, y1: Int, x2: Int, y2: Int,
        colorA: Int, colorB: Int, horizontal: Boolean
    ) {
        val width = x2 - x1
        val height = y2 - y1
        if (width <= 0 || height <= 0) return

        val steps = if (horizontal) GRADIENT_STEPS.coerceAtMost(width) else DITHER_BANDS.coerceAtMost(height)
        val bands = if (horizontal) DITHER_BANDS.coerceAtMost(height) else GRADIENT_STEPS.coerceAtMost(width)

        for (step in 0 until steps) {
            val axisStart = if (horizontal) x1 + width * step / steps else y1 + height * step / steps
            val axisEnd = if (horizontal) x1 + width * (step + 1) / steps else y1 + height * (step + 1) / steps
            val baseT = if (steps <= 1) 0f else step.toFloat() / (steps - 1)

            for (band in 0 until bands) {
                val crossStart = if (horizontal) y1 + height * band / bands else x1 + width * band / bands
                val crossEnd = if (horizontal) y1 + height * (band + 1) / bands else x1 + width * (band + 1) / bands

                val dither = BAYER4[band % 4][step % 4] / 16f
                val adjustedT = (baseT + (dither - 0.5f) * 0.10f).coerceIn(0f, 1f)
                val color = lerpColor(colorA, colorB, adjustedT)

                if (horizontal) {
                    ctx.fill(axisStart, crossStart, axisEnd, crossEnd, color)
                } else {
                    ctx.fill(crossStart, axisStart, crossEnd, axisEnd, color)
                }
            }
        }
    }

    private val JAG_PATTERN = intArrayOf(4, 11, 18, 22, 18, 11, 4, 0)

    private fun drawJaggedBar(
        ctx: DrawContext, x1: Int, x2: Int, y1: Int, y2: Int,
        colorA: Int, colorB: Int, leadingEdgeOnLeft: Boolean
    ) {
        drawPixelGradient(ctx, x1, y1, x2, y2, colorA, colorB, horizontal = true)

        val barHeight = y2 - y1
        if (barHeight <= 0) return
        val edgeX = if (leadingEdgeOnLeft) x1 else x2
        val edgeColor = if (leadingEdgeOnLeft) colorA else colorB
        val stripH = (barHeight / 14).coerceAtLeast(4)

        var y = y1
        var i = 0
        while (y < y2) {
            val bump = JAG_PATTERN[i % JAG_PATTERN.size]
            val yEnd = (y + stripH).coerceAtMost(y2)
            if (leadingEdgeOnLeft) {
                ctx.fill(edgeX - bump, y, edgeX, yEnd, edgeColor)
            } else {
                ctx.fill(edgeX, y, edgeX + bump, yEnd, edgeColor)
            }
            y = yEnd
            i++
        }
    }

    private val BAYER4 = arrayOf(
        intArrayOf(0,  8,  2, 10),
        intArrayOf(12, 4, 14,  6),
        intArrayOf(3, 11,  1,  9),
        intArrayOf(15, 7, 13,  5)
    )

    private fun lerpColor(a: Int, b: Int, t: Float): Int {
        val ar = (a shr 16 and 0xFF); val ag = (a shr 8 and 0xFF); val ab = (a and 0xFF); val aa = (a ushr 24)
        val br = (b shr 16 and 0xFF); val bg = (b shr 8 and 0xFF); val bb = (b and 0xFF); val ba = (b ushr 24)
        return argb(
            (aa + (ba - aa) * t).toInt(),
            (ar + (br - ar) * t).toInt(),
            (ag + (bg - ag) * t).toInt(),
            (ab + (bb - ab) * t).toInt()
        )
    }

    private const val PARTICLE_ROWS = 6

    private fun randomParticle(row: Int): Particle = Particle(
        x = Random.nextFloat(),
        y = ((row + Random.nextFloat()) / PARTICLE_ROWS).coerceIn(0f, 1f),
        w = 0.04f + Random.nextFloat() * 0.12f,
        speed = 0.18f + Random.nextFloat() * 0.35f,
        alpha = 0.4f + Random.nextFloat() * 0.5f
    )

    private fun seedParticles(
        list: MutableList<Particle>
    ) {
        list.clear()

        val perRow =
            BattleSliderConfig.particleDensity
                .particlesPerRow

        repeat(PARTICLE_ROWS) { row ->
            repeat(perRow) {
                list.add(
                    randomParticle(row)
                )
            }
        }
    }

    private fun updateAndDrawParticles(
        ctx: DrawContext,
        particles: MutableList<Particle>,
        barLeft: Float, barRight: Float, barTop: Float, barBottom: Float,
        rtl: Boolean, elapsedSec: Float, t: Float
    ) {
        val barW = barRight - barLeft
        val barH = barBottom - barTop
        if (barW <= 0f || barH <= 0f) return

        particles.forEach { p ->
            if (rtl) p.x -= p.speed * elapsedSec
            else     p.x += p.speed * elapsedSec

            if (rtl && p.x + p.w < 0f) { p.x = 1f; p.y = Random.nextFloat() }
            if (!rtl && p.x > 1f)      { p.x = -p.w; p.y = Random.nextFloat() }

            val px = (barLeft + p.x * barW).toInt()
            val pw = (p.w * barW).toInt().coerceAtLeast(4)
            val py = (barTop + p.y * barH).toInt()
            val alpha = (p.alpha * t * 255).toInt().coerceIn(0, 255)
            val color = (alpha shl 24) or 0xFFFFFF

            ctx.fill(px, py, px + pw, py + 2, color)
        }
    }

    private fun drawVS(ctx: DrawContext, sw: Int, sh: Int, vsT: Float) {
        val font = MinecraftClient.getInstance().textRenderer
        val text = "VS"
        val baseW = font.getWidth(text)
        val scale = 5.5f * vsT.coerceIn(0f, 1f)
        val cx = sw / 2f
        val cy = sh / 2f

        val matrices = ctx.matrices
        matrices.push()
        matrices.translate(cx, cy, 300f)
        matrices.scale(scale, scale, 1f)
        val tx = (-baseW / 2).toFloat()
        val ty = (-font.fontHeight / 2).toFloat()
        val BLACK = 0xFF111111.toInt()
        val WHITE = 0xFFFFFFFF.toInt()

        for (dx in -1..1) for (dy in -1..1) {
            if (dx == 0 && dy == 0) continue
            ctx.drawText(font, text, (tx + dx).toInt(), (ty + dy).toInt(), BLACK, false)
        }
        ctx.drawText(font, text, tx.toInt(), ty.toInt(), WHITE, false)

        matrices.pop()
    }

    private fun drawOpponentBadge(ctx: DrawContext, edgeX: Int, topY: Int) {
        val font = MinecraftClient.getInstance().textRenderer
        val pad = 5
        val badgeH = font.fontHeight + pad * 2
        val oppW = font.getWidth(opponentName) + pad * 2
        val oppX = edgeX - oppW
        val oppY = topY

        drawMetallicBadge(ctx, oppX, oppY, oppX + oppW, oppY + badgeH)
        ctx.drawText(font, opponentName, oppX + pad, oppY + pad, 0xFFFFFFFF.toInt(), false)
    }

    private fun drawPlayerBadge(ctx: DrawContext, edgeX: Int, topY: Int, barH: Int) {
        val font = MinecraftClient.getInstance().textRenderer
        val client = MinecraftClient.getInstance()
        val pad = 5
        val badgeH = font.fontHeight + pad * 2

        val botY = topY + barH + 2
        val playerNameRaw = client.player?.name?.string ?: "Player"
        val playerLabel = "Pokémon Trainer $playerNameRaw"
        val plW = font.getWidth(playerLabel) + pad * 2
        val plBadgeBottom = botY + barH
        val plY = plBadgeBottom - badgeH
        val plX = edgeX

        drawMetallicBadge(ctx, plX, plY, plX + plW, plBadgeBottom)
        ctx.drawText(font, playerLabel, plX + pad, plY + pad, 0xFFFFFFFF.toInt(), false)
    }


    private fun drawMetallicBadge(ctx: DrawContext, x1: Int, y1: Int, x2: Int, y2: Int) {
        ctx.fill(x1, y1, x2, y2, argb(210, 0x1A, 0x1A, 0x1A))
        ctx.fill(x1, y1, x2, y1 + 2, BORDER_LIGHT)
        ctx.fill(x1, y2 - 2, x2, y2, BORDER_DARK)
        ctx.fill(x1, y1, x1 + 2, y2, BORDER_LIGHT)
        ctx.fill(x2 - 2, y1, x2, y2, BORDER_DARK)
    }

    private fun easeOutCubic(t: Float): Float = 1f - (1f - t).pow(3)

    fun isAnimating(): Boolean =
        state != State.IDLE && !isFlushing


    fun isVisualTransitionActive(): Boolean =
        isAnimating()


    fun canSkip(): Boolean =
        BattleSliderConfig.allowSkipping &&
            state != State.IDLE &&
            state != State.SLIDING_OUT &&
            !isFlushing


    fun skip() {
        debugLog(
            "[t+{}ms] SKIP requested | state={}, canSkip={}, flushing={}, core={}, opponent={}, player={}",
            elapsedDebugMs(),
            state,
            canSkip(),
            isFlushing,
            pendingCorePackets.size,
            pendingOpponentPackets.size,
            pendingPlayerPackets.size
        )

        if (!canSkip()) {
            debugLog("Skip ignored because state={} is not currently skippable", state)
            return
        }

        state = State.SLIDING_OUT
        progress = 1f
        lastTimeMs = 0L
        playVanillaUiSound(SoundEvents.UI_TOAST_OUT)

        debugLog(
            "Skip accepted: visual state changed to SLIDING_OUT; packet replay remains on normal completion path"
        )
    }

    fun getDebugState(): String =
        "state=$state, isFlushing=$isFlushing, corePackets=${pendingCorePackets.size}, opponentPackets=${pendingOpponentPackets.size}, playerPackets=${pendingPlayerPackets.size}, t+${elapsedDebugMs()}ms"


    private fun replayQueue(
        queueName: String,
        actions: List<PendingAction>
    ) {
        debugLog(
            "[t+{}ms] REPLAY {} batch start | actions={}",
            elapsedDebugMs(),
            queueName,
            actions.size
        )

        actions.forEachIndexed { index, pending ->
            debugLog(
                "[t+{}ms] REPLAY {} {}/{} -> {} | queuedFor={}ms",
                elapsedDebugMs(),
                queueName,
                index + 1,
                actions.size,
                pending.label,
                System.currentTimeMillis() - pending.queuedAtMs
            )
            try {
                pending.action.invoke()
            } catch (e: Exception) {
                LOGGER.error(
                    "[t+{}ms] REPLAY FAILURE {} -> {}",
                    elapsedDebugMs(),
                    pending.label,
                    e.message,
                    e
                )
            }
        }

        debugLog(
            "[t+{}ms] REPLAY {} batch end",
            elapsedDebugMs(),
            queueName
        )
    }

    private fun drain(
        queue: java.util.concurrent.ConcurrentLinkedQueue<PendingAction>
    ): List<PendingAction> {
        val result = ArrayList<PendingAction>()
        var pending = queue.poll()
        while (pending != null) {
            result.add(pending)
            pending = queue.poll()
        }
        return result
    }


    private fun finishIntroAndFlushPackets() {
        if (state == State.IDLE || isFlushing) return

        isFlushing = true

        val coreBatch = drain(pendingCorePackets)
        val opponentBatch = drain(pendingOpponentPackets)
        val playerBatch = drain(pendingPlayerPackets)

        debugLog(
            "[t+{}ms] FLUSH snapshot | core={}, opponent={}, player={}",
            elapsedDebugMs(),
            coreBatch.size,
            opponentBatch.size,
            playerBatch.size
        )

        state = State.IDLE

        replayQueue("CORE", coreBatch)
        replayQueue("OPPONENT", opponentBatch)

        if (playerBatch.isNotEmpty()) {
            debugLog(
                "[t+{}ms] PLAYER batch scheduled after {}s | actions={}",
                elapsedDebugMs(),
                PLAYER_STAGGER_DELAY_S,
                playerBatch.size
            )
            afterOnClient(PLAYER_STAGGER_DELAY_S) {
                replayQueue("PLAYER", playerBatch)
            }
        } else {
            LOGGER.warn("[t+{}ms] PLAYER batch is empty", elapsedDebugMs())
        }

        localSkinId = null
        opponentSkinId = null
        localEntityRef = null
        opponentEntityRef = null
        bossPresentation = null
        specialWildPresentation = null
        localPokemonUUIDs = emptySet()
        opponentPokemonUUIDs = emptySet()
        localBallStacks = emptyList()
        opponentBallStacks = emptyList()
        topParticles.clear()
        botParticles.clear()


        isFlushing = false

        debugLog(
            "[t+{}ms] FLUSH dispatch complete | delayedPlayerActions={}",
            elapsedDebugMs(),
            playerBatch.size
        )
    }

    fun getRemainingAnimationMs(): Long {
        val flicker = flickerDurationMs()
        val bars = barsSlideMs()
        val vs = vsAppearMs()
        val characters = charactersSlideMs()
        val team = teamBallsSlideMs()
        val hold = holdDurationMs()
        val slideOut = slideOutDurationMs()

        return when (state) {
            State.FLICKER ->
                ((1f - progress) * flicker).toLong() +
                    bars + vs + characters + team +
                    hold + slideOut

            State.BARS_SLIDE_IN ->
                ((1f - progress) * bars).toLong() +
                    vs + characters + team +
                    hold + slideOut

            State.VS_APPEAR ->
                ((1f - progress) * vs).toLong() +
                    characters + team +
                    hold + slideOut

            State.CHARACTERS_SLIDE_IN ->
                ((1f - progress) * characters).toLong() +
                    team + hold + slideOut

            State.TEAM_BALLS_SLIDE_IN ->
                ((1f - progress) * team).toLong() +
                    hold + slideOut

            State.HOLD ->
                ((1f - progress) * hold).toLong() +
                    slideOut

            State.SLIDING_OUT ->
                (progress * slideOut).toLong()

            State.IDLE ->
                0L
        }
    }
}
