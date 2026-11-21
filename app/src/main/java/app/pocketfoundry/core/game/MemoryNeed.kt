package app.pocketfoundry.core.game

import android.content.Context
import app.pocketfoundry.core.drivers.Drivers
import app.pocketfoundry.core.logs.MemoryWatch
import app.pocketfoundry.core.system.Paths
import app.pocketfoundry.core.system.Settings
import org.json.JSONObject
import java.io.File

/**
 * Whether the phone has the memory a game is about to take. Short of it, Android closes the app without a word, most
 * often as Factorio makes its atlases, halfway through loading: so the launcher says so before starting.
 *
 * Counted as the phone counts it: the memory Android calls available plus the free swap. What loads took, measured
 * with low VRAM on (2026-11-21): the base game started on a 3 GB Redmi 10C with 1.16 GB of that, at Medium and at
 * High; Space Age at Medium didn't with 0.94 GB (closed as its atlases began); Space Age at High took 4.6 GB of a
 * Galaxy Tab S8's 6.1 GB (atlases 2.4 GB on the GPU, which is the same RAM, and up to 1.3 GB the game's own).
 * Fast start's cache skips the decoding: a quarter less.
 */
object MemoryNeed {
    /** MB: base game at Medium and High, Space Age at Medium and High, while Fast start's cache is made. */
    private const val BASE_MEDIUM = 900L
    private const val BASE_HIGH = 1100L
    private const val SPACE_AGE_MEDIUM = 2200L
    private const val SPACE_AGE_HIGH = 4500L
    private const val CACHED = 0.75

    /** [freeMb] is null where /proc/meminfo can't be read: then there's nothing to say. */
    class Check(val freeMb: Long?, val neededMb: Long) {
        val short get() = freeMb != null && freeMb < neededMb
    }

    fun check(context: Context): Check {
        val paths = Paths(context)
        val cached = Settings(context).fastStart && paths.atlasCache.exists()
        return Check(freeMb(MemoryWatch.read(File("/proc/meminfo"))),
                     neededMb(spaceAge(paths), Drivers.nextSprites(context) == Settings.HIGH, cached))
    }

    internal fun neededMb(spaceAge: Boolean, high: Boolean, cached: Boolean): Long {
        val building = when {
            spaceAge && high -> SPACE_AGE_HIGH
            spaceAge -> SPACE_AGE_MEDIUM
            high -> BASE_HIGH
            else -> BASE_MEDIUM
        }
        return if (cached) (building * CACHED).toLong() else building
    }

    /** From /proc/meminfo's kB: available memory and free swap, in MB. */
    internal fun freeMb(meminfo: Map<String, Long>): Long? =
        meminfo["MemAvailable"]?.let { (it + (meminfo["SwapFree"] ?: 0L)) / 1024 }

    /** Space Age is there and Factorio's mod list doesn't turn it off. */
    internal fun spaceAge(paths: Paths): Boolean {
        if (!File(paths.gameReadData, "space-age").isDirectory) return false
        val list = File(paths.gameData, "mods/mod-list.json")
        val mods = runCatching { JSONObject(list.readText()).getJSONArray("mods") }.getOrNull() ?: return true
        for (i in 0 until mods.length()) {
            val mod = mods.optJSONObject(i) ?: continue
            if (mod.optString("name") == "space-age") return mod.optBoolean("enabled", true)
        }
        return true
    }
}
