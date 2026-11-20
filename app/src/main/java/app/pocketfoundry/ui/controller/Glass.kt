package app.pocketfoundry.ui.controller

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import app.pocketfoundry.ui.kit.dp

/**
 * Mouse & keys' surfaces (a drawer, the add list, the set chips, the FPS counter): a sheet of smoked glass, lit along
 * its top edge, its shadow on the game below it. The keys are what's solid.
 */
class Glass(private val context: Context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() }
    private val path = Path()
    private val rect = RectF()
    // A sheet's shaders, for its height: each sheet keeps its height while it shows.
    private var shadersFor = -1f
    private var fillShader: LinearGradient? = null
    private var rimShader: LinearGradient? = null

    /** The room its shadow takes round a sheet: left, top, right and bottom, in pixels. */
    val shadowLeft get() = context.dp(SHADOW_BLUR_DP - SHADOW_X_DP)
    val shadowTop get() = context.dp(SHADOW_BLUR_DP - SHADOW_Y_DP)
    val shadowRight get() = context.dp(SHADOW_BLUR_DP + SHADOW_X_DP)
    val shadowBottom get() = context.dp(SHADOW_BLUR_DP + SHADOW_Y_DP)

    fun draw(canvas: Canvas, box: RectF, radius: Float = context.dp(RADIUS_DP)) {
        path.reset()
        path.addRoundRect(box, radius, radius, Path.Direction.CW)
        // The shadow only round the sheet: under it, it would darken the glass.
        canvas.save()
        canvas.clipOutPath(path)
        shadow.setShadowLayer(context.dp(SHADOW_BLUR_DP), context.dp(SHADOW_X_DP), context.dp(SHADOW_Y_DP), SHADOW)
        canvas.drawPath(path, shadow)
        canvas.restore()
        val h = box.height()
        if (shadersFor != h) {
            shadersFor = h
            val lit = (1.6f * radius / h).coerceAtMost(0.5f)
            fillShader = LinearGradient(0f, 0f, 0f, h, TOP, BOTTOM, Shader.TileMode.CLAMP)
            rimShader = LinearGradient(0f, 0f, 0f, h, intArrayOf(RIM_LIT, 0, 0, RIM_SHADE),
                                       floatArrayOf(0f, lit, 1f - lit, 1f), Shader.TileMode.CLAMP)
        }
        canvas.save()
        canvas.translate(box.left, box.top)
        rect.set(0f, 0f, box.width(), h)
        fill.shader = fillShader
        canvas.drawRoundRect(rect, radius, radius, fill)
        val edge = context.dp(RIM_DP)
        rect.inset(edge / 2, edge / 2)
        rim.strokeWidth = edge
        rim.shader = rimShader
        canvas.drawRoundRect(rect, radius - edge / 2, radius - edge / 2, rim)
        canvas.restore()
    }

    private companion object {
        const val RADIUS_DP = 9f
        const val RIM_DP = 0.8f
        /** Its tint top and bottom; its rim lit at the top and shaded at the bottom. */
        const val TOP = 0xE2363533.toInt()
        const val BOTTOM = 0xEE2A2928.toInt()
        const val RIM_LIT = 0x45FFFFFF
        const val RIM_SHADE = 0x6E000000
        /** Its shadow: soft, to the lower right, as the rendered keys' fall. */
        const val SHADOW = 0x59000000
        const val SHADOW_BLUR_DP = 7.5f
        const val SHADOW_X_DP = 2f
        const val SHADOW_Y_DP = 3.75f
    }
}
