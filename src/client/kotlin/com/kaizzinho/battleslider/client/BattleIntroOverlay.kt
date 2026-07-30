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
import net.minecraft.util.Identifier
import com.cobblemon.mod.common.api.scheduling.afterOnClient
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

@Environment(EnvType.CLIENT)
object BattleIntroOverlay {

    // ── State machine ─────────────────────────────────────────────────────────
    // FLICKER: 3 black flicks, ramping in intensity, ending held at full black
    // BARS_SLIDE_IN: both bars slide in simultaneously (top L->R, bottom R->L)
    // VS_APPEAR: VS graphic pops in
    // CHARACTERS_SLIDE_IN: trainer portrait slides L->R, player portrait R->L
    // HOLD: everything held on screen
    // SLIDING_OUT: both bars exit together (unchanged mechanism from before --
    //   it reuses the same position formulas as slide-in, so it automatically
    //   reverses along whichever direction the bars now enter from)
    enum class State { IDLE, FLICKER, BARS_SLIDE_IN, VS_APPEAR, CHARACTERS_SLIDE_IN, HOLD, SLIDING_OUT }

    var state = State.IDLE
    private var progress = 0f
    private var lastTimeMs = 0L
    private var isFlushing = false

    // ── Timing ───────────────────────────────────────────────────────────────
    // Total elapsed time from trigger() to the cry actually sounding =
    //   FLICKER_TOTAL_MS + BARS_SLIDE_MS + VS_APPEAR_MS + CHARACTERS_SLIDE_MS
    //   + HOLD_DURATION_MS + SLIDE_OUT_DURATION_MS + 1500 (cry delay in the mixin)
    // With the defaults below that's ~11s total. This is intentionally on the
    // slow/generous side per your request to see the full timing -- HOLD is
    // the one knob with real slack once you know where your music lands.
    private const val FLICKER_COUNT          = 7      // faster strobe -- more flicks packed into the same held total
    private const val FLICKER_TOTAL_MS       = 2250L  // duration held as-is per your request
    private const val BARS_SLIDE_MS          = 1250L  // was 750ms + 0.5s
    private const val VS_APPEAR_MS           = 850L   // was 350ms + 0.5s
    private const val CHARACTERS_SLIDE_MS    = 1250L  // was 750ms + 0.5s
    private const val HOLD_DURATION_MS       = 2700L  // was 2200ms + 0.5s -- still the one to retune once you see the full thing
    private const val SLIDE_OUT_DURATION_MS  = 1200L  // was 700ms + 0.5s

    // ── Pending packet queues ───────────────────────────────────────────────
    // Split by owner so we can stagger the replay: player's send-out plays
    // out fully (including cry) before the opponent's begins, matching the
    // classic "you send out, THEN they send out" game pacing.
    private val pendingPlayerPackets = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>()
    private val pendingOpponentPackets = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>()
    // Maps a spawned Pokemon's vanilla entity ID -> whether it's the player's,
    // populated when we see its SpawnPokemonPacket (which carries ownerId).
    // The later cry packet (PlayPosableAnimationPacket) only has an entity ID,
    // no owner -- this lets us classify it correctly by looking the ID up.
    private val entityOwnership = mutableMapOf<Int, Boolean>()

    // How long after the player's send-out queue starts before the opponent's
    // begins. Player's own sequence (ball open + the 1.5s cry delay in
    // PlayPosableAnimationHandlerMixin) takes roughly ~2s, so this leaves a
    // comfortable gap for it to fully finish, cry included, first.
    private const val OPPONENT_STAGGER_DELAY_S = 2.5f

    fun addPendingPlayerPacket(action: Runnable)   { pendingPlayerPackets.offer { action.run() } }
    fun addPendingOpponentPacket(action: Runnable) { pendingOpponentPackets.offer { action.run() } }
    // Fallback for anything that can't be classified by owner -- goes with the player's batch.
    fun addPendingPacket(action: Runnable) { pendingPlayerPackets.offer { action.run() } }
    fun setPendingBattlePacket(action: Runnable) { pendingPlayerPackets.offer { action.run() } }

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
        HudRenderCallback.EVENT.register { drawContext, tickCounter ->
            if (state != State.IDLE) render(drawContext, tickCounter.getTickDelta(true))
        }
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

        // Resolve top-bar color from RCT TrainerType if available -- this is
        // where your per-tier (trainer/gym leader/E4/champion) colors come
        // from; we just read whatever TrainerType.color() returns and
        // brighten it for visual pop. The player's bar below is always the
        // fixed light blue regardless of this.
        resolveTopBarColor(oppEntity, isOpponentPlayer)

        // Seed particles -- stratified across rows so coverage is even
        seedParticles(topParticles)
        seedParticles(botParticles)

        progress   = 0f
        lastTimeMs = 0L
        state      = State.FLICKER
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
                if (progress >= 1f) { progress = 0f; state = State.BARS_SLIDE_IN }
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
                if (progress >= 1f) { progress = 0f; state = State.HOLD }
            }
            State.HOLD -> {
                progress = (progress + elapsed.toFloat() / HOLD_DURATION_MS).coerceAtMost(1f)
                if (progress >= 1f) { progress = 1f; state = State.SLIDING_OUT }
            }
            State.SLIDING_OUT -> {
                progress = (progress - elapsed.toFloat() / SLIDE_OUT_DURATION_MS).coerceAtLeast(0f)
                if (progress <= 0f) {
                    isFlushing = true
                    var playerAction = pendingPlayerPackets.poll()
                    while (playerAction != null) { playerAction.invoke(); playerAction = pendingPlayerPackets.poll() }
                    isFlushing = false

                    // Opponent's send-out is staggered to start after the
                    // player's has had time to fully play out (including cry).
                    val opponentBatch = ArrayList<() -> Unit>()
                    var opponentAction = pendingOpponentPackets.poll()
                    while (opponentAction != null) { opponentBatch.add(opponentAction); opponentAction = pendingOpponentPackets.poll() }
                    if (opponentBatch.isNotEmpty()) {
                        afterOnClient(OPPONENT_STAGGER_DELAY_S) {
                            opponentBatch.forEach { it.invoke() }
                        }
                    }

                    state = State.IDLE
                    localSkinId = null; opponentSkinId = null
                    localEntityRef = null; opponentEntityRef = null
                    localPokemonUUIDs = emptySet(); opponentPokemonUUIDs = emptySet()
                    topParticles.clear(); botParticles.clear()
                    entityOwnership.clear()
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
        if (state == State.FLICKER) return   // nothing else to draw during the flicker

        // ── Bars progress -- BOTH bars now animate on ONE shared timeline,
        // simultaneously, in swapped directions (top enters from the left,
        // bottom enters from the right). SLIDING_OUT reuses this exact same
        // formula, so the exit is automatically a mirror of the entry.
        val barsT = when (state) {
            State.BARS_SLIDE_IN -> easeOutCubic(progress)
            State.VS_APPEAR, State.CHARACTERS_SLIDE_IN, State.HOLD -> 1f
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
            State.CHARACTERS_SLIDE_IN, State.HOLD -> 1f
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
            State.HOLD -> 1f
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

    // ── Pixelated dithered gradient ───────────────────────────────────────────
    private fun drawPixelGradient(
        ctx: DrawContext, x1: Int, y1: Int, x2: Int, y2: Int,
        colorA: Int, colorB: Int, horizontal: Boolean
    ) {
        val w = x2 - x1; val h = y2 - y1
        if (w <= 0 || h <= 0) return
        val pixSize = 4
        val xBlocks = (w + pixSize - 1) / pixSize
        val yBlocks = (h + pixSize - 1) / pixSize

        for (bx in 0 until xBlocks) {
            for (by in 0 until yBlocks) {
                val t = if (horizontal) bx.toFloat() / xBlocks.coerceAtLeast(1)
                else by.toFloat() / yBlocks.coerceAtLeast(1)

                val dither = BAYER4[by % 4][bx % 4] / 16f
                val tf = (t + (dither - 0.5f) * 0.18f).coerceIn(0f, 1f)

                val color = lerpColor(colorA, colorB, tf)
                val px = x1 + bx * pixSize
                val py = y1 + by * pixSize
                ctx.fill(px, py, (px + pixSize).coerceAtMost(x2), (py + pixSize).coerceAtMost(y2), color)
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
     * Skips straight to the exit phase -- cuts short whatever's currently
     * playing (flicker, bars-in, VS, characters-in, hold) but keeps the
     * SLIDING_OUT exit itself intact, so it still reads as a smooth ending
     * rather than an abrupt cut. Call from a keybinding.
     */
    fun skip() {
        if (state == State.IDLE || state == State.SLIDING_OUT) return
        state = State.SLIDING_OUT
        progress = 1f
        lastTimeMs = 0L
    }

    fun getRemainingAnimationMs(): Long = when (state) {
        State.FLICKER              -> ((1f - progress) * FLICKER_TOTAL_MS).toLong() + BARS_SLIDE_MS + VS_APPEAR_MS + CHARACTERS_SLIDE_MS + HOLD_DURATION_MS + SLIDE_OUT_DURATION_MS
        State.BARS_SLIDE_IN        -> ((1f - progress) * BARS_SLIDE_MS).toLong() + VS_APPEAR_MS + CHARACTERS_SLIDE_MS + HOLD_DURATION_MS + SLIDE_OUT_DURATION_MS
        State.VS_APPEAR            -> ((1f - progress) * VS_APPEAR_MS).toLong() + CHARACTERS_SLIDE_MS + HOLD_DURATION_MS + SLIDE_OUT_DURATION_MS
        State.CHARACTERS_SLIDE_IN  -> ((1f - progress) * CHARACTERS_SLIDE_MS).toLong() + HOLD_DURATION_MS + SLIDE_OUT_DURATION_MS
        State.HOLD                 -> ((1f - progress) * HOLD_DURATION_MS).toLong() + SLIDE_OUT_DURATION_MS
        State.SLIDING_OUT          -> (progress * SLIDE_OUT_DURATION_MS).toLong()
        State.IDLE                 -> 0L
    }
}