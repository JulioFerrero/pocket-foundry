package app.pocketfoundry.ui.kit

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.view.Display
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import kotlin.math.roundToInt

/**
 * Our screens at this much of Android's size: at the game's scale they were too big for a phone, with room for little
 * text. Both our activities apply it (scaleUi); Termux:X11's game screen keeps Android's.
 */
const val UI_SCALE = 0.85f

/**
 * In attachBaseContext. Only the density changes, so the rest of the configuration (font size, a foldable's new
 * screen) still follows Android.
 */
fun Activity.scaleUi(base: Context) = applyOverrideConfiguration(Configuration().apply {
    fontScale = 0f   // the default, 1, would override the player's font size
    densityDpi = (base.resources.configuration.densityDpi * UI_SCALE).roundToInt()
})

/** In the scaled dp: Configuration.screenWidthDp keeps Android's. */
val Context.screenWidthDp: Int get() = (resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt()

fun Context.dp(value: Float) = value * resources.displayMetrics.density

fun Context.dpi(value: Int) = (value * resources.displayMetrics.density).roundToInt()

/** The bars hidden until a swipe; Android 10 has only the older flags for it. */
fun Activity.immersive() {
    if (Build.VERSION.SDK_INT < 30) {
        @Suppress("DEPRECATION")
        window.decorView.run {
            systemUiVisibility = systemUiVisibility or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        }
        return
    }
    window.insetsController?.apply {
        hide(WindowInsets.Type.systemBars())
        systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}

/** The screen the activity is on; Activity.display is Android 11's. */
fun Activity.displayCompat(): Display? =
    if (Build.VERSION.SDK_INT >= 30) display else @Suppress("DEPRECATION") windowManager.defaultDisplay

/** Drawn under the system bars; Android 10 has only the older flags for it. */
fun Activity.edgeToEdge() {
    if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
    else {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }
}

fun View.padForCutout() = setOnApplyWindowInsetsListener { view, insets ->
    val cutout = insets.displayCutout
    view.setPadding(cutout?.safeInsetLeft ?: 0, cutout?.safeInsetTop ?: 0, cutout?.safeInsetRight ?: 0, cutout?.safeInsetBottom ?: 0)
    insets
}
