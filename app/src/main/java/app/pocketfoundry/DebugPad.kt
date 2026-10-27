package app.pocketfoundry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * Drives the virtual gamepad from adb in debug builds, for testing without the
 * on-screen controller:
 *   adb shell am broadcast -a app.pocketfoundry.debug.PAD --es tap south
 *   adb shell am broadcast -a app.pocketfoundry.debug.PAD --es press dpad_down   (then --es release …)
 *   adb shell am broadcast -a app.pocketfoundry.debug.PAD --es axis left_x --ei value 20000
 *   adb shell am broadcast -a app.pocketfoundry.debug.PAD --es run pocketfoundry-padtest   (a files/usr/bin tool)
 */
object DebugPad : BroadcastReceiver() {
    private const val ACTION = "app.pocketfoundry.debug.PAD"
    private val handler = Handler(Looper.getMainLooper())

    fun registerIfDebuggable(context: Context) {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        ContextCompat.registerReceiver(context, this, IntentFilter(ACTION), ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra("run")?.let { name ->
            require(name.matches(Regex("[a-z0-9-]+"))) { "Tool names are plain words" }
            Session.runTool(context, name)
            resultData = "started $name"
            return
        }
        intent.getStringExtra("tap")?.let { name ->
            val button = PadButton.valueOf(name.uppercase())
            InputBridge.button(button, true)
            handler.postDelayed({ InputBridge.button(button, false) }, 120)
        }
        intent.getStringExtra("press")?.let { InputBridge.button(PadButton.valueOf(it.uppercase()), true) }
        intent.getStringExtra("release")?.let { InputBridge.button(PadButton.valueOf(it.uppercase()), false) }
        intent.getStringExtra("axis")?.let {
            InputBridge.axis(PadAxis.valueOf(it.uppercase()), intent.getIntExtra("value", 0))
        }
        resultData = if (InputBridge.connected) "sent" else "gamepad not connected"
    }
}
