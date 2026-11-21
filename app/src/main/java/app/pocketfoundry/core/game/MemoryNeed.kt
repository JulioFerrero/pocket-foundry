package app.pocketfoundry.core.game

import android.content.Context
import app.pocketfoundry.core.drivers.Drivers
import app.pocketfoundry.core.logs.MemoryWatch
import app.pocketfoundry.core.system.Paths
import app.pocketfoundry.core.system.Settings
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/**
 * Whether the phone has the memory a game is about to take. Short of it, Android closes the app without a word, most
 * often as Factorio makes its atlases, halfway through loading: so the launcher says so before starting.
 *
 * Counted as the phone counts it: the memory Android calls available plus the free swap. What loads took, measured
 * with low VRAM on (2026-11-21): the base game started on a 3 GB Redmi 10C with 1.16 GB of that, at Medium and at
 * High; Space Age at Medium didn't with 0.94 GB (closed as its atlases began); Space Age at High took 4.6 GB of a
 * Galaxy Tab S8's 6.1 GB (atlases 2.4 GB on the GPU, which is the same RAM, and up to 1.3 GB the game's own).
 * Fast start's cache skips the decoding: a quarter less.
 *
 * A save's map comes on top, about 15 MB a MiB of its level.dat as Factorio unpacks it (2026-11-21): flame_Sla's 86 MiB
 * took 1.26 GB more than a small save on a Xiaomi 12T; gridlocked's 501 MiB held 7.3 GB on a vivo X200 Ultra, and
 * Android closed it on the 12T with 3.2 GB in memory and 4.9 GB in swap.
 */
object MemoryNeed {
    /** MB: base game at Medium and High, Space Age at Medium and High, while Fast start's cache is made. */
    private const val BASE_MEDIUM = 900L
    private const val BASE_HIGH = 1100L
    private const val SPACE_AGE_MEDIUM = 2200L
    private const val SPACE_AGE_HIGH = 4500L
    private const val CACHED = 0.75
    private const val MAP_PER_MIB = 15L
    private const val MIB = 1L shl 20

    /** [freeMb] is null where /proc/meminfo can't be read: then there's nothing to say. [mapMb]: the save's share. */
    class Check(val freeMb: Long?, val neededMb: Long, val mapMb: Long = 0) {
        val short get() = freeMb != null && freeMb < neededMb
        /** Most of it is the map, which lower sprites wouldn't make up for. */
        val mapHeavy get() = mapMb * 2 > neededMb
    }

    /** Before starting [save], or the main menu when it's null. */
    fun check(context: Context, save: File?): Check {
        val paths = Paths(context)
        val cached = Settings(context).fastStart && paths.atlasCache.exists()
        val map = mapMb(save)
        return Check(freeMb(MemoryWatch.read(File("/proc/meminfo"))),
                     neededMb(spaceAge(paths), Drivers.nextSprites(context) == Settings.HIGH, cached) + map, map)
    }

    /**
     * What [save]'s map takes once loaded. Factorio 2.0 stores level.dat as level.dat0, level.dat1, ... of a MiB each
     * unpacked; older saves as one level.dat, whose unpacked size the zip records. 0 for no save or an unreadable one.
     */
    internal fun mapMb(save: File?): Long {
        if (save == null) return 0
        val levelBytes = runCatching {
            ZipFile(save).use { zip ->
                var chunks = 0L
                var single = 0L
                for (entry in zip.entries()) {
                    val name = entry.name.substringAfterLast('/')
                    if (CHUNK.matches(name)) chunks++ else if (name == "level.dat") single = maxOf(0L, entry.size)
                }
                if (chunks > 0) chunks * MIB else single
            }
        }.getOrDefault(0L)
        return levelBytes / MIB * MAP_PER_MIB
    }

    private val CHUNK = Regex("level\\.dat\\d+")

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
