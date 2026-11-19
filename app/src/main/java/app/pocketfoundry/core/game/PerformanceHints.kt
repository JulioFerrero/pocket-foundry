package app.pocketfoundry.core.game

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import app.pocketfoundry.core.drivers.Route
import app.pocketfoundry.core.runtime.Runtime
import app.pocketfoundry.core.system.Paths
import java.io.File

/**
 * Android's performance hints (ADPF), how a game asks for the CPU it needs on any phone: our SDL opens a session for
 * the game's threads and reports each frame's work against the frame rate's target at the buffer swap
 * (runtime/sdl/pocketfoundry), through libhybris and libandroid's APerformanceHint. Android 13 and later, where the
 * phone has them. A driver's Options can turn them off with POCKETFOUNDRY_HINTS=.
 */
object PerformanceHints {
    internal const val SWITCH = "POCKETFOUNDRY_HINTS"

    fun environment(context: Context, paths: Paths): Map<String, String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return emptyMap()
        val manager = context.getSystemService(PerformanceHintManager::class.java) ?: return emptyMap()
        if (manager.preferredUpdateRateNanos < 0) return emptyMap()
        return Route.hybris(paths, Runtime.properties["ro.vndk.version"].orEmpty()) +
            (SWITCH to File(paths.hybris, "libhybris-common.so.1").path)
    }
}
