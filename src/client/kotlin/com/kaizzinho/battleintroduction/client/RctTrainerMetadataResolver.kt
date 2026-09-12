package com.kaizzinho.battleintroduction.client

import com.cobblemon.mod.common.api.battles.model.actor.BattleActor
import com.cobblemon.mod.common.api.battles.model.actor.EntityBackedBattleActor
import com.kaizzinho.battleintroduction.client.config.BattleIntroductionConfig
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.entity.LivingEntity
import org.slf4j.LoggerFactory
import java.lang.reflect.Modifier
import java.util.Locale


@Environment(EnvType.CLIENT)
// reflection keeps rct optional
object RctTrainerMetadataResolver {

    private val LOGGER =
        LoggerFactory.getLogger(
            "battleintroduction/RctTrainerMetadataResolver"
        )

    enum class TrainerRole {
        NORMAL,
        GYM_LEADER,
        ELITE_FOUR,
        CHAMPION,
        RIVAL
    }

    enum class DetectionSource {
        EXACT_OVERRIDE,
        RCT_STANDARD_TYPE,
        RCT_CUSTOM_TYPE,
        TRAINER_ID_PATTERN,
        COBBLEVERSE_PROGRESSION_RULE,
        UNKNOWN
    }

    data class TrainerClassification(
        val role: TrainerRole,
        val region: String?,
        val trainerId: String,
        val rawType: String,
        val optional: Boolean,
        val source: DetectionSource,
        val rawTypeColorRgb: Int?
    )

    private data class RawRctTrainer(
        val trainerId: String,
        val typeId: String,
        val optional: Boolean,
        val typeColorRgb: Int?
    )

    private const val RCT_MOD_ID = "rctmod"

    private const val RCT_MOD_CLASS =
        "com.gitlab.srcmc.rctmod.api.RCTMod"

    private const val TRAINER_TYPE_CLASS =
        "com.gitlab.srcmc.rctmod.api.data.pack.TrainerType"

    private val TRAINER_MOB_CLASS_NAMES = listOf(
        "com.gitlab.srcmc.rctmod.world.entities.TrainerMob",
        "com.gitlab.srcmc.rctmod.api.entity.TrainerMob"
    )

    private val REGIONS = linkedSetOf(
        "kanto",
        "johto",
        "hoenn",
        "sinnoh",
        "unova",
        "kalos",
        "alola",
        "galar",
        "hisui",
        "paldea"
    )

    private val CANONICAL_TYPE_BY_ROLE = mapOf(
        TrainerRole.GYM_LEADER to "leader",
        TrainerRole.ELITE_FOUR to "e4",
        TrainerRole.CHAMPION to "champ",
        TrainerRole.RIVAL to "rival"
    )


    private val FALLBACK_COLOR_BY_ROLE = mapOf(
        TrainerRole.GYM_LEADER to 0x55FF55,
        TrainerRole.ELITE_FOUR to 0x00AAAA,
        TrainerRole.CHAMPION to 0xAA00AA,
        TrainerRole.RIVAL to 0xFFAA00
    )


    fun resolve(
        actor: BattleActor,
        fallbackEntity: LivingEntity? = null
    ): TrainerClassification? {
        if (!FabricLoader.getInstance().isModLoaded(RCT_MOD_ID)) {
            return null
        }

        val entity = resolveActorEntity(actor) ?: fallbackEntity
            ?: return null

        if (!isTrainerEntity(entity)) {
            return null
        }

        return try {
            val raw = readRawTrainer(entity) ?: return null
            val classification = classify(raw)

            debugLog(
                "RCT classification: actor={}, entity={}, trainerId='{}', " +
                    "rawType='{}', optional={}, role={}, region={}, source={}, " +
                    "rawColor={}",
                actor.javaClass.name,
                entity.javaClass.name,
                classification.trainerId,
                classification.rawType,
                classification.optional,
                classification.role,
                classification.region ?: "<none>",
                classification.source,
                classification.rawTypeColorRgb
                    ?.let {
                        "0x%06X".format(
                            Locale.ROOT,
                            it and 0xFFFFFF
                        )
                    }
                    ?: "<none>"
            )

            classification
        } catch (e: ReflectiveOperationException) {
            debugLog(
                "RCT reflection failed for actor={} entity={}: {}",
                actor.javaClass.name,
                entity.javaClass.name,
                e.message ?: e.javaClass.simpleName
            )
            null
        } catch (e: LinkageError) {
            debugLog(
                "RCT linkage failed for actor={} entity={}: {}",
                actor.javaClass.name,
                entity.javaClass.name,
                e.message ?: e.javaClass.simpleName
            )
            null
        } catch (e: RuntimeException) {


            debugLog(
                "RCT metadata failed for actor={} entity={}: {}",
                actor.javaClass.name,
                entity.javaClass.name,
                e.message ?: e.javaClass.simpleName
            )
            null
        }
    }


    fun resolveEntity(
        entity: LivingEntity?
    ): TrainerClassification? {
        if (
            entity == null ||
            !FabricLoader.getInstance()
                .isModLoaded(RCT_MOD_ID) ||
            !isTrainerEntity(entity)
        ) {
            return null
        }

        return try {
            val raw =
                readRawTrainer(entity)
                    ?: return null
            val classification =
                classify(raw)

            debugLog(
                "RCT client entity classification: entity={}, trainerId='{}', rawType='{}', optional={}, role={}, region={}, source={}",
                entity.javaClass.name,
                classification.trainerId,
                classification.rawType,
                classification.optional,
                classification.role,
                classification.region
                    ?: "<none>",
                classification.source
            )

            classification
        } catch (e: ReflectiveOperationException) {
            debugLog(
                "RCT client entity reflection failed for entity={}: {}",
                entity.javaClass.name,
                e.message
                    ?: e.javaClass.simpleName
            )
            null
        } catch (e: LinkageError) {
            debugLog(
                "RCT client entity linkage failed for entity={}: {}",
                entity.javaClass.name,
                e.message
                    ?: e.javaClass.simpleName
            )
            null
        } catch (e: RuntimeException) {
            debugLog(
                "RCT client entity metadata failed for entity={}: {}",
                entity.javaClass.name,
                e.message
                    ?: e.javaClass.simpleName
            )
            null
        }
    }

    fun resolvePartySize(
        entity: LivingEntity?
    ): Int {
        if (
            entity == null ||
            !FabricLoader.getInstance()
                .isModLoaded(RCT_MOD_ID) ||
            !isTrainerEntity(entity)
        ) {
            return 0
        }

        return runCatching {
            val trainerId =
                normalizeId(
                    invokeNoArg(entity, "getTrainerId")
                        ?.toString()
                ) ?: return@runCatching 0
            val data =
                resolveTrainerData(
                    entity,
                    trainerId
                ) ?: return@runCatching 0
            val trainerTeam =
                invokeNoArg(
                    data,
                    "getTrainerTeam"
                ) ?: data
            val team =
                invokeNoArg(
                    trainerTeam,
                    "getTeam"
                )
            val size =
                collectionSize(team)
                    .coerceIn(0, 6)

            debugLog(
                "[RCT-PARTY] client definition trainerId='{}' dataClass={} teamClass={} partySize={}",
                trainerId,
                data.javaClass.name,
                trainerTeam.javaClass.name,
                size
            )

            size
        }.getOrElse {
            debugLog(
                "[RCT-PARTY] client party-size lookup failed entity={}: {}",
                entity.javaClass.name,
                it.message ?: it.javaClass.simpleName
            )
            0
        }
    }

    fun resolveSliderColor(
        classification: TrainerClassification
    ): Int? {
        if (classification.role == TrainerRole.NORMAL) {
            return classification.rawTypeColorRgb
        }

        val canonicalTypeId =
            CANONICAL_TYPE_BY_ROLE[classification.role]

        if (canonicalTypeId != null) {
            resolveRegisteredTypeColor(canonicalTypeId)
                ?.let { return it }
        }


        if (
            classification.source ==
                DetectionSource.RCT_STANDARD_TYPE &&
            classification.rawTypeColorRgb != null
        ) {
            return classification.rawTypeColorRgb
        }

        return FALLBACK_COLOR_BY_ROLE[classification.role]
    }

    fun isTrainerEntity(entity: LivingEntity?): Boolean {
        if (
            entity == null ||
            !FabricLoader.getInstance().isModLoaded(RCT_MOD_ID)
        ) {
            return false
        }

        val classLoader = entity.javaClass.classLoader

        return TRAINER_MOB_CLASS_NAMES
            .asSequence()
            .mapNotNull {
                loadClassOrNull(it, classLoader)
            }
            .any { it.isInstance(entity) }
    }

    private fun resolveActorEntity(
        actor: BattleActor
    ): LivingEntity? {
        val backedActor =
            actor as? EntityBackedBattleActor<*>
                ?: return null

        return backedActor.entity as? LivingEntity
    }

    private fun readRawTrainer(
        entity: LivingEntity
    ): RawRctTrainer? {
        val trainerId = normalizeId(
            invokeNoArg(entity, "getTrainerId")
                ?.toString()
        ) ?: return null

        val data = resolveTrainerData(entity, trainerId)
            ?: return null

        val typeObject = invokeNoArg(data, "getType")

        val typeId = normalizeId(
            when (typeObject) {
                is String ->
                    typeObject

                null ->
                    null

                else ->
                    (
                        invokeNoArg(typeObject, "id")
                            ?: invokeNoArg(typeObject, "getId")
                    )?.toString()
            }
        ).orEmpty()

        val typeColor = typeObject
            ?.let { invokeNoArg(it, "color") }
            .asIntOrNull()

        val optional =
            invokeNoArg(data, "isOptional") as? Boolean
                ?: false

        return RawRctTrainer(
            trainerId = trainerId,
            typeId = typeId,
            optional = optional,
            typeColorRgb = typeColor
        )
    }

    private fun resolveTrainerData(
        entity: LivingEntity,
        trainerId: String
    ): Any? {


        invokeNoArg(entity, "getData")
            ?.let { return it }

        val classLoader = entity.javaClass.classLoader
        val rctModClass = loadClassOrNull(
            RCT_MOD_CLASS,
            classLoader
        ) ?: return null

        val getInstance = rctModClass.methods.firstOrNull {
            it.name == "getInstance" &&
                it.parameterCount == 0 &&
                Modifier.isStatic(it.modifiers)
        } ?: return null

        val rct = getInstance.invoke(null) ?: return null
        val manager =
            invokeNoArg(rct, "getTrainerManager")
                ?: return null


        return invokeOneArg(
            manager,
            "getData",
            trainerId
        ) ?: invokeOneArg(
            manager,
            "getData",
            entity
        )
    }

    private fun classify(
        raw: RawRctTrainer
    ): TrainerClassification {
        val trainerId = normalizeId(raw.trainerId).orEmpty()
        val typeId = normalizeId(raw.typeId).orEmpty()

        val typeRegion = regionFrom(typeId)
        val idRegion = regionFrom(trainerId)
        val region = typeRegion ?: idRegion

        val configuredOverride =
            BattleIntroductionConfig
                .getRctTrainerRoleOverride(trainerId)

        val overrideRole = parseConfiguredRole(
            configuredOverride
        )

        if (
            configuredOverride != null &&
            overrideRole == null
        ) {
            debugLog(
                "Ignoring invalid RCT role override: trainerId='{}', value='{}'",
                trainerId,
                configuredOverride
            )
        }

        val roleAndSource = when {
            overrideRole != null ->
                overrideRole to
                    DetectionSource.EXACT_OVERRIDE

            typeId in GYM_TYPE_IDS ->
                TrainerRole.GYM_LEADER to
                    DetectionSource.RCT_STANDARD_TYPE

            typeId in ELITE_FOUR_TYPE_IDS ->
                TrainerRole.ELITE_FOUR to
                    DetectionSource.RCT_STANDARD_TYPE

            typeId in CHAMPION_TYPE_IDS ->
                TrainerRole.CHAMPION to
                    DetectionSource.RCT_STANDARD_TYPE

            typeId in RIVAL_TYPE_IDS ->
                TrainerRole.RIVAL to
                    DetectionSource.RCT_STANDARD_TYPE

            typeRegion != null &&
                typeId == "${typeRegion}_league" ->
                TrainerRole.ELITE_FOUR to
                    DetectionSource.RCT_CUSTOM_TYPE

            typeRegion != null &&
                typeId == "${typeRegion}_champion" ->
                TrainerRole.CHAMPION to
                    DetectionSource.RCT_CUSTOM_TYPE

            typeRegion != null &&
                typeId == "${typeRegion}_rival" ->
                TrainerRole.RIVAL to
                    DetectionSource.RCT_CUSTOM_TYPE

            isGymLeaderId(trainerId, region) ->
                TrainerRole.GYM_LEADER to
                    DetectionSource.TRAINER_ID_PATTERN

            isEliteFourId(trainerId, region) ->
                TrainerRole.ELITE_FOUR to
                    DetectionSource.TRAINER_ID_PATTERN

            isChampionId(trainerId, region) ->
                TrainerRole.CHAMPION to
                    DetectionSource.TRAINER_ID_PATTERN

            isRivalId(trainerId, region) ->
                TrainerRole.RIVAL to
                    DetectionSource.TRAINER_ID_PATTERN

            typeRegion != null &&
                typeId == typeRegion &&
                trainerId.startsWith("${typeRegion}_") &&
                !raw.optional &&
                !isReservedProgressionId(
                    trainerId,
                    typeRegion
                ) ->
                TrainerRole.GYM_LEADER to
                    DetectionSource.COBBLEVERSE_PROGRESSION_RULE

            else ->
                TrainerRole.NORMAL to
                    DetectionSource.UNKNOWN
        }

        return TrainerClassification(
            role = roleAndSource.first,
            region = region,
            trainerId = trainerId,
            rawType = typeId,
            optional = raw.optional,
            source = roleAndSource.second,
            rawTypeColorRgb = raw.typeColorRgb
        )
    }

    private fun isGymLeaderId(
        trainerId: String,
        region: String?
    ): Boolean {
        if (
            trainerId.startsWith("leader_") ||
            trainerId.startsWith("gym_leader_") ||
            trainerId.startsWith("gymleader_")
        ) {
            return true
        }

        return region != null &&
            (
                trainerId.startsWith("${region}_leader_") ||
                    trainerId.startsWith(
                        "${region}_gym_leader_"
                    )
                )
    }

    private fun isEliteFourId(
        trainerId: String,
        region: String?
    ): Boolean {
        if (
            trainerId.startsWith("elite_four_") ||
            trainerId.startsWith("elite4_") ||
            trainerId.startsWith("elite_4_") ||
            trainerId.startsWith("e4_")
        ) {
            return true
        }

        return region != null &&
            trainerId.startsWith("${region}_league_")
    }

    private fun isChampionId(
        trainerId: String,
        region: String?
    ): Boolean {
        if (
            trainerId.startsWith("champion_") ||
            trainerId.startsWith("champ_")
        ) {
            return true
        }

        return region != null &&
            trainerId.startsWith("${region}_champion_")
    }

    private fun isRivalId(
        trainerId: String,
        region: String?
    ): Boolean {
        if (trainerId.startsWith("rival_")) {
            return true
        }

        return region != null &&
            trainerId.startsWith("${region}_rival_")
    }

    private fun isReservedProgressionId(
        trainerId: String,
        region: String
    ): Boolean {
        val reservedPrefixes = listOf(
            "${region}_league",
            "${region}_champion",
            "${region}_rival",
            "${region}_leader",
            "${region}_gym_leader"
        )

        return reservedPrefixes.any { prefix ->
            trainerId == prefix ||
                trainerId.startsWith("${prefix}_")
        }
    }

    private fun regionFrom(value: String): String? =
        REGIONS.firstOrNull { region ->
            value == region ||
                value.startsWith("${region}_")
        }

    private fun parseConfiguredRole(
        value: String?
    ): TrainerRole? {
        return when (normalizeId(value)) {
            "normal", "trainer" ->
                TrainerRole.NORMAL

            "leader", "gym_leader", "gymleader" ->
                TrainerRole.GYM_LEADER

            "e4", "elite_four", "elitefour", "elite_4" ->
                TrainerRole.ELITE_FOUR

            "champ", "champion" ->
                TrainerRole.CHAMPION

            "rival" ->
                TrainerRole.RIVAL

            else ->
                null
        }
    }

    private fun resolveRegisteredTypeColor(
        typeId: String
    ): Int? {
        return try {
            val trainerTypeClass = loadClassOrNull(
                TRAINER_TYPE_CLASS,
                javaClass.classLoader
            ) ?: return null

            val trainerType = trainerTypeClass
                .getMethod(
                    "valueOf",
                    String::class.java
                )
                .invoke(null, typeId)
                ?: return null

            invokeNoArg(
                trainerType,
                "color"
            ).asIntOrNull()
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: LinkageError) {
            null
        }
    }

    private fun invokeNoArg(
        target: Any,
        methodName: String
    ): Any? {
        val method = target.javaClass.methods
            .firstOrNull {
                it.name == methodName &&
                    it.parameterCount == 0
            }
            ?: return null

        return method.invoke(target)
    }

    private fun invokeOneArg(
        target: Any,
        methodName: String,
        argument: Any
    ): Any? {
        val method = target.javaClass.methods
            .firstOrNull {
                it.name == methodName &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0]
                        .isAssignableFrom(
                            argument.javaClass
                        )
            }
            ?: return null

        return method.invoke(target, argument)
    }

    private fun loadClassOrNull(
        name: String,
        classLoader: ClassLoader
    ): Class<*>? {
        return try {
            Class.forName(
                name,
                false,
                classLoader
            )
        } catch (_: ClassNotFoundException) {
            null
        } catch (_: LinkageError) {
            null
        }
    }

    private fun collectionSize(
        value: Any?
    ): Int =
        when (value) {
            null -> 0
            is Collection<*> -> value.size
            is Map<*, *> -> value.size
            is Iterable<*> -> value.count()
            else ->
                if (value.javaClass.isArray) {
                    java.lang.reflect.Array.getLength(value)
                } else {
                    0
                }
        }

    private fun normalizeId(
        value: String?
    ): String? {
        return value
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.substringAfterLast(':')
            ?.substringAfterLast('/')
            ?.replace('-', '_')
            ?.takeIf { it.isNotBlank() }
    }

    private fun Any?.asIntOrNull(): Int? {
        return when (this) {
            is Int ->
                this

            is Number ->
                this.toInt()

            else ->
                null
        }
    }

    private fun debugLog(
        message: String,
        vararg args: Any?
    ) {
        if (BattleIntroductionConfig.debugLogging) {
            LOGGER.info(message, *args)
        }
    }

    private val GYM_TYPE_IDS = setOf(
        "leader",
        "gym_leader",
        "gymleader"
    )

    private val ELITE_FOUR_TYPE_IDS = setOf(
        "e4",
        "elite_four",
        "elitefour",
        "elite_4"
    )

    private val CHAMPION_TYPE_IDS = setOf(
        "champ",
        "champion"
    )

    private val RIVAL_TYPE_IDS = setOf(
        "rival"
    )
}
