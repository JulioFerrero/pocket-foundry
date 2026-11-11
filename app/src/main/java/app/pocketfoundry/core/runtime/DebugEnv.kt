package app.pocketfoundry.core.runtime

import android.content.Context
import android.util.Log
import app.pocketfoundry.core.system.Paths
import app.pocketfoundry.core.system.TAG
import app.pocketfoundry.core.system.isDebuggable

/**
 * Debug builds only: `game.KEY=value`, `x11.KEY=value` and `test.program=…` lines from files/debug.env, to try
 * things without a rebuild.
 */
object DebugEnv {
    fun read(context: Context, process: String): Map<String, String> {
        val file = Paths(context).debugEnv
        if (!context.isDebuggable || !file.exists()) return emptyMap()
        return parse(file.readLines(), process).also { if (it.isNotEmpty()) Log.i(TAG, "debug.env for $process: $it") }
    }

    internal fun parse(lines: List<String>, process: String): Map<String, String> {
        val line = Regex("${Regex.escape(process)}\\.([^=]*)=(.*)")
        return lines.mapNotNull { line.matchEntire(it.trim())?.destructured }.associate { (key, value) -> key to value }
    }
}
