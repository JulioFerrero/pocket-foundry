package app.pocketfoundry.ui.screens.layout

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.PorterDuff
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import app.pocketfoundry.core.controller.Input
import app.pocketfoundry.core.game.FrameCounter
import app.pocketfoundry.core.saves.Saves
import app.pocketfoundry.core.system.Paths
import app.pocketfoundry.core.system.Settings
import app.pocketfoundry.core.system.background
import app.pocketfoundry.ui.controller.ControllerView
import app.pocketfoundry.ui.kit.Dropdown
import app.pocketfoundry.ui.kit.MATCH
import app.pocketfoundry.ui.kit.edgeToEdge
import app.pocketfoundry.ui.kit.immersive
import app.pocketfoundry.ui.kit.shown
import app.pocketfoundry.ui.screens.game.FpsCounterView
import java.io.File

/**
 * The controls' editor without a game (Settings -> Controls -> Edit layout): the layout of the controls chosen there,
 * over the newest save's picture. The screen is the game's, Android's own size and edge to edge, so what's placed here
 * sits in the game where it was put. Done or Back saves it and closes.
 */
class LayoutActivity : Activity() {
    private lateinit var view: ControllerView
    private val location = IntArray(2)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge()
        val settings = Settings(this)
        val input = if (settings.controls == Settings.KEYS) Input.KEYS else Input.PAD
        val backdrop = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setColorFilter(DIM, PorterDuff.Mode.SRC_ATOP)
        }
        val fps = FpsCounterView(this, FrameCounter(Paths(this).frames)).apply {
            keys = input == Input.KEYS
            shown = settings.fpsCounter
        }
        view = ControllerView(this, input, fps) { fps.sprites = it }
        view.onEditorDone = ::finish
        setContentView(FrameLayout(this).apply {
            addView(backdrop, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(fps, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                                                  Gravity.TOP or Gravity.START))
        })
        if (fps.shown) fps.post(fps::place)
        view.startEditing()
        showBackdrop(backdrop)
    }

    override fun onResume() {
        super.onResume()
        immersive()
    }

    /** The newest save's picture, as the game would be behind the controls; grass without one. */
    private fun showBackdrop(backdrop: ImageView) {
        val paths = Paths(this)
        background("layout-backdrop", {
            Saves.newest(paths)?.let { Saves.preview(it, 1) }
                ?: BitmapFactory.decodeFile(File(paths.baseGraphics, GRASS).path)
        }) { picture -> picture.getOrNull()?.let(backdrop::setImageBitmap) }
    }

    /** Every finger is the editor's (ControllerView.pointerDown), in its coordinates; while a window is up, its. */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (view.panelOpen) return super.dispatchTouchEvent(event)
        view.getLocationInWindow(location)
        val index = event.actionIndex
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
                view.pointerDown(event.getPointerId(index), event.getX(index) - location[0], event.getY(index) - location[1])
            MotionEvent.ACTION_MOVE -> for (i in 0 until event.pointerCount) {
                view.pointerMove(event.getPointerId(i), event.getX(i) - location[0], event.getY(i) - location[1])
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> view.pointerUp(event.getPointerId(index))
            MotionEvent.ACTION_CANCEL -> view.releaseAll()
        }
        return true
    }

    /** Back closes a list or a window that's open, else saves and closes, as Done. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            Dropdown.dismissOpen() -> {}
            view.panelOpen -> view.closePanel()
            else -> view.finishEditing()
        }
    }

    private companion object {
        const val DIM = 0x66000000
        const val GRASS = "terrain/grass-1.png"
    }
}
