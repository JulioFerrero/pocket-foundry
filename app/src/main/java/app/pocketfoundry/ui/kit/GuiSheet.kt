package app.pocketfoundry.ui.kit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log
import app.pocketfoundry.core.system.Paths
import app.pocketfoundry.core.system.TAG
import app.pocketfoundry.R
import java.io.File
import java.io.FileNotFoundException

object Factorio {
    const val PANEL = 0xFF313031.toInt()
    const val FRAME = 0xFF2E2623.toInt()
    const val INSET_DARK = 0xFF242324.toInt()
    const val INSET_LIGHT = 0xFF414040.toInt()
    const val HEADING = 0xFFFFE6C0.toInt()
    const val TEXT = Color.WHITE
    const val MUTED = 0xFFC9C0B4.toInt()
    const val FAINT = 0xFFA39B91.toInt()
    const val LINK = 0xFF7DCAED.toInt()
    const val ERROR = 0xFFFF9B8F.toInt()
    const val BAR = 0xFFF1A240.toInt()
    const val BODY = 0xFF201810.toInt()

    /** Titillium Web: the game's Lilittium is Titillium with Cyrillic added. Android caches each font it loads. */
    fun font(context: Context, weight: Int): Typeface = context.resources.getFont(when (weight) {
        700 -> R.font.titillium_web_bold
        600 -> R.font.titillium_web_semibold
        else -> R.font.titillium_web_regular
    })
}

enum class Tone(val face: Int, val light: Int, val dark: Int, val glow: Int) {
    GREY(0xFF8E8E8E.toInt(), 0xFFE3E3E3.toInt(), 0xFF282828.toInt(), 0xFFE1B16A.toInt()),
    GREEN(0xFF5EB663.toInt(), 0xFF95DF99.toInt(), 0xFF163218.toInt(), 0xFF87D88B.toInt()),
    ORANGE(0xFFE39C40.toInt(), 0xFFF7CC8F.toInt(), 0xFF5C3D12.toInt(), 0xFFE1B16A.toInt()),
    RED(0xFFE8605A.toInt(), 0xFFF5A29E.toInt(), 0xFF4A1514.toInt(), 0xFFFE5A5A.toInt()),
}

/** The imported game's GUI sheet, read once; null before an import or if the sheet's layout changed. Main thread only. */
object GuiSprites {
    private var sheet: GuiSheet? = null
    private var read = false

    fun get(context: Context): GuiSheet? {
        if (!read) {
            read = true
            sheet = runCatching { GuiSheet(File(Paths(context).game, "data/core/graphics")) }
                .onFailure { if (it !is FileNotFoundException) Log.w(TAG, "Factorio's GUI sprites unusable, drawing the look instead", it) }
                .getOrNull()
        }
        return sheet
    }

    fun reload() {
        read = false
    }
}

/**
 * data/core/graphics/gui-new.png cut at the places in data/core/prototypes/style.lua (Factorio 2.x).
 * Each piece is its own bitmap so scaling can't bleed in its neighbours.
 */
class GuiSheet(dir: File) {
    private val png = BitmapFactory.decodeFile(File(dir, "gui-new.png").path) ?: throw FileNotFoundException("gui-new.png")

    init {
        check(png.width == 597 && png.height == 1220) { "gui-new.png is ${png.width}×${png.height}, not the layout read here" }
    }

    // A panel is the style "frame"; insets are inside_deep_frame and inside_shallow_frame (one border, two fills).
    val frame = nine(0, 0)
    val deepFrame = nine(17, 0, centerX = 42)
    val shallowFrame = nine(17, 0, centerX = 76)

    // button, green_button and red_button. Orange is button's selected look.
    private val buttons = mapOf(
        Tone.GREY to states(0),
        Tone.GREEN to states(68),
        Tone.ORANGE to listOf(nine(225, 17), nine(352, 17), nine(17, 17), nine(369, 17)),
        Tone.RED to states(136),
    )

    // back_button / confirm_button: the pointed end is a 48 × 64 tile from the arrow rows (left half points left,
    // right half right; states 96 px apart), the flat end comes from the button's own tile.
    private val arrows = mapOf(
        Tone.GREY to arrowStates(232, 0),
        Tone.GREEN to arrowStates(296, 68),
        Tone.ORANGE to arrowStates(232, 0, idle = 2),   // grey's hovered look: orange, on grey
        Tone.RED to arrowStates(360, 136),
    )

    val checkbox = SHEET_ORDER.map { piece(it * 28, 132, 28, 28) }
    val check = piece(112, 132, 28, 28)

    /** The dropdown's arrow; null if missing. */
    val caret = largestMip(File(dir, "icons/mip/dropdown.png"), 32)

    // slot_button, the inventory's and quickbar's dark square: idle, clicked, disabled (idle, as the game has none),
    // hovered. Its 80-pixel tile is cut 40 | 1 | 39 and drawn with a corner of half the shorter side, so it scales
    // whole and a wider button stretches only its middle column.
    val slot = intArrayOf(0, 160, 0, 80).map { nine(it, 736, near = 40, far = 39) }

    private val controller = File(dir, "icons/controller")
    private val glyphs = HashMap<String, Bitmap?>()

    /** One of the game's controller glyphs, e.g. "joycon/color/A"; null if it's missing. */
    fun glyph(path: String): Bitmap? {
        // Not getOrPut: that would look for a missing glyph again on every draw.
        if (path !in glyphs) glyphs[path] = largestMip(File(controller, "$path.png"), 40)
        return glyphs[path]
    }

    fun button(tone: Tone, state: Int) = buttons.getValue(tone)[state]
    fun arrow(tone: Tone, state: Int) = arrows.getValue(tone)[state]

    private fun piece(x: Int, y: Int, w: Int, h: Int): Bitmap = Bitmap.createBitmap(png, x, y, w, h)

    private fun states(x: Int) = SHEET_ORDER.map { nine(x + it * 17, 17) }

    private fun arrowStates(row: Int, tile: Int, idle: Int = 0) = SHEET_ORDER.mapIndexed { state, column ->
        val i = if (state == IDLE) idle else column
        val x = i * 96
        val end = tile + i * 17
        ArrowSlices(piece(x, row, 24, 64), piece(x + 24, row, 24, 64), piece(x + 24, row, 1, 64), strip(end), strip(end + 9))
    }

    /** An 8-pixel-wide strip of a button's 17-pixel tile: corner, edge, corner. */
    private fun strip(x: Int) = listOf(piece(x, 17, 8, 8), piece(x, 25, 8, 1), piece(x, 26, 8, 8))

    /** A tile cut [near] | 1 | [far] both ways, 17 pixels by default; the 1-pixel centre can come from another column. */
    private fun nine(x: Int, y: Int, near: Int = 8, far: Int = 8, centerX: Int = x + near): NineSlice {
        val at = intArrayOf(0, near, near + 1)
        val size = intArrayOf(near, 1, far)
        return NineSlice(List(9) { i ->
            if (i == 4) piece(centerX, y + near, 1, 1)
            else piece(x + at[i % 3], y + at[i / 3], size[i % 3], size[i / 3])
        })
    }

    /** A mipmapped icon's largest mip, [size] px; null if the file is missing. */
    private fun largestMip(file: File, size: Int) =
        BitmapFactory.decodeFile(file.path)?.let { Bitmap.createBitmap(it, 0, 0, minOf(size, it.width), minOf(size, it.height)) }

    // Last: every piece above is cut from png.
    init {
        png.recycle()
    }

    companion object {
        // The index into every state list here.
        const val IDLE = 0
        const val PRESSED = 1
        const val DISABLED = 2
        const val HOVERED = 3
        /** Corner size in dp: one sprite pixel per dp, the game's 100% scale. */
        const val CORNER = 8f
        /** The arrow end's width, per height of the face. */
        const val ARROW_TIP = 24f / 64
        /** The sheet column of each state: the sheet lays out idle, disabled, hovered, clicked. */
        private val SHEET_ORDER = intArrayOf(0, 3, 1, 2)
    }
}

class NineSlice(private val parts: List<Bitmap>) {
    private val cell = RectF()
    private val xs = FloatArray(4)
    private val ys = FloatArray(4)

    fun draw(canvas: Canvas, r: RectF, corner: Float, paint: Paint) {
        val c = minOf(corner, r.width() / 2, r.height() / 2)
        xs[0] = r.left
        xs[1] = r.left + c
        xs[2] = r.right - c
        xs[3] = r.right
        ys[0] = r.top
        ys[1] = r.top + c
        ys[2] = r.bottom - c
        ys[3] = r.bottom
        for (i in 0 until 9) {
            cell.set(xs[i % 3], ys[i / 3], xs[i % 3 + 1], ys[i / 3 + 1])
            canvas.drawBitmap(parts[i], null, cell, paint)
        }
    }
}

/** A back or confirm button's face: a flat end, a stretched middle and a point, on the left or the right. */
class ArrowSlices(private val pointLeft: Bitmap, private val pointRight: Bitmap, private val middle: Bitmap,
                  private val endLeft: List<Bitmap>, private val endRight: List<Bitmap>) {
    private val cell = RectF()

    fun draw(canvas: Canvas, face: RectF, left: Boolean, paint: Paint) {
        val tip = face.height() * GuiSheet.ARROW_TIP
        // The flat end's 8-px corner, scaled like the 64-px-high arrow so their bevels meet.
        val c = face.height() * 8 / 64
        if (left) {
            put(canvas, pointLeft, face.left, face.top, face.left + tip, face.bottom, paint)
            put(canvas, middle, face.left + tip, face.top, face.right - c, face.bottom, paint)
            end(canvas, endRight, face.right - c, face, c, paint)
        } else {
            end(canvas, endLeft, face.left, face, c, paint)
            put(canvas, middle, face.left + c, face.top, face.right - tip, face.bottom, paint)
            put(canvas, pointRight, face.right - tip, face.top, face.right, face.bottom, paint)
        }
    }

    private fun end(canvas: Canvas, pieces: List<Bitmap>, x: Float, face: RectF, c: Float, paint: Paint) {
        put(canvas, pieces[0], x, face.top, x + c, face.top + c, paint)
        put(canvas, pieces[1], x, face.top + c, x + c, face.bottom - c, paint)
        put(canvas, pieces[2], x, face.bottom - c, x + c, face.bottom, paint)
    }

    private fun put(canvas: Canvas, bitmap: Bitmap, l: Float, t: Float, r: Float, b: Float, paint: Paint) {
        cell.set(l, t, r, b)
        canvas.drawBitmap(bitmap, null, cell, paint)
    }
}
