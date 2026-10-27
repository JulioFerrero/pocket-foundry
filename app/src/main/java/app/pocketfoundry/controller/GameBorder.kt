package app.pocketfoundry.controller

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PorterDuff
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import com.termux.x11.MainActivity
import app.pocketfoundry.Paths
import app.pocketfoundry.Saves
import app.pocketfoundry.Settings
import app.pocketfoundry.ui.dpi
import java.io.File
import kotlin.concurrent.thread

/**
 * The frame round the game when Settings asks for one: Termux:X11's view drawn a little smaller,
 * so the screen's rounded corners only cut into the frame, over the newest save's picture softened
 * and darkened (a terrain texture from the player's copy before there is a save). The X screen
 * follows the view's size, and pocketfoundry-wm keeps the game filling it.
 */
class GameBorder(private val activity: MainActivity) {
    private val frame: FrameLayout = activity.findViewById(com.termux.x11.R.id.frame)
    private val game: View = activity.findViewById(com.termux.x11.R.id.lorieView)
    private val backdrop = ImageView(activity).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setColorFilter(0x80000000.toInt(), PorterDuff.Mode.SRC_ATOP)
        visibility = View.GONE
    }
    private var shown: String? = null

    init {
        // Behind the game's view, which punches its own hole through it.
        frame.addView(backdrop, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** Settings may have changed while the launcher was in front. */
    fun apply() {
        val border = activity.dpi(Settings.border(activity))
        val params = game.layoutParams as FrameLayout.LayoutParams
        if (params.leftMargin != border) {
            params.setMargins(border, border, border, border)
            game.layoutParams = params
        }
        backdrop.visibility = if (border > 0) View.VISIBLE else View.GONE
        if (border > 0) load()
    }

    private fun load() {
        val paths = Paths(activity)
        val save = Saves.newest(paths)
        val key = save?.let { "${it.path}@${it.lastModified()}" } ?: "terrain"
        if (key == shown) return
        shown = key
        thread(name = "pocketfoundry-border") {
            val picture = save?.let { Saves.preview(it, 2) }
                ?: BitmapFactory.decodeFile(File(paths.game, TERRAIN).path, BitmapFactory.Options().apply { inSampleSize = 2 })
            val soft = picture?.let(::soften)
            activity.runOnUiThread { if (shown == key) backdrop.setImageBitmap(soft) }
        }
    }

    /** A small, blurred copy: drawn full screen it keeps the colours and a little of the texture. */
    private fun soften(picture: Bitmap): Bitmap {
        val w = SOFT_WIDTH
        val h = maxOf(1, picture.height * w / picture.width)
        val pixels = IntArray(w * h)
        Bitmap.createScaledBitmap(picture, w, h, true).getPixels(pixels, 0, w, 0, 0, w, h)
        repeat(2) { boxBlur(pixels, w, h, 2) }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    /** One horizontal and one vertical pass of a box blur; two of them come close to a Gaussian. */
    private fun boxBlur(pixels: IntArray, w: Int, h: Int, radius: Int) {
        val out = IntArray(pixels.size)
        for (pass in 0..1) {
            val (src, dst) = if (pass == 0) pixels to out else out to pixels
            val (lines, length) = if (pass == 0) h to w else w to h
            for (line in 0 until lines) {
                for (i in 0 until length) {
                    var r = 0; var g = 0; var b = 0; var n = 0
                    for (k in maxOf(0, i - radius)..minOf(length - 1, i + radius)) {
                        val p = src[if (pass == 0) line * w + k else k * w + line]
                        r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF; n++
                    }
                    dst[if (pass == 0) line * w + i else i * w + line] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
                }
            }
        }
    }

    companion object {
        private const val SOFT_WIDTH = 160
        private const val TERRAIN = "data/base/graphics/terrain/grass-1.png"
    }
}
