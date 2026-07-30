package com.kaizzinho.battleslider.client.config

import com.google.gson.GsonBuilder
import com.google.gson.JsonParseException
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Client-side Battleslider configuration.
 *
 * Generated at:
 *   config/battleslider.json
 *
 * Changes are loaded when the Minecraft client starts. A restart is therefore
 * required after editing the JSON file.
 */
object BattleSliderConfig {

    private val LOGGER = LoggerFactory.getLogger("battleslider/BattleSliderConfig")
    private val GSON = GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create()

    private val configPath: Path =
        FabricLoader.getInstance().configDir.resolve("battleslider.json")

    data class Values(
        /**
         * Enables detailed packet queue, replay, skip and timing diagnostics.
         *
         * Keep false for normal gameplay and public releases. Set true only
         * while diagnosing an intro or packet-order problem.
         */
        var debugLogging: Boolean = false
    )

    @Volatile
    private var values: Values = Values()

    val debugLogging: Boolean
        get() = values.debugLogging

    /**
     * Loads the config from disk. If the file does not exist, a default one is
     * created with debugLogging=false.
     *
     * If the JSON is malformed, the invalid file is preserved as a timestamped
     * .broken backup and a clean default config is written.
     */
    fun load() {
        try {
            Files.createDirectories(configPath.parent)

            if (!Files.exists(configPath)) {
                values = Values()
                save()
                LOGGER.info(
                    "Created default Battleslider config at {}",
                    configPath.toAbsolutePath()
                )
                return
            }

            Files.newBufferedReader(configPath).use { reader ->
                values = GSON.fromJson(reader, Values::class.java) ?: Values()
            }

            // Rewrite after loading so newly introduced fields are added with
            // defaults while preserving all currently supported values.
            save()

            LOGGER.info(
                "Loaded Battleslider config: debugLogging={}",
                values.debugLogging
            )
        } catch (e: JsonParseException) {
            recoverFromInvalidConfig(e)
        } catch (e: Exception) {
            LOGGER.error(
                "Failed to load Battleslider config from {}; using defaults",
                configPath.toAbsolutePath(),
                e
            )
            values = Values()
        }
    }

    private fun save() {
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
                "Could not preserve malformed Battleslider config at {}",
                backupPath.toAbsolutePath(),
                backupError
            )
        }

        values = Values()

        try {
            save()
        } catch (saveError: Exception) {
            LOGGER.error(
                "Could not write replacement Battleslider config at {}",
                configPath.toAbsolutePath(),
                saveError
            )
        }

        LOGGER.error(
            "Invalid Battleslider config JSON. Defaults were restored and the " +
                "invalid file was moved to {}",
            backupPath.toAbsolutePath(),
            error
        )
    }
}
