package app.pocketfoundry.core.game

import android.content.Context
import app.pocketfoundry.core.system.Paths
import app.pocketfoundry.core.system.Settings
import app.pocketfoundry.core.drivers.Drivers.Compression

object FactorioConfig {
    /** Without its version header Factorio calls the file invalid and offers a reset. */
    private const val VERSION_HEADER = "; version=13\n"
    /** The settings that halved what the CPU driver draws in a game; all on by default. */
    private val EFFECTS = listOf("show-smoke", "show-clouds", "show-fog", "show-space-dust", "show-decoratives",
                                 "show-particles", "show-item-shadows", "show-inserter-shadows", "additional-terrain-effects",
                                 "high-quality-terrain", "elevated-rail-shadows")

    /** Whether the config has Light's effects off, so turning Light off can put them back once. */
    private const val LIGHT_APPLIED = "lightApplied"
    /**
     * Whether the config has textures uncompressed for the driver, so another driver can put Factorio's default back
     * once. True at first: 0.8.3 to 0.8.5 left them uncompressed without noting it.
     */
    private const val UNCOMPRESSED_APPLIED = "uncompressedApplied"

    /** [noGpuMemory]: the driver hides the GPU's memory from Factorio, which then guesses wrong (update). */
    /** [atlasCache]: fast start, where the player has it on and the driver gives the atlases back right. */
    fun write(context: Context, paths: Paths, keyboard: Boolean, compression: Compression, atlasCache: Boolean,
              noGpuMemory: Boolean = false) {
        val settings = Settings(context)
        val prefs = Settings.prefs(context)
        val light = settings.lightGraphics
        val uncompressed = compression == Compression.NONE
        val old = if (paths.config.exists()) paths.config.readText() else ""
        // Factorio derives its paths from its executable (here the loader), so name them.
        val config = update(old, paths.gameReadData.path, paths.gameData.path, keyboard, settings.spriteQuality,
                            compression, textures(uncompressed, prefs.getBoolean(UNCOMPRESSED_APPLIED, true)),
                            effects(light, prefs.getBoolean(LIGHT_APPLIED, false)), atlasCache, noGpuMemory)
        paths.config.parentFile?.mkdirs()
        paths.config.writeText(config)
        prefs.edit().putBoolean(LIGHT_APPLIED, light).putBoolean(UNCOMPRESSED_APPLIED, uncompressed).apply()
    }

    /**
     * A driver that needs them gets uncompressed textures at every start. The next driver puts Factorio's default back
     * once; after that the game's menu decides (null).
     */
    internal fun textures(uncompressed: Boolean, uncompressedApplied: Boolean): String? = when {
        uncompressed -> "none"
        uncompressedApplied -> "low-quality"
        else -> null
    }

    /**
     * Light forces the effects off at every start. Turning Light off turns them on once; after that the game's menu
     * decides (null).
     */
    internal fun effects(light: Boolean, lightApplied: Boolean): Boolean? = if (light || lightApplied) !light else null

    internal fun update(ini: String, readData: String, writeData: String, keyboard: Boolean, spriteQuality: String,
                        compression: Compression, textures: String?, effects: Boolean?, atlasCache: Boolean,
                        noGpuMemory: Boolean = false): String {
        var config = if (ini.startsWith("; version=")) ini else VERSION_HEADER
        config = setIni(config, "path", "read-data", readData)
        config = setIni(config, "path", "write-data", writeData)
        // Factorio doesn't switch between mouse and gamepad by itself.
        config = setIni(config, "input", "input-method", if (keyboard) "keyboard-and-mouse" else "game-controller")
        // Factorio's automatic UI scale is too small on a phone.
        if (!Regex("(?m)^ui-scale-mode=").containsMatchIn(config)) {
            config = setIni(config, "interface", "ui-scale-mode", "manual-display-points")
            config = setIni(config, "interface", "custom-proportional-ui-scale", "1.25")
        }
        config = setIni(config, "graphics", "graphics-quality", spriteQuality)
        for (key in listOf("gpu-accelerated-compression", "gpu-accelerated-mipmap-compression"))
            config = setIni(config, "graphics", key, (compression == Compression.GPU).toString())
        if (textures != null) config = setIni(config, "graphics", "texture-compression-level", textures)
        if (effects != null) for (key in EFFECTS) config = setIni(config, "graphics", key, effects.toString())
        // Compressed, the cache takes a third of the room and loads as fast. Factorio builds it again when the sprites,
        // their compression or the mods change.
        config = setIni(config, "graphics", "cache-sprite-atlas", atlasCache.toString())
        config = setIni(config, "graphics", "compress-sprite-atlas-cache", "true")
        // Finding no GPU memory, Factorio made its atlases as large as the driver allows (16383 on a Mali-G52) and
        // streamed sprites in while playing, each piece kept as ASTC on a Mali by MobileGlues: 7 FPS on a Redmi 12's
        // main menu. What it picks through Zink on the same phones.
        if (noGpuMemory) {
            config = setIni(config, "graphics", "max-texture-size", "8192")
            config = setIni(config, "graphics", "texture-streaming", "false")
        }
        return config
    }

    internal fun setIni(ini: String, section: String, key: String, value: String): String {
        val line = "$key=$value"
        val existing = Regex("(?m)^${Regex.escape(key)}=.*$")
        val header = Regex("(?m)^\\[${Regex.escape(section)}\\]$")
        val start = header.find(ini) ?: return ini.trimEnd('\n') + "\n\n[$section]\n$line\n"
        val end = Regex("(?m)^\\[").find(ini, start.range.last + 1)?.range?.first ?: ini.length
        val body = ini.substring(start.range.last + 1, end)
        val newBody = if (existing.containsMatchIn(body)) body.replace(existing, Regex.escapeReplacement(line))
                      else "\n$line" + body
        return ini.substring(0, start.range.last + 1) + newBody + ini.substring(end)
    }
}
