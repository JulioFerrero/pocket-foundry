package app.pocketfoundry.game

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PorterDuff
import android.os.Bundle
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import com.termux.x11.MainActivity
import app.pocketfoundry.LauncherActivity
import app.pocketfoundry.Paths
import app.pocketfoundry.Settings
import app.pocketfoundry.controller.ControllerView
import app.pocketfoundry.controller.Gamepads
import app.pocketfoundry.controller.InputRouter
import app.pocketfoundry.controller.PointerGestures
import app.pocketfoundry.mainHandler
import app.pocketfoundry.saves.Saves
import app.pocketfoundry.ui.MATCH
import app.pocketfoundry.ui.background
import app.pocketfoundry.ui.displayCompat
import app.pocketfoundry.ui.dpi
import app.pocketfoundry.ui.shown
import kotlin.math.abs
import java.io.File
import java.util.WeakHashMap

/** Pocket Foundry's side of Termux:X11's game activity, added from outside so that activity stays unchanged. */
object GameScreens : Application.ActivityLifecycleCallbacks {
    private const val CONNECT_TIMEOUT_MS = 20_000L
    private const val CONNECT_POLL_MS = 250L
    private val screens = WeakHashMap<Activity, GameScreen>()
    private var resumed: Activity? = null

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(this)
        Session.addListener {
            if (Session.inGame) return@addListener
            // The game has its own task: finishing it alone wouldn't bring the launcher back.
            resumed?.let { toLauncher(it) }
            screens.keys.toList().forEach { it.finish() }
        }
    }

    override fun onActivityPostCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (activity !is MainActivity) return
        // Back from Recents after the game ended in the background: the X server went with it.
        if (!Session.inGame) {
            ScreenLog.add("game screen opened after the game ended; back to the launcher")
            toLauncher(activity)
            activity.finish()
            return
        }
        screens[activity] = GameScreen(activity)
        ScreenLog.add("game screen opened")
        watchConnection(activity)
    }

    /** Termux:X11 shows "Not connected" until its screen joins the X server; screen.log says whether it did. */
    private fun watchConnection(activity: MainActivity) {
        val since = SystemClock.uptimeMillis()
        fun check() {
            if (activity.isDestroyed) return
            if (activity.lorieView.connected()) return ScreenLog.add("game screen connected")
            if (SystemClock.uptimeMillis() - since > CONNECT_TIMEOUT_MS) {
                return ScreenLog.add("game screen still not connected after ${CONNECT_TIMEOUT_MS / 1000} s; " +
                                     "the announcement " + if (ScreenLog.announced) "arrived" else "never arrived")
            }
            mainHandler.postDelayed(::check, CONNECT_POLL_MS)
        }
        mainHandler.post(::check)
    }

    override fun onActivityStarted(activity: Activity) {
        if (activity !is MainActivity) return
        Session.resume()
        screens[activity]?.view?.reload()
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity !is MainActivity) return
        resumed = activity
        applyRefreshRate(activity)
        val screen = screens[activity]
        screen?.border?.apply()
        if (Settings(activity).takeLayoutEditorRequest()) screen?.view?.startEditing()
    }

    /** 60 Hz: the game draws at most 60 frames a second, and 30 divides it evenly. */
    private fun applyRefreshRate(activity: Activity) {
        val display = activity.displayCompat() ?: return
        val now = display.mode
        val mode = display.supportedModes
            .filter { it.physicalWidth == now.physicalWidth && it.physicalHeight == now.physicalHeight }
            .minByOrNull { abs(it.refreshRate - 60f) } ?: return
        activity.window.attributes = activity.window.attributes.apply { preferredDisplayModeId = mode.modeId }
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumed === activity) resumed = null
        screens[activity]?.pause()
    }

    override fun onActivityStopped(activity: Activity) {
        if (activity is MainActivity && Settings(activity).pauseInBackground) Session.pause()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {
        screens.remove(activity)?.close()
    }
}

private class GameScreen(activity: MainActivity) {
    private val keyboard = Session.keyboard
    private val settings = Settings(activity)
    val view = ControllerView(activity, keyboard)
    private val gestures = PointerGestures(activity, padMode = !keyboard) {
        if (settings.vibration) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }
    private val launcher = { toLauncher(activity) }
    private val pads = Gamepads(activity, launcher) { view.padInHand = it }
    val border = GameBorder(activity)

    init {
        activity.addContentView(view, ViewGroup.LayoutParams(MATCH, MATCH))
        view.onKeyboard = { activity.lorieView.toggleKeyboardVisible() }
        takeKeysFirst(activity, pads::key)
        activity.window.callback = InputRouter(activity.window.callback, view, gestures, pads::key, pads::motion, launcher)
    }

    fun pause() {
        view.releaseAll()
        gestures.cancel()
        pads.release()
    }

    fun close() = pads.close()

    /**
     * Termux:X11 sends keys to X before the window sees them (LorieView.dispatchKeyEventPreIme -> mLorieKeyListener),
     * so [look] goes in front of that listener. [InputRouter] offers [look] the keys that skip it.
     */
    private fun takeKeysFirst(activity: MainActivity, look: (KeyEvent) -> Boolean) {
        val keys = activity.mLorieKeyListener
        val ours = View.OnKeyListener { view, code, event -> look(event) || keys.onKey(view, code, event) }
        activity.mLorieKeyListener = ours
        activity.lorieView.setOnKeyListener(ours)
    }
}

/**
 * Shrinks Termux:X11's view so rounded screen corners only cut the frame; the X screen follows its size. The border
 * shows the newest save's preview (or grass), blurred and darkened.
 */
private class GameBorder(private val activity: MainActivity) {
    private val frame: FrameLayout = activity.findViewById(com.termux.x11.R.id.frame)
    private val game = activity.lorieView
    private val backdrop = ImageView(activity).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setColorFilter(DIM, PorterDuff.Mode.SRC_ATOP)
        shown = false
    }
    private var showing: String? = null

    init {
        frame.addView(backdrop, 0, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    fun apply() {
        val border = activity.dpi(Settings(activity).border)
        val params = game.layoutParams as FrameLayout.LayoutParams
        if (params.leftMargin != border) {
            params.setMargins(border, border, border, border)
            game.layoutParams = params
        }
        backdrop.shown = border > 0
        if (border > 0) loadBackdrop()
    }

    private fun loadBackdrop() {
        val paths = Paths(activity)
        val save = Saves.newest(paths)
        val key = save?.let(Saves::previewKey) ?: "terrain"
        if (key == showing) return
        showing = key
        background("border", {
            val picture = save?.let { Saves.preview(it, SAMPLE_SIZE) }
                ?: BitmapFactory.decodeFile(File(paths.game, TERRAIN).path,
                                            BitmapFactory.Options().apply { inSampleSize = SAMPLE_SIZE })
            picture?.let(::soften)
        }) { soft -> if (showing == key) backdrop.setImageBitmap(soft.getOrNull()) }
    }

    private fun soften(picture: Bitmap): Bitmap {
        val w = SOFT_WIDTH
        val h = maxOf(1, picture.height * w / picture.width)
        val pixels = IntArray(w * h)
        val blurredRows = IntArray(w * h)
        Bitmap.createScaledBitmap(picture, w, h, true).getPixels(pixels, 0, w, 0, 0, w, h)
        repeat(BLUR_PASSES) {
            for (row in 0 until h) blurLine(pixels, blurredRows, row * w, 1, w)
            for (column in 0 until w) blurLine(blurredRows, pixels, column, w, h)
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    /** One row or column: [length] pixels from [start], [step] apart, each averaged with [BLUR_RADIUS] either side. */
    private fun blurLine(src: IntArray, dst: IntArray, start: Int, step: Int, length: Int) {
        for (i in 0 until length) {
            var r = 0; var g = 0; var b = 0; var n = 0
            for (k in maxOf(0, i - BLUR_RADIUS)..minOf(length - 1, i + BLUR_RADIUS)) {
                val p = src[start + k * step]
                r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF; n++
            }
            dst[start + i * step] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
        }
    }

    companion object {
        private const val SOFT_WIDTH = 160
        /** Half the backdrop's light. */
        private const val DIM = 0x80000000.toInt()
        /** Pictures are read at half size: they shrink to [SOFT_WIDTH] anyway. */
        private const val SAMPLE_SIZE = 2
        private const val BLUR_RADIUS = 2
        private const val BLUR_PASSES = 2
        private const val TERRAIN = "data/base/graphics/terrain/grass-1.png"
    }
}

private fun toLauncher(activity: Activity) = activity.startActivity(Intent(activity, LauncherActivity::class.java))
