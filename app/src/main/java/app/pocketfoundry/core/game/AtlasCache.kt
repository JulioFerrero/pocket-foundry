package app.pocketfoundry.core.game

import android.content.Context
import app.pocketfoundry.core.system.Paths
import app.pocketfoundry.core.system.Settings

/**
 * Fast start's cache holds the atlases as the driver gave them back, and Factorio loads it whatever driver made it:
 * one made by PanVK, whose BC emulation skips the copy, would load on the phone's driver too. So each version of each
 * driver makes its own, and a cache from before 0.8.18, whose maker isn't known, goes.
 */
object AtlasCache {
    private const val MAKER = "atlasCacheMaker"

    /** Before a game on [maker] (DriverCheck.cacheMaker): [kept] says whether it gets fast start at all. */
    fun prepare(context: Context, paths: Paths, maker: String, kept: Boolean) {
        val prefs = Settings.prefs(context)
        if (!kept || prefs.getString(MAKER, null) != maker) delete(paths)
        prefs.edit().putString(MAKER, maker.takeIf { kept }).apply()
    }

    /** With whatever else Factorio named after it (OldApp skips the same). */
    fun delete(paths: Paths) {
        paths.gameData.listFiles { file -> file.name.startsWith(paths.atlasCache.name) }?.forEach { it.delete() }
    }
}
