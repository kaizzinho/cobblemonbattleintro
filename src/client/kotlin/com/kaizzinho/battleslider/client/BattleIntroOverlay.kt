package com.kaizzinho.battleslider.client

import com.cobblemon.mod.common.api.battles.model.actor.BattleActor
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.fabricmc.loader.api.FabricLoader
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
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random
import org.slf4j.LoggerFactory
import com.kaizzinho.battleslider.client.config.BattleSliderConfig

@Environment(EnvType.CLIENT)
object BattleIntroOverlay {

    private val LOGGER = LoggerFactory.getLogger("battleslider/BattleIntroOverlay")
    private var registered = false

    /**
     * Detailed queue/timing diagnostics controlled by config/battleslider.json.
     * Expensive message arguments are only constructed when debugging is active.
     */
    private fun debugLog(message: String, vararg args: Any?) {
        if (BattleSliderConfig.debugLogging) {
            LOGGER.info(message, *args)
        }
    }

    // ── State machine ─────────────────────────────────────────────────────────
    // FLICKER: 7 black flicks, each reaching full black, final pulse held
    // BARS_SLIDE_IN: both bars slide in simultaneously (top L->R, bottom R->L)
    // VS_APPEAR: VS graphic pops in
    // CHARACTERS_SLIDE_IN: trainer portrait slides L->R, player portrait R->L
    // TEAM_BALLS_SLIDE_IN: six party slots line up after both portraits settle
    // HOLD: everything held on screen
    // SLIDING_OUT: both bars exit together (unchanged mechanism from before --
    //   it reuses the same position formulas as slide-in, so it automatically
    //   reverses along whichever direction the bars now enter from)
    enum class State { IDLE, FLICKER, BARS_SLIDE_IN, VS_APPEAR, CHARACTERS_SLIDE_IN, TEAM_BALLS_SLIDE_IN, HOLD, SLIDING_OUT }

    var state = State.IDLE
    private var progress = 0f
    private var lastTimeMs = 0L
    private var isFlushing = false

    // ── Timing ───────────────────────────────────────────────────────────────
    // Total elapsed time from trigger() to the cry actually sounding =
    //   FLICKER_TOTAL_MS + BARS_SLIDE_MS + VS_APPEAR_MS + CHARACTERS_SLIDE_MS
    //   + TEAM_BALLS_SLIDE_MS + HOLD_DURATION_MS + SLIDE_OUT_DURATION_MS + 1500 (cry delay in the mixin)
    // With the defaults below that's ~11s total. This is intentionally on the
    // slow/generous side per your request to see the full timing -- HOLD is
    // the one knob with real slack once you know where your music lands.
    private const val FLICKER_COUNT          = 7      // faster strobe -- more flicks packed into the same held total
    private const val FLICKER_TOTAL_MS       = 2250L  // duration held as-is per your request
    private const val BARS_SLIDE_MS          = 1250L  // was 750ms + 0.5s
    private const val VS_APPEAR_MS           = 850L   // was 350ms + 0.5s
    private const val CHARACTERS_SLIDE_MS    = 1250L  // was 750ms + 0.5s
    private const val TEAM_BALLS_SLIDE_MS     = 950L   // six staggered slots + one synchronized lineup sound
    private const val HOLD_DURATION_MS       = 2700L  // was 2200ms + 0.5s -- still the one to retune once you see the full thing
    private const val SLIDE_OUT_DURATION_MS  = 1200L  // was 700ms + 0.5s

    // ── Pending packet queues ───────────────────────────────────────────────
    // Split by owner so we can stagger the replay: the opponent's send-out
    // plays fully first, then the local player's sequence begins.
    private data class PendingAction(
        val label: String,
        val queuedAtMs: Long,
        val action: () -> Unit
    )

    /**
     * CORE packets initialize/update Cobblemon's battle model and GUI. They must
     * always replay before either trainer's throw/spawn sequence.
     *
     * SIDE queues contain only throw sound, spawn and cry/animation actions.
     */
    private val pendingCorePackets = java.util.concurrent.ConcurrentLinkedQueue<PendingAction>()
    private val pendingPlayerPackets = java.util.concurrent.ConcurrentLinkedQueue<PendingAction>()
    private val pendingOpponentPackets = java.util.concurrent.ConcurrentLinkedQueue<PendingAction>()

    private var introStartedAtMs = 0L
    private var debugSequence = 0L
    // Maps a spawned Pokemon's vanilla entity ID -> whether it's the player's,
    // populated when we see its SpawnPokemonPacket (which carries ownerId).
    // The later cry packet (PlayPosableAnimationPacket) only has an entity ID,
    // no owner -- this lets us classify it correctly by looking the ID up.
    private val entityOwnership = mutableMapOf<Int, Boolean>()

    // How long after the opponent's send-out queue starts before the local
    // player's begins. The opponent's sequence (throw, beam and delayed cry)
    // takes roughly two seconds, so this leaves a clean gap before our throw.
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

    // Compatibility overloads while every mixin is migrated.
    fun addPendingPlayerPacket(action: Runnable) =
        addPendingPlayerPacket("unlabelled-player", action)

    fun addPendingOpponentPacket(action: Runnable) =
        addPendingOpponentPacket("unlabelled-opponent", action)

    /**
     * Generic battle packets are not player-owned. They are battle-model/GUI
     * prerequisites and therefore belong to the CORE queue.
     */
    fun addPendingPacket(action: Runnable) =
        addPendingCorePacket("unlabelled-core", action)

    fun setPendingBattlePacket(action: Runnable) =
        addPendingCorePacket("BattleInitializePacket", action)

    fun registerPokemonOwnership(entityId: Int, isPlayerOwned: Boolean) { entityOwnership[entityId] = isPlayerOwned }
    fun isPlayerOwnedEntity(entityId: Int): Boolean? = entityOwnership[entityId]

    /** True if the given Pokemon UUID belongs to the opponent's party for this battle. */
    fun isOpponentPokemon(pokemonUUID: java.util.UUID): Boolean = pokemonUUID in opponentPokemonUUIDs
    /** True if the given Pokemon UUID belongs to the local player's party for this battle. */
    fun isLocalPokemon(pokemonUUID: java.util.UUID): Boolean = pokemonUUID in localPokemonUUIDs

    /**
     * For packets with no owner/UUID info at all (like the vanilla throw
     * sound, which only carries a position) -- classifies by proximity to
     * each trainer's actual position, since the sound is always played at
     * the throwing trainer's location.
     */
    fun isSoundNearPlayer(x: Double, y: Double, z: Double): Boolean {
        val player = localEntityRef ?: return true
        val opponent = opponentEntityRef
        val distPlayer = player.pos.squaredDistanceTo(x, y, z)
        val distOpponent = opponent?.pos?.squaredDistanceTo(x, y, z) ?: Double.MAX_VALUE
        return distPlayer <= distOpponent
    }

    // ── Battle context (resolved at trigger time) ─────────────────────────────
    private var localSkinId:    Identifier? = null
    private var opponentSkinId: Identifier? = null
    private var opponentName:   String = ""
    private var isOpponentPlayer: Boolean = false
    private var localEntityRef:    LivingEntity? = null
    private var opponentEntityRef: LivingEntity? = null

    // Captured directly from the battle actors' real party data at trigger
    // time -- used to classify spawn packets by the actual Pokemon UUID
    // (SpawnPokemonPacket.pokemonUUID), which is far more reliable than
    // matching ownerId against an entity's UUID (that assumption doesn't
    // reliably hold for RCT NPC trainers).
    private var localPokemonUUIDs = emptySet<java.util.UUID>()
    private var opponentPokemonUUIDs = emptySet<java.util.UUID>()

    // Six visual party slots per side. Occupied slots use the Pokémon's real
    // caughtBall item; missing party members use the bundled gray empty sprite.
    private var localBallStacks: List<ItemStack> = emptyList()
    private var opponentBallStacks: List<ItemStack> = emptyList()

    private val EMPTY_BALL_TEXTURE = Identifier.of("battleslider", "textures/gui/empty_party_ball.png")
    private const val EMPTY_BALL_TEXTURE_SIZE = 16
    private val TEAM_BALL_LINEUP_SOUND = Identifier.of("battleslider", "team_ball_lineup")

    private var topColorA: Int = 0  // dark end -- opponent bar, driven by RCT TrainerType.color() or PvP/default fallback
    private var topColorB: Int = 0  // light end

    // ── Particle system ───────────────────────────────────────────────────────
    private data class Particle(var x: Float, var y: Float, var w: Float, var speed: Float, var alpha: Float)
    private val topParticles  = mutableListOf<Particle>()
    private val botParticles  = mutableListOf<Particle>()

    // ── Palette (ARGB) ──────────────────────────────────────────────────────
    // Player bar -- FIXED light blue, always, regardless of opponent type.
    // Same saturated-dark-to-pale-white gradient style as the pink had.
    private val BOT_COLOR_A = argb(255, 0x1A, 0x8C, 0xE8)   // saturated sky blue
    private val BOT_COLOR_B = argb(255, 0xCE, 0xEE, 0xFF)   // pale ice blue/white

    // PvP opponent -- bright orange -> pale peach
    private val PVP_COLOR_A = argb(255, 0xE8, 0x6A, 0x00)
    private val PVP_COLOR_B = argb(255, 0xFF, 0xD8, 0x90)

    // Default trainer (fallback, e.g. RCT not loaded / TrainerType lookup failed) -- bright yellow -> near-white
    private val DEFAULT_COLOR_A = argb(255, 0xC8, 0xB4, 0x00)
    private val DEFAULT_COLOR_B = argb(255, 0xFF, 0xF6, 0xB8)

    // Metallic border
    private val BORDER_LIGHT = argb(255, 0xFF, 0xFF, 0xFF)
    private val BORDER_DARK  = argb(255, 0x88, 0x88, 0x88)

    private fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    // ── Registration ──────────────────────────────────────────────────────────
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

    // ── Trigger ───────────────────────────────────────────────────────────────
    fun trigger(localActor: BattleActor, opponentActor: BattleActor) {
        if (isFlushing) return
        val client = MinecraftClient.getInstance()

        localSkinId    = getSkinId(client.player)
        isOpponentPlayer = opponentActor is PlayerBattleActor
        localEntityRef = client.player

        val oppEntity = BattleHandler.resolveOpponentEntity(opponentActor)
        opponentSkinId = getSkinId(oppEntity)
        opponentEntityRef = oppEntity
        opponentName   = resolveOpponentName(opponentActor, oppEntity)

        localPokemonUUIDs = localActor.pokemonList.map { it.uuid }.toSet()
        opponentPokemonUUIDs = opponentActor.pokemonList.map { it.uuid }.toSet()
        localBallStacks = localActor.pokemonList.take(6).map { resolveBallStack(it.effectedPokemon.caughtBall) }
        opponentBallStacks = opponentActor.pokemonList.take(6).map { resolveBallStack(it.effectedPokemon.caughtBall) }

        // Resolve top-bar color from RCT TrainerType if available -- this is
        // where your per-tier (trainer/gym leader/E4/champion) colors come
        // from; we just read whatever TrainerType.color() returns and
        // brighten it for visual pop. The player's bar below is always the
        // fixed light blue regardless of this.
        resolveTopBarColor(oppEntity, isOpponentPlayer)

        // Seed particles -- stratified across rows so coverage is even
        seedParticles(topParticles)
        seedParticles(botParticles)

        pendingCorePackets.clear()
        pendingPlayerPackets.clear()
        pendingOpponentPackets.clear()
        entityOwnership.clear()
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

    private fun resolveTopBarColor(oppEntity: LivingEntity?, isPvP: Boolean) {
        if (isPvP) {
            topColorA = PVP_COLOR_A; topColorB = PVP_COLOR_B; return
        }
        if (FabricLoader.getInstance().isModLoaded("rctmod") && oppEntity != null) {
            try {
                val mobClass = Class.forName("com.gitlab.srcmc.rctmod.api.entity.TrainerMob")
                if (mobClass.isInstance(oppEntity)) {
                    val getData = mobClass.getMethod("getData")
                    val data = getData.invoke(oppEntity)
                    val getType = data.javaClass.getMethod("getType")
                    val trainerType = getType.invoke(data)
                    val colorMethod = trainerType.javaClass.getMethod("color")
                    val rgb = colorMethod.invoke(trainerType) as Int
                    topColorA = brighten(rgb, 0.7f)
                    topColorB = brighten(rgb, 1.5f)
                    return
                }
            } catch (_: Exception) {}
        }
        topColorA = DEFAULT_COLOR_A; topColorB = DEFAULT_COLOR_B
    }

    /** Blend an RGB int toward black (factor<1) or toward white (factor>1) */
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
        if (entity != null && FabricLoader.getInstance().isModLoaded("rctmod")) {
            try {
                val mobClass = Class.forName("com.gitlab.srcmc.rctmod.api.entity.TrainerMob")
                if (mobClass.isInstance(entity)) {
                    return entity.name.string
                }
            } catch (_: Exception) {}
        }
        return entity?.name?.string ?: "???"
    }

    private fun getSkinId(entity: LivingEntity?): Identifier? {
        if (entity is AbstractClientPlayerEntity) return entity.skinTextures.texture
        return null
    }


    /**
     * Resolves a Cobblemon PokeBall to its registered item without locking this
     * mod to one mapped accessor name. Cobblemon/addon versions have exposed the
     * item through item(), getItem(), asItem(), an Item field, or an Identifier.
     */
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

    /**
     * Plays one of Minecraft's built-in UI sounds without requiring an
     * additional bundled audio asset or sounds.json registration.
     */
    private fun playVanillaUiSound(sound: SoundEvent, pitch: Float = 1.0f) {
        val client = MinecraftClient.getInstance()
        try {
            client.soundManager.play(
                PositionedSoundInstance.master(sound, pitch)
            )
        } catch (e: Exception) {
            LOGGER.warn("Could not play vanilla UI sound: {}", e.message)
        }
    }

    private fun playTeamBallLineupSound() {
        val client = MinecraftClient.getInstance()
        try {
            client.soundManager.play(
                PositionedSoundInstance.master(SoundEvent.of(TEAM_BALL_LINEUP_SOUND), 1.0f)
            )
        } catch (e: Exception) {
            // Missing .ogg is harmless while the user is still sourcing audio.
            LOGGER.debug("Team-ball lineup sound unavailable: {}", e.message)
        }
    }

    // ── Main render ───────────────────────────────────────────────────────────
    private fun render(drawContext: DrawContext, tickDelta: Float) {
        val client = MinecraftClient.getInstance()
        val sw = client.window.scaledWidth
        val sh = client.window.scaledHeight

        val now = System.currentTimeMillis()
        val elapsed = if (lastTimeMs == 0L) 0L else now - lastTimeMs
        lastTimeMs = now

        when (state) {
            State.FLICKER -> {
                progress = (progress + elapsed.toFloat() / FLICKER_TOTAL_MS).coerceAtMost(1f)
                if (progress >= 1f) {
                    progress = 0f
                    state = State.BARS_SLIDE_IN
                    playVanillaUiSound(SoundEvents.UI_TOAST_IN)
                }
            }
            State.BARS_SLIDE_IN -> {
                progress = (progress + elapsed.toFloat() / BARS_SLIDE_MS).coerceAtMost(1f)
                if (progress >= 1f) { progress = 0f; state = State.VS_APPEAR }
            }
            State.VS_APPEAR -> {
                progress = (progress + elapsed.toFloat() / VS_APPEAR_MS).coerceAtMost(1f)
                if (progress >= 1f) { progress = 0f; state = State.CHARACTERS_SLIDE_IN }
            }
            State.CHARACTERS_SLIDE_IN -> {
                progress = (progress + elapsed.toFloat() / CHARACTERS_SLIDE_MS).coerceAtMost(1f)
                if (progress >= 1f) {
                    progress = 0f
                    state = State.TEAM_BALLS_SLIDE_IN
                    playTeamBallLineupSound()
                }
            }
            State.TEAM_BALLS_SLIDE_IN -> {
                progress = (progress + elapsed.toFloat() / TEAM_BALLS_SLIDE_MS).coerceAtMost(1f)
                if (progress >= 1f) { progress = 0f; state = State.HOLD }
            }
            State.HOLD -> {
                progress = (progress + elapsed.toFloat() / HOLD_DURATION_MS).coerceAtMost(1f)
                if (progress >= 1f) {
                    progress = 1f
                    state = State.SLIDING_OUT
                    playVanillaUiSound(SoundEvents.UI_TOAST_OUT)
                }
            }
            State.SLIDING_OUT -> {
                progress = (progress - elapsed.toFloat() / SLIDE_OUT_DURATION_MS).coerceAtLeast(0f)
                if (progress <= 0f) {
                    finishIntroAndFlushPackets()
                    return
                }
            }
            State.IDLE -> return
        }

        // ── Black backdrop -- 3 flicks (ramping, held black at the end), then
        // fades out as the bars slide in over it ────────────────────────────
        val blackAlpha = when (state) {
            State.FLICKER -> computeFlickerAlpha(progress)
            State.BARS_SLIDE_IN -> (255 * (1f - easeOutCubic(progress))).toInt()
            else -> 0
        }
        if (blackAlpha > 0) {
            drawContext.fill(0, 0, sw, sh, (blackAlpha shl 24))
        }
        if (state == State.FLICKER) {
            drawSkipPrompt(drawContext, sw, sh)
            return
        }

        // ── Bars progress -- BOTH bars now animate on ONE shared timeline,
        // simultaneously, in swapped directions (top enters from the left,
        // bottom enters from the right). SLIDING_OUT reuses this exact same
        // formula, so the exit is automatically a mirror of the entry.
        val barsT = when (state) {
            State.BARS_SLIDE_IN -> easeOutCubic(progress)
            State.VS_APPEAR, State.CHARACTERS_SLIDE_IN, State.TEAM_BALLS_SLIDE_IN, State.HOLD -> 1f
            State.SLIDING_OUT -> easeOutCubic(progress)
            else -> 0f
        }

        // Bar geometry
        val barH  = sh / 4
        val topY  = sh / 2 - barH - 1
        val botY  = sh / 2 + 1
        val borderThick = 2

        // ── Top bar -- LEFT to RIGHT (enters from the left, grows rightward) ───
        val topRight = (sw * barsT).toInt()
        drawJaggedBar(drawContext, 0, topRight, topY, topY + barH, topColorB, topColorA, leadingEdgeOnLeft = false)
        drawContext.fill(0, topY,              topRight, topY + borderThick, BORDER_LIGHT)
        drawContext.fill(0, topY + barH - borderThick, topRight, topY + barH, BORDER_DARK)

        // ── Bottom bar -- RIGHT to LEFT (enters from the right, grows leftward) ─
        val botLeft = (sw * (1f - barsT)).toInt()
        drawJaggedBar(drawContext, botLeft, sw, botY, botY + barH, BOT_COLOR_A, BOT_COLOR_B, leadingEdgeOnLeft = true)
        drawContext.fill(botLeft, botY,              sw, botY + borderThick, BORDER_LIGHT)
        drawContext.fill(botLeft, botY + barH - borderThick, sw, botY + barH, BORDER_DARK)

        // ── Speed-line particles ──────────────────────────────────────────────
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

        // ── VS graphic -- pops in during VS_APPEAR, stays through HOLD, drops
        // out partway through SLIDING_OUT (same threshold as before) ───────────
        val vsT = when (state) {
            State.VS_APPEAR -> easeOutCubic(progress)
            State.CHARACTERS_SLIDE_IN, State.TEAM_BALLS_SLIDE_IN, State.HOLD -> 1f
            State.SLIDING_OUT -> if (progress > 0.4f) 1f else 0f
            else -> 0f
        }
        if (vsT > 0f) drawVS(drawContext, sw, sh, vsT)

        // ── Name badges -- once the bars are fully formed (VS_APPEAR onward).
        // Positioned relative to the bar's OWN current edge (topRight/botLeft)
        // rather than the fixed screen edge, so during SLIDING_OUT they
        // retract together with the bar instead of sitting orphaned over
        // now-visible world background.
        if (state != State.BARS_SLIDE_IN && opponentName.isNotEmpty()) {
            drawOpponentBadge(drawContext, topRight, topY)
            drawPlayerBadge(drawContext, botLeft, topY, barH)
        }

        // ── Character portraits -- slide in AFTER VS appears (trainer L->R on
        // top, player R->L on bottom), and retract in sync with the bars
        // during SLIDING_OUT (reusing barsT directly, the same value driving
        // the bar retraction) rather than staying frozen at their resting
        // spot while the bar disappears out from under them.
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

            val oppEntity = opponentEntityRef ?: localEntityRef
            if (oppEntity != null) {
                TrainerSkinRenderer.render(drawContext,
                    opponentSkinId, TrainerSkinRenderer.Pose.OPPONENT,
                    oppX, topY, topY + barH, oppEntity)
            }
            val plrEntity = localEntityRef
            if (plrEntity != null) {
                TrainerSkinRenderer.render(drawContext,
                    localSkinId, TrainerSkinRenderer.Pose.PLAYER,
                    plrX, botY, botY + barH, plrEntity)
            }
        }


        // ── Party-ball rows ───────────────────────────────────────────────────
        // This phase starts only after both portraits are fully settled. Each
        // side always has six slots. Occupied slots render the actual caughtBall
        // item; missing members use a gray inactive sprite.
        val teamBallsT = when (state) {
            State.TEAM_BALLS_SLIDE_IN -> progress
            State.HOLD -> 1f
            State.SLIDING_OUT -> 1f
            else -> 0f
        }
        if (teamBallsT > 0f) {
            drawTeamBalls(
                ctx = drawContext,
                stacks = opponentBallStacks,
                isOpponent = true,
                sw = sw,
                barTop = topY,
                barHeight = barH,
                phaseProgress = teamBallsT,
                exitProgress = if (state == State.SLIDING_OUT) barsT else 1f
            )
            drawTeamBalls(
                ctx = drawContext,
                stacks = localBallStacks,
                isOpponent = false,
                sw = sw,
                barTop = botY,
                barHeight = barH,
                phaseProgress = teamBallsT,
                exitProgress = if (state == State.SLIDING_OUT) barsT else 1f
            )
        }

        if (state != State.SLIDING_OUT) {
            drawSkipPrompt(drawContext, sw, sh)
        }
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
        // Both Minecraft item icons and the custom empty sprite are authored
        // in a logical 16x16 GUI space. Keeping them native avoids fractional
        // scaling artifacts and guarantees identical slot proportions.
        val slotSize = 16
        val gap = 5
        val rowWidth = slotSize * 6 + gap * 5

        // Party rows occupy the area opposite their trainer portrait:
        // - Opponent portrait rests on the right, so its row settles on the left.
        // - Player portrait rests on the left, so its row settles on the right.
        //
        // Keep both rows away from the center VS emblem and from the name badges.
        val baseStart = if (isOpponent) {
            // Left-side empty area: roughly 1/8 → 3/8 of the screen.
            (sw * 3 / 8 - rowWidth).coerceAtLeast(24)
        } else {
            // Right-side empty area: roughly 5/8 → 7/8 of the screen.
            (sw * 5 / 8).coerceAtMost(sw - rowWidth - 24)
        }
        val y = barTop + (barHeight - slotSize) / 2

        for (slot in 0 until 6) {
            // Opponent reveals left→right. Player mirrors it right→left.
            val revealOrder = if (isOpponent) slot else 5 - slot
            val staggerStart = revealOrder * 0.075f
            val localT = ((phaseProgress - staggerStart) / (1f - 5f * 0.075f))
                .coerceIn(0f, 1f)
            if (localT <= 0f) continue

            val eased = easeOutBack(localT)
            val finalX = baseStart + slot * (slotSize + gap)
            val introOffset = if (isOpponent) {
                // Opponent row enters from the left and travels right.
                -((1f - eased) * 72f).toInt()
            } else {
                // Player row enters from the right and travels left.
                ((1f - eased) * 72f).toInt()
            }

            // Top bar exits left; bottom bar exits right.
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
        // size is intentionally fixed at 16 in drawTeamBalls().
        ctx.drawItem(stack, x, y)
    }

    /** Slight overshoot, then settle — the small GBA-style ball "snap". */
    private fun easeOutBack(t: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val x = t - 1f
        return 1f + c3 * x * x * x + c1 * x * x
    }

    /**
     * Small bottom-right hint using the key's actual current binding, so the
     * displayed label stays correct when the player remaps the control.
     */
    private fun drawSkipPrompt(ctx: DrawContext, sw: Int, sh: Int) {
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

    /** Every flick now reaches true pitch black at its peak (fading in/out via a sine curve); the final flick ramps up and holds instead of fading back down. */
    private fun computeFlickerAlpha(overallProgress: Float): Int {
        val flickPhase = (overallProgress * FLICKER_COUNT).coerceAtMost(FLICKER_COUNT.toFloat() - 0.0001f)
        val cycleIndex = flickPhase.toInt().coerceIn(0, FLICKER_COUNT - 1)
        val cycleProgress = (flickPhase - cycleIndex).coerceIn(0f, 1f)
        val isLastFlick = cycleIndex == FLICKER_COUNT - 1

        val pulse = if (isLastFlick) {
            cycleProgress                                   // ramp up and hold at black
        } else {
            sin((cycleProgress * Math.PI).toFloat()).coerceIn(0f, 1f)   // up to full black, then back down
        }
        return (pulse * 255).toInt().coerceIn(0, 255)
    }

    // ── Fast pixelated gradient ───────────────────────────────────────────────
    // The original renderer issued one fill() call for every 4x4 pixel cell,
    // creating tens of thousands of draw calls per frame at high resolutions.
    // This version keeps the blocky/dithered appearance using a fixed grid of
    // broad strips: 48 horizontal color steps x 6 vertical dither bands.
    // That caps each bar at 288 gradient fills regardless of GUI resolution.
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

    // ── Jagged/torn leading edge -- pixelated bumps approximating a torn-paper
    // cut, in the same chunky style as the dithered gradient itself ──────────
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

    // ── Speed-line particles ──────────────────────────────────────────────────
    // Stratified by row -- guarantees particles across every vertical band of
    // the bar instead of relying on pure random placement, which (with only a
    // handful of particles) can leave a visible empty patch by chance.
    private const val PARTICLE_ROWS     = 6
    private const val PARTICLES_PER_ROW = 5   // 30 particles per bar total

    private fun randomParticle(row: Int): Particle = Particle(
        x = Random.nextFloat(),
        y = ((row + Random.nextFloat()) / PARTICLE_ROWS).coerceIn(0f, 1f),
        w = 0.04f + Random.nextFloat() * 0.12f,
        speed = 0.18f + Random.nextFloat() * 0.35f,
        alpha = 0.4f + Random.nextFloat() * 0.5f
    )

    private fun seedParticles(list: MutableList<Particle>) {
        list.clear()
        repeat(PARTICLE_ROWS) { row -> repeat(PARTICLES_PER_ROW) { list.add(randomParticle(row)) } }
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

    // ── VS graphic — large italic with 1px black outline, scales in via vsT ──
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

    // ── Name badges — metallic rectangle with white text ──────────────────────
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

    /** Draws a dark metallic filled rectangle with a 2px light top border and 2px dark bottom border */
    private fun drawMetallicBadge(ctx: DrawContext, x1: Int, y1: Int, x2: Int, y2: Int) {
        ctx.fill(x1, y1, x2, y2, argb(210, 0x1A, 0x1A, 0x1A))
        ctx.fill(x1, y1, x2, y1 + 2, BORDER_LIGHT)
        ctx.fill(x1, y2 - 2, x2, y2, BORDER_DARK)
        ctx.fill(x1, y1, x1 + 2, y2, BORDER_LIGHT)
        ctx.fill(x2 - 2, y1, x2, y2, BORDER_DARK)
    }

    // ── Easing ────────────────────────────────────────────────────────────────
    private fun easeOutCubic(t: Float): Float = 1f - (1f - t).pow(3)

    // ── Public API ────────────────────────────────────────────────────────────
    fun isAnimating(): Boolean = state != State.IDLE && !isFlushing

    /**
     * The intro may only be skipped before its normal exit has started.
     * Once SLIDING_OUT begins, packet replay is already imminent and accepting
     * another skip request could interfere with the normal completion path.
     */
    fun canSkip(): Boolean =
        state != State.IDLE && state != State.SLIDING_OUT && !isFlushing

    /**
     * Fast-forwards only the VISUAL intro to its normal slide-out phase.
     *
     * We intentionally do not flush packets directly here. A key press can happen
     * while Cobblemon is still delivering and our mixins are still collecting the
     * battle GUI/send-out packets. Flushing at that instant can replay an incomplete
     * packet set and leave the battle GUI partially initialized or softlocked.
     *
     * By entering SLIDING_OUT and letting the ordinary completion path call
     * finishIntroAndFlushPackets(), the remaining 1.2 seconds act as a safe packet
     * collection window. The normal opponent-first, player-after-2.5s stagger is
     * therefore preserved for both natural completion and skipped intros.
     */
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

    /**
     * Single completion path shared by normal animation completion and skipping.
     * Keeping packet replay here prevents the two paths from drifting apart.
     */
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

    /**
     * Replay order is deliberately:
     *
     * 1. CORE battle-model / GUI packets
     * 2. Opponent throw, spawn and cry
     * 3. Local-player throw, spawn and cry after the configured stagger
     *
     * Previously generic packets, including BattleInitializePacket, shared the
     * delayed PLAYER queue. That allowed opponent entity/spawn packets to replay
     * before Cobblemon's client battle model existed, explaining the intermittent
     * incomplete GUI and softlock after a skip.
     */
    private fun finishIntroAndFlushPackets() {
        if (state == State.IDLE || isFlushing) return

        isFlushing = true

        // Snapshot every queue before any replay starts. Packets that arrive after
        // this point are no longer intercepted because isAnimating() is false.
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

        // Stop suppressing Cobblemon rendering before replaying BattleInitialize.
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
        localPokemonUUIDs = emptySet()
        opponentPokemonUUIDs = emptySet()
        localBallStacks = emptyList()
        opponentBallStacks = emptyList()
        topParticles.clear()
        botParticles.clear()

        /*
         * Do not clear entityOwnership yet. The delayed player cry packet may
         * still consult it during replay. It is reset at the next trigger().
         */
        isFlushing = false

        debugLog(
            "[t+{}ms] FLUSH dispatch complete | delayedPlayerActions={}",
            elapsedDebugMs(),
            playerBatch.size
        )
    }

    fun getRemainingAnimationMs(): Long = when (state) {
        State.FLICKER              -> ((1f - progress) * FLICKER_TOTAL_MS).toLong() + BARS_SLIDE_MS + VS_APPEAR_MS + CHARACTERS_SLIDE_MS + TEAM_BALLS_SLIDE_MS + HOLD_DURATION_MS + SLIDE_OUT_DURATION_MS
        State.BARS_SLIDE_IN        -> ((1f - progress) * BARS_SLIDE_MS).toLong() + VS_APPEAR_MS + CHARACTERS_SLIDE_MS + TEAM_BALLS_SLIDE_MS + HOLD_DURATION_MS + SLIDE_OUT_DURATION_MS
        State.VS_APPEAR            -> ((1f - progress) * VS_APPEAR_MS).toLong() + CHARACTERS_SLIDE_MS + TEAM_BALLS_SLIDE_MS + HOLD_DURATION_MS + SLIDE_OUT_DURATION_MS
        State.CHARACTERS_SLIDE_IN  -> ((1f - progress) * CHARACTERS_SLIDE_MS).toLong() + TEAM_BALLS_SLIDE_MS + HOLD_DURATION_MS + SLIDE_OUT_DURATION_MS
        State.TEAM_BALLS_SLIDE_IN -> ((1f - progress) * TEAM_BALLS_SLIDE_MS).toLong() + HOLD_DURATION_MS + SLIDE_OUT_DURATION_MS
        State.HOLD                 -> ((1f - progress) * HOLD_DURATION_MS).toLong() + SLIDE_OUT_DURATION_MS
        State.SLIDING_OUT          -> (progress * SLIDE_OUT_DURATION_MS).toLong()
        State.IDLE                 -> 0L
    }
}