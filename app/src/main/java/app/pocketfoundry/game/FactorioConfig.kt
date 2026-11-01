package app.pocketfoundry.game

import android.content.Context
import app.pocketfoundry.Paths
import app.pocketfoundry.Settings
import java.io.File

/** Writes our settings into config.ini; the player's other settings stay. */
object FactorioConfig {
    /** Without its version header Factorio calls the file invalid and offers a reset. */
    private const val VERSION_HEADER = "; version=13\n"
    /** The settings that halved what the CPU driver draws in a game; all on by default. */
    private val EFFECTS = listOf("show-smoke", "show-clouds", "show-fog", "show-space-dust", "show-decoratives",
                                 "show-particles", "show-item-shadows", "show-inserter-shadows", "additional-terrain-effects",
                                 "high-quality-terrain", "elevated-rail-shadows")

    fun write(context: Context, paths: Paths, keyboard: Boolean, panvk: Boolean) {
        val settings = Settings(context)
        val light = settings.lightGraphics
        val old = if (paths.config.exists()) paths.config.readText() else ""
        // Factorio derives its paths from its executable (here the loader), so name them.
        val config = update(old, File(paths.game, "data").path, paths.gameData.path, keyboard,
                            settings.spriteQuality, panvk, effects(light, settings.lightApplied))
        paths.config.parentFile?.mkdirs()
        paths.config.writeText(config)
        settings.lightApplied = light
    }

    /**
     * Light forces the effects off at every start. Turning Light off turns them on once; after that the game's menu
     * decides (null).
     */
    internal fun effects(light: Boolean, lightApplied: Boolean): Boolean? = if (light || lightApplied) !light else null

    internal fun update(ini: String, readData: String, writeData: String, keyboard: Boolean, spriteQuality: String,
                        panvk: Boolean, effects: Boolean?): String {
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
        // PanVK's BC emulation skips the copies Factorio's GPU texture compressor makes: its self-test fails there,
        // and a G610 faulted right after. The CPU compresses instead.
        for (key in listOf("gpu-accelerated-compression", "gpu-accelerated-mipmap-compression"))
            config = setIni(config, "graphics", key, (!panvk).toString())
        if (effects != null) for (key in EFFECTS) config = setIni(config, "graphics", key, effects.toString())
        return config
    }

    /** Sets [key] in [section], adding either where missing. */
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
