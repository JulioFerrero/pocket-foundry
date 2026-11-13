package app.pocketfoundry.core.game

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import app.pocketfoundry.core.controller.InputBridge
import app.pocketfoundry.core.controller.VirtualPad
import app.pocketfoundry.core.controller.VirtualPad.Source.DEBUG
import app.pocketfoundry.core.controller.PadAxis
import app.pocketfoundry.core.controller.PadButton
import app.pocketfoundry.core.system.isDebuggable
import app.pocketfoundry.core.system.mainHandler

/**
 * Drives the virtual gamepad from adb, in debug builds:
 *   adb shell am broadcast -a app.pocketfoundry.debug.PAD --es tap south
 *   adb shell am broadcast -a app.pocketfoundry.debug.PAD --es press dpad_down   (then --es release …)
 *   adb shell am broadcast -a app.pocketfoundry.debug.PAD --es axis left_x --ei value 20000
 *   adb shell am broadcast -a app.pocketfoundry.debug.PAD --es run pocketfoundry-padtest   (a files/usr/bin tool)
 */
object DebugPad : BroadcastReceiver() {
    private const val ACTION = "dev.pocketfoundry.debug.PAD"
    private const val TAP_MS = 120L

    fun registerIfDebuggable(context: Context) {
        if (!context.isDebuggable) return
        ContextCompat.registerReceiver(context, this, IntentFilter(ACTION), ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra("run")?.let { name ->
            Session.runTool(context, name)
            resultData = "started $name"
            return
        }
        intent.getStringExtra("tap")?.let { name ->
            val button = button(name) ?: return unknown(name)
            VirtualPad.button(DEBUG, button, true)
            mainHandler.postDelayed({ VirtualPad.button(DEBUG, button, false) }, TAP_MS)
        }
        intent.getStringExtra("press")?.let { VirtualPad.button(DEBUG, button(it) ?: return unknown(it), true) }
        intent.getStringExtra("release")?.let { VirtualPad.button(DEBUG, button(it) ?: return unknown(it), false) }
        intent.getStringExtra("axis")?.let { name ->
            val axis = PadAxis.entries.find { it.name == name.uppercase() } ?: return unknown(name)
            VirtualPad.axis(DEBUG, axis, intent.getIntExtra("value", 0))
        }
        resultData = if (InputBridge.connected) "sent" else "gamepad not connected"
    }

    private fun button(name: String) = PadButton.entries.find { it.name == name.uppercase() }

    private fun unknown(name: String) { resultData = "unknown button or axis: $name" }
}
