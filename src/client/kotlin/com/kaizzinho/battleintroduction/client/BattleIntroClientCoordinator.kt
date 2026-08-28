package com.kaizzinho.battleintroduction.client

import com.cobblemon.mod.common.api.battles.model.actor.ActorType
import com.cobblemon.mod.common.client.CobblemonClient
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.cobblemon.mod.common.net.messages.client.battle.BattleInitializePacket
import com.cobblemon.mod.common.pokemon.Pokemon
import com.kaizzinho.battleintroduction.BattleIntroKind
import com.kaizzinho.battleintroduction.BattleIntroStartPayload
import com.kaizzinho.battleintroduction.client.config.BattleIntroductionConfig
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.MinecraftClient
import net.minecraft.entity.LivingEntity
import net.minecraft.item.ItemStack
import net.minecraft.registry.Registries
import net.minecraft.util.Identifier
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object BattleIntroClientCoordinator {

    private const val DESCRIPTOR_TTL_MS = 10_000L
    private const val STARTED_TTL_MS = 120_000L

    private val logger =
        LoggerFactory.getLogger(
            "battleintroduction/ClientCoordinator"
        )

    private data class PendingDescriptor(
        val payload: BattleIntroStartPayload,
        val receivedAtMs: Long
    )

    private data class StartContext(
        val packet: BattleInitializePacket,
        val descriptor: BattleIntroStartPayload?,
        val opponentActor: BattleInitializePacket.BattleActorDTO,
        val opponentEntity: LivingEntity?,
        val source: String
    )

    private data class WildPresentation(
        val raid: RaidDensCompat.RaidPresentation? = null,
        val boss: WildBossesCompat.BossPresentation? = null,
        val special: SpecialWildPokemonResolver.Presentation? = null
    )

    private val authoritative =
        ConcurrentHashMap<UUID, PendingDescriptor>()

    private val started =
        ConcurrentHashMap<UUID, Long>()

    private var registered = false

    fun register() {
        if (registered) {
            return
        }

        registered = true

        ClientPlayNetworking.registerGlobalReceiver(
            BattleIntroStartPayload.ID
        ) { payload, _ ->
            onAuthoritative(payload)
        }

        logger.info(
            "Battle intro client packet coordinator registered"
        )
    }

    fun onBattleInitialize(
        packet: BattleInitializePacket,
        client: MinecraftClient
    ): Boolean {
        cleanup()

        if (!canAttempt(packet.battleId)) {
            return false
        }

        val descriptor =
            authoritative.remove(
                packet.battleId
            )?.payload
        val context =
            buildContext(
                packet = packet,
                descriptor = descriptor,
                client = client
            ) ?: return false
        val startedNow =
            startSupported(context)

        debugLog(
            "BattleInitialize observed battle={} descriptor={} source={} started={}",
            packet.battleId,
            descriptor?.kind ?: "<none>",
            context.source,
            startedNow
        )

        return startedNow
    }

    private fun onAuthoritative(
        payload: BattleIntroStartPayload
    ) {
        cleanup()

        if (started.containsKey(payload.battleId)) {
            return
        }

        authoritative[payload.battleId] =
            PendingDescriptor(
                payload = payload,
                receivedAtMs =
                    System.currentTimeMillis()
            )

        debugLog(
            "Authoritative intro descriptor queued battle={} kind={}",
            payload.battleId,
            payload.kind
        )
    }

    private fun canAttempt(
        battleId: UUID
    ): Boolean =
        BattleIntroductionConfig.enableBattleIntros &&
            !started.containsKey(battleId)

    private fun buildContext(
        packet: BattleInitializePacket,
        descriptor: BattleIntroStartPayload?,
        client: MinecraftClient
    ): StartContext? {
        val opponentActor =
            resolveOpponentActor(
                packet,
                client
            ) ?: return null
        val opponentEntity =
            resolveOpponentEntity(
                client = client,
                opponentActor = opponentActor,
                descriptor = descriptor
            )

        return StartContext(
            packet = packet,
            descriptor = descriptor,
            opponentActor = opponentActor,
            opponentEntity = opponentEntity,
            source =
                if (descriptor == null) {
                    "client_fallback"
                } else {
                    "server"
                }
        )
    }

    private fun startSupported(
        context: StartContext
    ): Boolean {
        val kind =
            context.descriptor?.kind

        return if (kind == null) {
            startFallback(context)
        } else {
            startAuthoritative(
                context,
                kind
            )
        }
    }

    private fun startAuthoritative(
        context: StartContext,
        kind: BattleIntroKind
    ): Boolean =
        when (kind) {
            BattleIntroKind.PVP ->
                startPvP(context)

            BattleIntroKind.TRAINER ->
                startTrainer(context)

            BattleIntroKind.RAID,
            BattleIntroKind.WILD_BOSS,
            BattleIntroKind.LEGENDARY,
            BattleIntroKind.MYTHICAL ->
                startWild(
                    context,
                    kind
                )
        }

    private fun startFallback(
        context: StartContext
    ): Boolean =
        when (context.opponentActor.type) {
            ActorType.PLAYER ->
                startPvP(context)

            ActorType.WILD ->
                startWild(
                    context,
                    null
                )

            else ->
                startTrainer(context)
        }

    private fun startPvP(
        context: StartContext
    ): Boolean {
        if (!BattleIntroductionConfig.pvpBattleIntros) {
            return false
        }

        return startIntro(
            context,
            WildPresentation()
        )
    }

    private fun startTrainer(
        context: StartContext
    ): Boolean {
        if (!BattleIntroductionConfig.trainerBattleIntros) {
            return false
        }

        return startIntro(
            context,
            WildPresentation()
        )
    }

    private fun startWild(
        context: StartContext,
        kind: BattleIntroKind?
    ): Boolean {
        val presentation =
            resolveWildPresentation(
                context,
                kind
            ) ?: return false

        return startIntro(
            context,
            presentation
        )
    }

    private fun resolveWildPresentation(
        context: StartContext,
        kind: BattleIntroKind?
    ): WildPresentation? =
        if (kind == null) {
            resolveFallbackWild(context)
        } else {
            resolveAuthoritativeWild(
                context,
                kind
            )
        }

    private fun resolveFallbackWild(
        context: StartContext
    ): WildPresentation? {
        val entity =
            context.opponentEntity

        resolveFallbackRaid(entity)?.let {
            return WildPresentation(
                raid = it
            )
        }

        resolveFallbackBoss(entity)?.let {
            return WildPresentation(
                boss = it
            )
        }

        return resolveFallbackSpecial(entity)
            ?.let {
                WildPresentation(
                    special = it
                )
            }
    }

    private fun resolveAuthoritativeWild(
        context: StartContext,
        kind: BattleIntroKind
    ): WildPresentation? {
        val entity =
            context.opponentEntity
                as? PokemonEntity
                ?: return null
        val descriptor =
            context.descriptor
                ?: return null

        return when (kind) {
            BattleIntroKind.RAID ->
                authoritativeRaid(
                    entity,
                    descriptor
                )

            BattleIntroKind.WILD_BOSS ->
                authoritativeBoss(
                    entity,
                    descriptor
                )

            BattleIntroKind.LEGENDARY ->
                authoritativeSpecial(
                    entity,
                    descriptor,
                    SpecialWildPokemonResolver.Role.LEGENDARY
                )

            BattleIntroKind.MYTHICAL ->
                authoritativeSpecial(
                    entity,
                    descriptor,
                    SpecialWildPokemonResolver.Role.MYTHICAL
                )

            else ->
                null
        }
    }

    private fun resolveFallbackRaid(
        entity: LivingEntity?
    ): RaidDensCompat.RaidPresentation? {
        if (!BattleIntroductionConfig.raidDenBattleIntros) {
            return null
        }

        return RaidDensCompat.resolveClientEntity(
            entity
        )
    }

    private fun resolveFallbackBoss(
        entity: LivingEntity?
    ): WildBossesCompat.BossPresentation? {
        if (!BattleIntroductionConfig.wildBossBattleIntros) {
            return null
        }

        return WildBossesCompat.resolveEntity(
            entity
        )
    }

    private fun resolveFallbackSpecial(
        entity: LivingEntity?
    ): SpecialWildPokemonResolver.Presentation? =
        SpecialWildPokemonResolver
            .resolveEntity(entity)
            ?.takeIf(::specialEnabled)

    private fun authoritativeRaid(
        entity: PokemonEntity,
        descriptor: BattleIntroStartPayload
    ): WildPresentation? {
        if (!BattleIntroductionConfig.raidDenBattleIntros) {
            return null
        }

        return WildPresentation(
            raid =
                RaidDensCompat.fromAuthoritative(
                    entity = entity,
                    stars = descriptor.detail,
                    typeColorRgb = descriptor.colorRgb
                )
        )
    }

    private fun authoritativeBoss(
        entity: PokemonEntity,
        descriptor: BattleIntroStartPayload
    ): WildPresentation? {
        if (!BattleIntroductionConfig.wildBossBattleIntros) {
            return null
        }

        return WildPresentation(
            boss =
                WildBossesCompat.fromAuthoritative(
                    entity = entity,
                    tierName = descriptor.detail,
                    scaledLevel = descriptor.level
                )
        )
    }

    private fun authoritativeSpecial(
        entity: PokemonEntity,
        descriptor: BattleIntroStartPayload,
        role: SpecialWildPokemonResolver.Role
    ): WildPresentation? {
        if (!specialEnabled(role)) {
            return null
        }

        return WildPresentation(
            special =
                SpecialWildPokemonResolver.fromAuthoritative(
                    entity = entity,
                    role = role,
                    primaryTypeId = descriptor.detail
                )
        )
    }

    private fun specialEnabled(
        presentation: SpecialWildPokemonResolver.Presentation
    ): Boolean =
        specialEnabled(presentation.role)

    private fun specialEnabled(
        role: SpecialWildPokemonResolver.Role
    ): Boolean =
        when (role) {
            SpecialWildPokemonResolver.Role.LEGENDARY ->
                BattleIntroductionConfig.legendaryBattleIntros

            SpecialWildPokemonResolver.Role.MYTHICAL ->
                BattleIntroductionConfig.mythicalBattleIntros
        }

    private fun startIntro(
        context: StartContext,
        presentation: WildPresentation
    ): Boolean {
        if (BattleIntroOverlay.isAnimating()) {
            return false
        }

        val triggerData =
            buildTriggerData(context)

        started[context.packet.battleId] =
            System.currentTimeMillis()

        BattleIntroOverlay.triggerClient(
            data = triggerData,
            raid = presentation.raid,
            wildBoss = presentation.boss,
            specialWild = presentation.special
        )

        if (!BattleIntroOverlay.isAnimating()) {
            started.remove(
                context.packet.battleId
            )
            return false
        }

        logStarted(
            context,
            presentation
        )

        return true
    }

    private fun buildTriggerData(
        context: StartContext
    ): BattleIntroOverlay.ClientTriggerData {
        val localParty =
            localParty()

        return BattleIntroOverlay.ClientTriggerData(
            isOpponentPlayer =
                context.opponentActor.type ==
                    ActorType.PLAYER,
            opponentName =
                opponentName(context),
            opponentEntity =
                context.opponentEntity,
            localPokemonUUIDs =
                localParty
                    .map { it.uuid }
                    .toSet(),
            opponentPokemonUUIDs =
                opponentPokemonUuids(context),
            localBallStacks =
                localParty
                    .take(6)
                    .map(::ballStack),
            opponentBallStacks =
                opponentBallStacks(context),
            trainerClassification =
                RctTrainerMetadataResolver
                    .resolveEntity(
                        context.opponentEntity
                    ),
            debugOpponentType =
                "${context.source}/${context.descriptor?.kind ?: context.opponentActor.type}"
        )
    }

    private fun localParty(): List<Pokemon> =
        CobblemonClient.storage
            .party
            .slots
            .filterNotNull()

    private fun ballStack(
        pokemon: Pokemon
    ): ItemStack =
        ItemStack(
            pokemon.caughtBall.item()
        )

    private fun opponentPokemonUuids(
        context: StartContext
    ): Set<UUID> {
        val authoritativeUuids =
            context.descriptor
                ?.opponentPokemonUuids
                .orEmpty()

        if (authoritativeUuids.isNotEmpty()) {
            return authoritativeUuids.toSet()
        }

        return context.opponentActor
            .activePokemon
            .mapNotNull { it?.uuid }
            .toSet()
    }

    private fun opponentBallStacks(
        context: StartContext
    ): List<ItemStack> {
        val authoritativeBalls =
            context.descriptor
                ?.opponentBallItemIds
                .orEmpty()
                .mapNotNull(::ballStackFromId)

        if (authoritativeBalls.isNotEmpty()) {
            return authoritativeBalls
        }

        return context.opponentActor
            .activePokemon
            .mapNotNull { dto ->
                dto?.properties
                    ?.pokeball
                    ?.let(::ballStackFromId)
            }
    }

    private fun opponentName(
        context: StartContext
    ): String {
        val authoritativeName =
            context.descriptor
                ?.opponentName
                ?.takeIf { it.isNotBlank() }

        if (authoritativeName != null) {
            return authoritativeName
        }

        val displayName =
            context.opponentActor
                .displayName
                .string

        return if (
            context.opponentActor.type ==
                ActorType.PLAYER
        ) {
            "Pokémon Trainer $displayName"
        } else {
            displayName
        }
    }

    private fun resolveOpponentActor(
        packet: BattleInitializePacket,
        client: MinecraftClient
    ): BattleInitializePacket.BattleActorDTO? {
        val player =
            client.player
                ?: return null
        val localSide =
            listOf(
                packet.side1,
                packet.side2
            ).firstOrNull { side ->
                side.actors.any { actor ->
                    isLocalPlayerActor(
                        actor,
                        player.uuid,
                        player.name.string
                    )
                }
            } ?: return null
        val opponentSide =
            if (localSide === packet.side1) {
                packet.side2
            } else {
                packet.side1
            }

        return opponentSide.actors
            .firstOrNull()
    }

    private fun isLocalPlayerActor(
        actor: BattleInitializePacket.BattleActorDTO,
        playerUuid: UUID,
        playerName: String
    ): Boolean {
        if (actor.type != ActorType.PLAYER) {
            return false
        }

        return actor.uuid == playerUuid ||
            actor.displayName.string == playerName
    }

    private fun resolveOpponentEntity(
        client: MinecraftClient,
        opponentActor: BattleInitializePacket.BattleActorDTO,
        descriptor: BattleIntroStartPayload?
    ): LivingEntity? {
        val world =
            client.world
                ?: return null

        findPokemonEntity(
            world.entities.filterIsInstance<PokemonEntity>(),
            descriptor?.opponentPokemonUuid
        )?.let { return it }

        findLivingEntity(
            world.entities.filterIsInstance<LivingEntity>(),
            descriptor?.opponentEntityUuid
        )?.let { return it }

        resolvePlayerEntity(
            client,
            opponentActor
        )?.let { return it }

        findActivePokemonEntity(
            world.entities.filterIsInstance<PokemonEntity>(),
            opponentActor
        )?.let { return it }

        return findLivingEntity(
            world.entities.filterIsInstance<LivingEntity>(),
            opponentActor.uuid
        )
    }

    private fun findPokemonEntity(
        entities: List<PokemonEntity>,
        pokemonUuid: UUID?
    ): PokemonEntity? {
        if (pokemonUuid == null) {
            return null
        }

        return entities.firstOrNull {
            it.pokemon.uuid == pokemonUuid
        }
    }

    private fun findLivingEntity(
        entities: List<LivingEntity>,
        entityUuid: UUID?
    ): LivingEntity? {
        if (entityUuid == null) {
            return null
        }

        return entities.firstOrNull {
            it.uuid == entityUuid
        }
    }

    private fun resolvePlayerEntity(
        client: MinecraftClient,
        opponentActor: BattleInitializePacket.BattleActorDTO
    ): LivingEntity? {
        if (opponentActor.type != ActorType.PLAYER) {
            return null
        }

        return client.world
            ?.players
            ?.firstOrNull {
                it.uuid == opponentActor.uuid
            }
    }

    private fun findActivePokemonEntity(
        entities: List<PokemonEntity>,
        opponentActor: BattleInitializePacket.BattleActorDTO
    ): PokemonEntity? {
        val activeUuids =
            opponentActor.activePokemon
                .mapNotNull { it?.uuid }
                .toSet()

        if (activeUuids.isEmpty()) {
            return null
        }

        return entities.firstOrNull {
            it.pokemon.uuid in activeUuids
        }
    }

    private fun ballStackFromId(
        raw: String
    ): ItemStack? {
        val normalized =
            if (':' in raw) {
                raw
            } else {
                "cobblemon:$raw"
            }
        val id =
            Identifier.tryParse(normalized)
                ?: return null

        return ItemStack(
            Registries.ITEM.get(id)
        )
    }

    private fun logStarted(
        context: StartContext,
        presentation: WildPresentation
    ) {
        debugLog(
            "Starting BattleIntroduction intro source={} battle={} opponentType={} kind={} raid={} wildBoss={} specialWild={}",
            context.source,
            context.packet.battleId,
            context.opponentActor.type,
            context.descriptor?.kind ?: "<none>",
            presentation.raid?.stars ?: "<none>",
            presentation.boss?.tierName ?: "<none>",
            presentation.special?.role ?: "<none>"
        )
    }

    private fun cleanup() {
        val now =
            System.currentTimeMillis()

        authoritative.entries.removeIf {
            now - it.value.receivedAtMs >
                DESCRIPTOR_TTL_MS
        }

        started.entries.removeIf {
            now - it.value >
                STARTED_TTL_MS
        }
    }

    private fun debugLog(
        message: String,
        vararg args: Any?
    ) {
        if (BattleIntroductionConfig.debugLogging) {
            logger.info(
                message,
                *args
            )
        }
    }
}
