package app.pocketfoundry.controller

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.ViewGroup
import com.termux.x11.MainActivity
import app.pocketfoundry.LauncherActivity
import app.pocketfoundry.Session
import app.pocketfoundry.Settings
import kotlin.math.abs
import java.util.WeakHashMap

/**
 * Pocket Foundry's side of Termux:X11's game activity, without changing that activity:
 * the controller over it, Back to the launcher, the game paused while it's off
 * screen, and the activity closed when the game exits.
 */
object OnScreenController : Application.ActivityLifecycleCallbacks {
    private val views = WeakHashMap<Activity, ControllerView>()
    private val gestures = WeakHashMap<Activity, PointerGestures>()
    private val pads = WeakHashMap<Activity, Gamepads>()
    private val borders = WeakHashMap<Activity, GameBorder>()
    private var resumed: Activity? = null

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(this)
        Session.addListener {
            if (Session.state != Session.State.STOPPED) return@addListener
            // Seen from the game: land back on the launcher (its own task).
            resumed?.let { it.startActivity(Intent(it, LauncherActivity::class.java)) }
            views.keys.toList().forEach { it.finish() }
        }
    }

    override fun onActivityPostCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (activity !is MainActivity) return
        // The controls the game was started with: its input mode follows them.
        val touch = Session.controls == "touch"
        val view = ControllerView(activity, touch)
        activity.addContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                             ViewGroup.LayoutParams.MATCH_PARENT))
        val lorie = activity.lorieView
        view.keys = { code, down -> lorie.sendKeyEvent(0, code, down) }
        view.onKeyboard = { lorie.toggleKeyboardVisible() }
        // Either way, touching the game is its mouse: in controller mode that moves its cursor
        // to the spot (the buttons act there) and clicks in its menus, and gestures press the pad.
        val mouse = PointerGestures.attach(activity, { view.mining }, if (touch) null else PadPresses()) {
            if (Settings.vibration(activity)) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
        mouse?.let { gestures[activity] = it }
        val launcher = { activity.startActivity(Intent(activity, LauncherActivity::class.java)) }
        // A real controller plays through the same virtual pad, and the drawn one steps aside while it does.
        val controller = Gamepads.attach(activity, launcher) { view.padInHand = it }
        pads[activity] = controller
        activity.window.callback = TouchRouter(activity.window.callback, view, mouse, controller, launcher)
        views[activity] = view
        borders[activity] = GameBorder(activity)
    }

    override fun onActivityStarted(activity: Activity) {
        if (activity !is MainActivity) return
        Session.resume()
        views[activity]?.reload()
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity !is MainActivity) return
        resumed = activity
        applyRefreshRate(activity)
        borders[activity]?.apply()
        if (Settings.takeLayoutEditorRequest(activity)) views[activity]?.startEditing()
    }

    /** The panel at 60 Hz while playing: the game draws at most 60 frames a second, and 30 divides it evenly. */
    private fun applyRefreshRate(activity: Activity) {
        val display = activity.display ?: return
        val now = display.mode
        val want = 60f
        val mode = display.supportedModes
            .filter { it.physicalWidth == now.physicalWidth && it.physicalHeight == now.physicalHeight }
            .minByOrNull { abs(it.refreshRate - want) } ?: return
        activity.window.attributes = activity.window.attributes.apply { preferredDisplayModeId = mode.modeId }
    }

    // Nothing stays held down while the game is off screen.
    override fun onActivityPaused(activity: Activity) {
        if (resumed === activity) resumed = null
        views[activity]?.releaseAll()
        gestures[activity]?.cancel()
        pads[activity]?.release()
    }

    override fun onActivityStopped(activity: Activity) {
        if (activity is MainActivity && Settings.pauseInBackground(activity)) Session.pause()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {
        views.remove(activity)
        gestures.remove(activity)
        pads.remove(activity)?.close()
    }
}
