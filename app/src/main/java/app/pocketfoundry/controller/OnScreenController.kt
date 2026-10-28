package app.pocketfoundry.controller

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import com.termux.x11.MainActivity
import app.pocketfoundry.LauncherActivity
import app.pocketfoundry.ScreenLog
import app.pocketfoundry.Session
import app.pocketfoundry.Settings
import kotlin.math.abs
import java.util.WeakHashMap

/** Pocket Foundry's side of Termux:X11's game activity, added from outside so that activity stays unchanged. */
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
            // The game has its own task: finishing it alone wouldn't bring the launcher back.
            resumed?.let { it.startActivity(Intent(it, LauncherActivity::class.java)) }
            views.keys.toList().forEach { it.finish() }
        }
    }

    override fun onActivityPostCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (activity !is MainActivity) return
        // The input mode the game started with: it can't change until the game restarts.
        val keyboard = Session.keyboard
        val view = ControllerView(activity, keyboard)
        activity.addContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                             ViewGroup.LayoutParams.MATCH_PARENT))
        view.onKeyboard = { activity.lorieView.toggleKeyboardVisible() }
        val mouse = PointerGestures.attach(activity, if (keyboard) null else PadPresses()) {
            if (Settings.vibration(activity)) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
        mouse?.let { gestures[activity] = it }
        val launcher = { activity.startActivity(Intent(activity, LauncherActivity::class.java)) }
        val controller = Gamepads(activity, launcher) { view.padInHand = it }
        pads[activity] = controller
        takeKeysFirst(activity, controller::key)
        activity.window.callback = TouchRouter(activity.window.callback, view, mouse, controller::key, controller::motion, launcher)
        views[activity] = view
        borders[activity] = GameBorder(activity)
        ScreenLog.add("game screen opened")
        watchConnection(activity)
    }

    /** Termux:X11 shows "Not connected" until its screen joins the X server; screen.log says whether it did. */
    private fun watchConnection(activity: MainActivity) {
        val since = SystemClock.uptimeMillis()
        val handler = Handler(Looper.getMainLooper())
        handler.post(object : Runnable {
            override fun run() {
                if (activity.isDestroyed) return
                if (activity.lorieView.connected()) return ScreenLog.add("game screen connected")
                if (SystemClock.uptimeMillis() - since > 20_000) {
                    return ScreenLog.add("game screen still not connected after 20 s; the announcement " +
                                         if (ScreenLog.announced) "arrived" else "never arrived")
                }
                handler.postDelayed(this, 250)
            }
        })
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

    /**
     * Termux:X11 sends keys to X before the window sees them (LorieView's pre-IME dispatch), so [look] wraps
     * that listener. If the field can't be reached, [TouchRouter] still offers it the keys that get past.
     */
    private fun takeKeysFirst(activity: MainActivity, look: (KeyEvent) -> Boolean) = runCatching {
        val field = MainActivity::class.java.getDeclaredField("mLorieKeyListener").apply { isAccessible = true }
        val keys = field.get(activity) as View.OnKeyListener
        val ours = View.OnKeyListener { view, code, event -> look(event) || keys.onKey(view, code, event) }
        field.set(activity, ours)
        activity.lorieView.setOnKeyListener(ours)
    }.onFailure { Log.w("PocketFoundry", "Controller buttons may reach Termux:X11 as keys", it) }

    /** 60 Hz: the game draws at most 60 frames a second, and 30 divides it evenly. */
    private fun applyRefreshRate(activity: Activity) {
        val display = activity.display ?: return
        val now = display.mode
        val want = 60f
        val mode = display.supportedModes
            .filter { it.physicalWidth == now.physicalWidth && it.physicalHeight == now.physicalHeight }
            .minByOrNull { abs(it.refreshRate - want) } ?: return
        activity.window.attributes = activity.window.attributes.apply { preferredDisplayModeId = mode.modeId }
    }

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
