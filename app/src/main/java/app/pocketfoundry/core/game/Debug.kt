package app.pocketfoundry.core.game

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import app.pocketfoundry.core.controller.InputBridge
import app.pocketfoundry.core.controller.PadAxis
import app.pocketfoundry.core.controller.PadButton
import app.pocketfoundry.core.system.mainHandler
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

/**
 * Drives the virtual gamepad from adb, in debug builds:
 *   adb shell am broadcast -a app.pocketfoundry.debug.PAD --es tap south
 *   adb shell am broadcast -a app.pocketfoundry.debug.PAD --es press dpad_down   (then --es release …)
 *   adb shell am broadcast -a app.pocketfoundry.debug.PAD --es axis left_x --ei value 20000
 *   adb shell am broadcast -a app.pocketfoundry.debug.PAD --es run pocketfoundry-padtest   (a files/usr/bin tool)
 */
object DebugPad : BroadcastReceiver() {
    private const val ACTION = "app.pocketfoundry.debug.PAD"
    private const val TAP_MS = 120L

    fun registerIfDebuggable(context: Context) {
        if (!context.isDebuggable) return
        ContextCompat.registerReceiver(context, this, IntentFilter(ACTION), ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra("run")?.let { name ->
            require(name.matches(Session.TOOL_NAME)) { "Tool names are plain words" }
            Session.runTool(context, name)
            resultData = "started $name"
            return
        }
        intent.getStringExtra("tap")?.let { name ->
            val button = button(name) ?: return unknown(name)
            InputBridge.button(button, true)
            mainHandler.postDelayed({ InputBridge.button(button, false) }, TAP_MS)
        }
        intent.getStringExtra("press")?.let { InputBridge.button(button(it) ?: return unknown(it), true) }
        intent.getStringExtra("release")?.let { InputBridge.button(button(it) ?: return unknown(it), false) }
        intent.getStringExtra("axis")?.let { name ->
            val axis = PadAxis.entries.find { it.name == name.uppercase() } ?: return unknown(name)
            InputBridge.axis(axis, intent.getIntExtra("value", 0))
        }
        resultData = if (InputBridge.connected) "sent" else "gamepad not connected"
    }

    private fun button(name: String) = PadButton.entries.find { it.name == name.uppercase() }

    private fun unknown(name: String) { resultData = "unknown button or axis: $name" }
}
