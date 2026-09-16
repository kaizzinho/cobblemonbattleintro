package com.kaizzinho.battleintroduction.client.config

import com.google.gson.GsonBuilder
import com.google.gson.JsonParseException
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale


object BattleIntroductionConfig {

    private val LOGGER =
        LoggerFactory.getLogger("battleintroduction/BattleIntroductionConfig")

    private val GSON = GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create()

    private val configDir: Path =
        FabricLoader.getInstance()
            .configDir
            .resolve("cobblemonbattleintroduction")

    private val configPath: Path =
        configDir.resolve("battleintroduction.json")

    private val previousConfigPath: Path =
        FabricLoader.getInstance()
            .configDir
            .resolve("battleintroduction.json")

    private val legacyConfigPath: Path =
        FabricLoader.getInstance()
            .configDir
            .resolve("battleslider.json")

    enum class AnimationSpeed(
        val configValue: String,
        val durationMultiplier: Double
    ) {
        SLOW("slow", 1.25),
        NORMAL("normal", 1.0),
        FAST("fast", 0.75),
        VERY_FAST("very_fast", 0.50);

        companion object {
            fun from(value: String?): AnimationSpeed =
                entries.firstOrNull {
                    it.configValue == BattleIntroductionConfig.normalizeChoice(value)
                } ?: NORMAL
        }
    }

    enum class FlashIntensity(
        val configValue: String
    ) {
        OFF("off"),
        REDUCED("reduced"),
        NORMAL("normal");

        companion object {
            fun from(value: String?): FlashIntensity =
                entries.firstOrNull {
                    it.configValue == BattleIntroductionConfig.normalizeChoice(value)
                } ?: NORMAL
        }
    }

    enum class ParticleDensity(
        val configValue: String,
        val particlesPerRow: Int
    ) {
        OFF("off", 0),
        LOW("low", 2),
        NORMAL("normal", 5),
        HIGH("high", 8);

        companion object {
            fun from(value: String?): ParticleDensity =
                entries.firstOrNull {
                    it.configValue == BattleIntroductionConfig.normalizeChoice(value)
                } ?: LOW
        }
    }

    enum class TrainerPortraitMode(
        val configValue: String
    ) {
        THREE_D_PREFERRED("3d_preferred"),
        TWO_D_ONLY("2d_only"),
        DISABLED("disabled");

        companion object {
            fun from(value: String?): TrainerPortraitMode =
                entries.firstOrNull {
                    it.configValue == BattleIntroductionConfig.normalizeChoice(value)
                } ?: THREE_D_PREFERRED
        }
    }

    enum class PokemonPortraitMode(
        val configValue: String
    ) {
        AUTOMATIC("automatic"),
        THREE_D_ONLY("3d_only"),
        TWO_D_PREFERRED("2d_preferred"),
        TWO_D_ONLY("2d_only"),
        DISABLED("disabled");

        companion object {
            fun from(value: String?): PokemonPortraitMode =
                entries.firstOrNull {
                    it.configValue == BattleIntroductionConfig.normalizeChoice(value)
                } ?: AUTOMATIC
        }
    }

    data class Values(


        var enableBattleIntros: Boolean = true,
        var allowSkipping: Boolean = true,
        var showSkipPrompt: Boolean = true,
        var showNameBadges: Boolean = true,
        var showPartyBalls: Boolean = true,


        var trainerBattleIntros: Boolean = true,
        var pvpBattleIntros: Boolean = true,
        var wildBossBattleIntros: Boolean = true,
        var raidDenBattleIntros: Boolean = true,
        var legendaryBattleIntros: Boolean = true,
        var mythicalBattleIntros: Boolean = true,
        var alphaBattleIntros: Boolean = true,


        var animationSpeed: String = AnimationSpeed.NORMAL.configValue,
        var flashIntensity: String = FlashIntensity.NORMAL.configValue,
        var holdDurationMs: Int = 1300,
        var particleDensity: String = ParticleDensity.LOW.configValue,
        var trainerPortraitMode: String =
            TrainerPortraitMode.THREE_D_PREFERRED.configValue,
        var pokemonPortraitMode: String =
            PokemonPortraitMode.AUTOMATIC.configValue,


        var uiTransitionSounds: Boolean = true,
        var teamBallLineupSound: Boolean = true,
        var teamBallLineupVolume: Float = 1.25f,


        var debugLogging: Boolean = false,


        var rctTrainerRoleOverrides: MutableMap<String, String>? =
            linkedMapOf()
    )

    @Volatile
    private var values: Values = Values()

    val enableBattleIntros: Boolean
        get() = values.enableBattleIntros

    val allowSkipping: Boolean
        get() = values.allowSkipping

    val showSkipPrompt: Boolean
        get() = values.showSkipPrompt

    val showNameBadges: Boolean
        get() = values.showNameBadges

    val showPartyBalls: Boolean
        get() = values.showPartyBalls

    val trainerBattleIntros: Boolean
        get() = values.trainerBattleIntros

    val pvpBattleIntros: Boolean
        get() = values.pvpBattleIntros

    val wildBossBattleIntros: Boolean
        get() = values.wildBossBattleIntros

    val raidDenBattleIntros: Boolean
        get() = values.raidDenBattleIntros

    val legendaryBattleIntros: Boolean
        get() = values.legendaryBattleIntros

    val mythicalBattleIntros: Boolean
        get() = values.mythicalBattleIntros

    val alphaBattleIntros: Boolean
        get() = values.alphaBattleIntros

    val animationSpeed: AnimationSpeed
        get() = AnimationSpeed.from(values.animationSpeed)

    val animationDurationMultiplier: Double
        get() = animationSpeed.durationMultiplier

    val flashIntensity: FlashIntensity
        get() = FlashIntensity.from(values.flashIntensity)

    val holdDurationMs: Long
        get() = values.holdDurationMs.toLong()

    val particleDensity: ParticleDensity
        get() = ParticleDensity.from(values.particleDensity)

    val trainerPortraitMode: TrainerPortraitMode
        get() = TrainerPortraitMode.from(values.trainerPortraitMode)

    val pokemonPortraitMode: PokemonPortraitMode
        get() = PokemonPortraitMode.from(values.pokemonPortraitMode)

    val uiTransitionSounds: Boolean
        get() = values.uiTransitionSounds

    val teamBallLineupSound: Boolean
        get() = values.teamBallLineupSound

    val teamBallLineupVolume: Float
        get() = values.teamBallLineupVolume

    val debugLogging: Boolean
        get() = values.debugLogging

    val rctTrainerRoleOverrideCount: Int
        get() = values.rctTrainerRoleOverrides.orEmpty().size


    fun snapshot(): Values =
        values.copy(
            rctTrainerRoleOverrides =
                LinkedHashMap(values.rctTrainerRoleOverrides.orEmpty())
        )


    @Synchronized
    fun applyAndSave(updated: Values): Boolean {
        val previous = values
        val normalized = normalizeValues(
            updated.copy(
                rctTrainerRoleOverrides =
                    LinkedHashMap(updated.rctTrainerRoleOverrides.orEmpty())
            )
        )

        values = normalized

        return try {
            writeConfig()
            LOGGER.info("BattleIntroduction client settings saved")
            true
        } catch (e: Exception) {
            values = previous
            LOGGER.error(
                "Could not save BattleIntroduction config to {}",
                configPath.toAbsolutePath(),
                e
            )
            false
        }
    }

    fun defaultValues(): Values = Values()


    fun getRctTrainerRoleOverride(trainerId: String): String? {
        val normalizedId = normalizeToken(trainerId)
        return values.rctTrainerRoleOverrides
            .orEmpty()[normalizedId]
    }


// back up bad configs before resetting
    fun load() {
        try {
            Files.createDirectories(configPath.parent)

            if (!Files.exists(configPath)) {
                val migrationSource =
                    listOf(previousConfigPath, legacyConfigPath)
                        .firstOrNull { Files.exists(it) }

                if (migrationSource != null) {
                    Files.copy(
                        migrationSource,
                        configPath,
                        StandardCopyOption.REPLACE_EXISTING
                    )
                    LOGGER.info(
                        "Migrated Battle Introduction config from {} to {}",
                        migrationSource.toAbsolutePath(),
                        configPath.toAbsolutePath()
                    )
                }
            }

            if (!Files.exists(configPath)) {
                values = Values()
                writeConfig()
                LOGGER.info(
                    "Created default BattleIntroduction config at {}",
                    configPath.toAbsolutePath()
                )
                return
            }

            Files.newBufferedReader(configPath).use { reader ->
                val loaded =
                    GSON.fromJson(reader, Values::class.java)
                        ?: Values()
                values = normalizeValues(loaded)
            }


            writeConfig()

            LOGGER.info(
                "Loaded BattleIntroduction config: intros={}, trainer={}, pvp={}, wildBoss={}, raidDens={}, legendary={}, mythical={}, alpha={}, speed={}, flash={}, particles={}, trainerPortrait={}, pokemonPortrait={}, debugLogging={}, rctTrainerRoleOverrides={}",
                values.enableBattleIntros,
                values.trainerBattleIntros,
                values.pvpBattleIntros,
                values.wildBossBattleIntros,
                values.raidDenBattleIntros,
                values.legendaryBattleIntros,
                values.mythicalBattleIntros,
                values.alphaBattleIntros,
                values.animationSpeed,
                values.flashIntensity,
                values.particleDensity,
                values.trainerPortraitMode,
                values.pokemonPortraitMode,
                values.debugLogging,
                values.rctTrainerRoleOverrides.orEmpty().size
            )
        } catch (e: JsonParseException) {
            recoverFromInvalidConfig(e)
        } catch (e: Exception) {
            LOGGER.error(
                "Failed to load BattleIntroduction config from {}; using defaults",
                configPath.toAbsolutePath(),
                e
            )
            values = Values()
        }
    }

    private fun normalizeValues(loaded: Values): Values {
        loaded.animationSpeed =
            AnimationSpeed.from(loaded.animationSpeed).configValue

        loaded.flashIntensity =
            FlashIntensity.from(loaded.flashIntensity).configValue

        loaded.particleDensity =
            ParticleDensity.from(loaded.particleDensity).configValue

        loaded.trainerPortraitMode =
            TrainerPortraitMode.from(loaded.trainerPortraitMode).configValue

        loaded.pokemonPortraitMode =
            PokemonPortraitMode.from(loaded.pokemonPortraitMode).configValue

        loaded.holdDurationMs =
            loaded.holdDurationMs.coerceIn(250, 2500)

        loaded.teamBallLineupVolume =
            loaded.teamBallLineupVolume.coerceIn(0f, 2f)

        val normalizedOverrides = linkedMapOf<String, String>()

        loaded.rctTrainerRoleOverrides
            .orEmpty()
            .forEach { (rawTrainerId, rawRole) ->
                val trainerId = normalizeToken(rawTrainerId)
                val role = normalizeToken(rawRole)

                if (trainerId.isNotEmpty() && role.isNotEmpty()) {
                    normalizedOverrides[trainerId] = role
                }
            }

        loaded.rctTrainerRoleOverrides = normalizedOverrides
        return loaded
    }

    private fun normalizeToken(value: String): String =
        value.trim()
            .lowercase(Locale.ROOT)
            .substringAfterLast(':')
            .substringAfterLast('/')
            .replace('-', '_')

    private fun writeConfig() {
        Files.newBufferedWriter(configPath).use { writer ->
            GSON.toJson(values, writer)
        }
    }

    private fun recoverFromInvalidConfig(error: Exception) {
        val timestamp = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))

        val backupPath = configPath.resolveSibling(
            "${configPath.fileName}.broken-$timestamp"
        )

        try {
            if (Files.exists(configPath)) {
                Files.move(
                    configPath,
                    backupPath,
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
        } catch (backupError: Exception) {
            LOGGER.error(
                "Could not preserve malformed BattleIntroduction config at {}",
                backupPath.toAbsolutePath(),
                backupError
            )
        }

        values = Values()

        try {
            writeConfig()
        } catch (saveError: Exception) {
            LOGGER.error(
                "Could not write replacement BattleIntroduction config at {}",
                configPath.toAbsolutePath(),
                saveError
            )
        }

        LOGGER.error(
            "Invalid BattleIntroduction config JSON. Defaults were restored and the " +
                "invalid file was moved to {}",
            backupPath.toAbsolutePath(),
            error
        )
    }

    private fun normalizeChoice(value: String?): String =
        value.orEmpty()
            .trim()
            .lowercase(Locale.ROOT)
            .replace('-', '_')
            .replace(' ', '_')
}
