package app.pocketfoundry.ui.screens.launcher

import android.view.InputDevice
import android.view.KeyEvent
import android.widget.LinearLayout
import app.pocketfoundry.LauncherActivity
import app.pocketfoundry.core.controller.ButtonMap
import app.pocketfoundry.core.controller.PadTarget
import app.pocketfoundry.ui.kit.FButton
import app.pocketfoundry.ui.kit.Factorio
import app.pocketfoundry.ui.kit.Frame
import app.pocketfoundry.ui.kit.Tone
import app.pocketfoundry.ui.kit.add
import app.pocketfoundry.ui.kit.button
import app.pocketfoundry.ui.kit.label
import app.pocketfoundry.ui.kit.row
import app.pocketfoundry.ui.kit.stack

/**
 * Settings -> Controls -> Controller buttons: what [device]'s buttons do (ButtonMap). Each of the game's buttons shows
 * the controller's that do it; tapping it waits for one to be pressed, which then does it. Usable with the controller
 * alone: a button is chosen on its release, and the wait takes only the next press.
 */
class ButtonsDialog(private val launcher: LauncherActivity, private val device: InputDevice) {
    private val buttons = ButtonMap(launcher)
    private val cells = HashMap<PadTarget, FButton>()
    private var waiting: PadTarget? = null
    /** The press that was just set: its release is the dialog's too, not the launcher's. */
    private var setKey = KeyEvent.KEYCODE_UNKNOWN

    fun show() {
        val reset = button(launcher, "Reset", size = 15f) {
            stopWaiting()
            buttons.reset(device.descriptor)
            refresh()
        }
        launcher.host.show("Controller buttons", DIALOG_DP, "Close", reset,
                           titleEnd = label(launcher, device.name, 15f, Factorio.MUTED)) {
            add(stack(launcher, Frame.DEEP, padding = 10, gap = 6) {
                for (targets in PadTarget.entries.chunked(COLUMNS)) {
                    add(row(launcher) {
                        for (target in targets) add(cell(target), width = 0, weight = 1f, end = 8)
                        // The last row's cells as wide as the others.
                        repeat(COLUMNS - targets.size) { add(LinearLayout(launcher), width = 0, weight = 1f, end = 8) }
                    })
                }
            })
        }
        refresh()
    }

    private fun cell(target: PadTarget) = row(launcher) {
        add(label(launcher, target.title, 15f, weight = 600), width = TITLE_DP)
        val keys = button(launcher, "", size = 13f) { wait(target) }.apply { maxLines = 1 }
        cells[target] = keys
        add(keys, width = 0, height = CELL_HEIGHT_DP, weight = 1f)
    }

    private fun wait(target: PadTarget) {
        if (waiting == target) return stopWaiting()
        waiting = target
        launcher.keyCapture = ::capture
        refresh()
    }

    private fun stopWaiting() {
        waiting = null
        if (setKey == KeyEvent.KEYCODE_UNKNOWN) launcher.keyCapture = null
        refresh()
    }

    /** The next press of this controller's, while waiting; its release after. Closed, the dialog lets go. */
    private fun capture(event: KeyEvent): Boolean {
        if (!launcher.host.isOpen) {
            launcher.keyCapture = null
            return false
        }
        if (event.device?.descriptor != device.descriptor) return false
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == setKey) {
            setKey = KeyEvent.KEYCODE_UNKNOWN
            if (waiting == null) launcher.keyCapture = null
            return true
        }
        val target = waiting ?: return false
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return true
        buttons.set(device.descriptor, event.keyCode, target)
        setKey = event.keyCode
        waiting = null
        refresh()
        return true
    }

    private fun refresh() {
        for ((target, cell) in cells) {
            if (target == waiting) {
                cell.text = "Press one"
                cell.tone = Tone.ORANGE
                continue
            }
            val keys = buttons.keys(device.descriptor, target) { device.hasKeys(it)[0] }
            cell.text = keys.joinToString(", ", transform = ButtonMap::name).ifEmpty { "None" }
            cell.tone = Tone.GREY
        }
    }

    private companion object {
        const val DIALOG_DP = 720
        const val COLUMNS = 4
        const val TITLE_DP = 52
        const val CELL_HEIGHT_DP = 34
    }
}
