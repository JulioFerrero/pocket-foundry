package app.pocketfoundry.core.game

import android.content.Context
import android.os.PowerManager
import app.pocketfoundry.core.logs.ScreenLog
import app.pocketfoundry.core.system.BusyCores
import app.pocketfoundry.core.system.CpuClocks
import app.pocketfoundry.core.system.Paths
import app.pocketfoundry.core.system.Settings
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * The phone's fast cores kept busy while the game falls behind its frame rate. Factorio's threads take turns, each
 * waiting on the next (the update on the drawing, the drawing on the driver and the X server), so no core looks busy
 * enough for the phone to raise its clock, and the cores sleep between turns. Loops at the idle priority on the fast
 * cores (BusyCores) keep the clocks up and the cores awake without taking time from the game.
 *
 * Measured on a Xiaomi 12T (2026-11-21), whose phone ignores Android's performance hints: a heavy map went from 18 to
 * 54 FPS, its fast cores from 1.1 to 2.35 GHz and the CPU's hottest from 47 to 59 °C; a light one zoomed out from 52 to
 * 58. Loops on two of the four fast cores gave 29 FPS; Factorio's threads at a higher priority, or kept on the fast
 * cores, changed nothing.
 *
 * Only while the game screen is shown and Fast CPU is on, and not while the phone reports heat (thermal status
 * moderate or worse) or saves battery. [Need] decides from the frame rate.
 */
object CpuBoost {
    private const val TICKS_A_SECOND = (1000 / FrameRate.TICK_MS).toInt()
    private var watch: Thread? = null

    /** As the game screen shows. */
    @Synchronized
    fun start(context: Context) {
        stop()
        val settings = Settings(context)
        val cores = CpuClocks.fastCores()
        if (!settings.fastCpu || cores.isEmpty()) return
        val power = context.getSystemService(PowerManager::class.java)
        val frames = FrameCounter(Paths(context).frames)
        val rate = FrameRate()
        val need = Need(settings.frameRate)
        watch = thread(name = "pocketfoundry-cpu-boost", isDaemon = true) {
            var busy = 0L
            var ticks = 0
            try {
                while (true) {
                    Thread.sleep(FrameRate.TICK_MS)
                    rate.add(frames.sample(), System.nanoTime())
                    if (++ticks % TICKS_A_SECOND != 0) continue
                    val fps = rate.current.toDouble().takeIf { it > 0 }
                    val hot = power.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
                    val wanted = need.second(fps, allowed = !hot && !power.isPowerSaveMode)
                    if (wanted == (busy != 0L)) continue
                    val why = when {
                        hot -> "the phone is hot"
                        power.isPowerSaveMode -> "battery saver"
                        fps == null -> "no frames"
                        else -> "${fps.roundToInt()} FPS of ${settings.frameRate}"
                    }
                    if (wanted) {
                        busy = BusyCores.start(cores)
                        if (busy == 0L) return@thread ScreenLog.add("fast CPU: its loops didn't start")
                    } else {
                        BusyCores.stop(busy)
                        busy = 0
                    }
                    ScreenLog.add("fast CPU ${if (wanted) "on, cores ${cores.joinToString(",")}" else "off"}: $why")
                }
            } catch (_: InterruptedException) {
            } finally {
                if (busy != 0L) BusyCores.stop(busy)
            }
        }
    }

    /** As the game screen hides. */
    @Synchronized
    fun stop() {
        watch?.interrupt()
        watch = null
    }

    /**
     * Second by second, whether to boost: once the game has been below 90 % of [target] for [SLOW_FOR] seconds. Whether
     * it still needs it only shows without it, so after [FIRST_WAIT] seconds keeping up it stops for a [TRIAL]: behind
     * for [TRIAL_SLOW_FOR] seconds then (the first can dip alone while the clocks settle), it's back on and waits twice
     * as long before the next one (up to [LONGEST_WAIT]).
     */
    internal class Need(private val target: Int) {
        private var on = false
        private var slow = 0
        private var keepingUp = 0
        private var trial = 0
        private var wait = FIRST_WAIT

        /** [fps]: the last second's, null without frames. [allowed]: false while the phone is hot or saves battery. */
        fun second(fps: Double?, allowed: Boolean): Boolean {
            if (fps == null || !allowed) {
                on = false
                slow = 0
                trial = 0
                return false
            }
            val behind = fps < target * BEHIND
            when {
                on -> {
                    keepingUp = if (behind) 0 else keepingUp + 1
                    if (keepingUp >= wait) {
                        on = false
                        keepingUp = 0
                        trial = TRIAL
                    }
                }
                trial > 0 -> {
                    trial--
                    slow = if (behind) slow + 1 else 0
                    if (slow >= TRIAL_SLOW_FOR) {
                        on = true
                        trial = 0
                        slow = 0
                        wait = minOf(wait * 2, LONGEST_WAIT)
                    } else if (trial == 0) {
                        slow = 0
                        wait = FIRST_WAIT
                    }
                }
                else -> {
                    slow = if (behind) slow + 1 else 0
                    if (slow >= SLOW_FOR) {
                        on = true
                        slow = 0
                    }
                }
            }
            return on
        }

        companion object {
            const val BEHIND = 0.9
            const val SLOW_FOR = 3
            const val FIRST_WAIT = 120
            const val TRIAL = 5
            const val TRIAL_SLOW_FOR = 2
            const val LONGEST_WAIT = 960
        }
    }
}
