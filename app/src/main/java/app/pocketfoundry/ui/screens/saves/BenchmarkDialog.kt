package app.pocketfoundry.ui.screens.saves

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import app.pocketfoundry.LauncherActivity
import app.pocketfoundry.core.game.Benchmark
import app.pocketfoundry.core.game.GameImport
import app.pocketfoundry.core.logs.Phone
import app.pocketfoundry.core.system.CpuClocks
import app.pocketfoundry.core.system.appVersion
import app.pocketfoundry.core.system.background
import app.pocketfoundry.core.system.mainHandler
import app.pocketfoundry.ui.kit.Factorio
import app.pocketfoundry.ui.kit.Frame
import app.pocketfoundry.ui.kit.Progress
import app.pocketfoundry.ui.kit.add
import app.pocketfoundry.ui.kit.button
import app.pocketfoundry.ui.kit.heading
import app.pocketfoundry.ui.kit.label
import app.pocketfoundry.ui.kit.shown
import app.pocketfoundry.ui.kit.stack
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Factorio's benchmark of a save (Benchmark): its progress, then the updates a second, each run and the clocks the
 * phone allowed meanwhile, with a line to copy into a report.
 */
class BenchmarkDialog(private val launcher: LauncherActivity) {
    /** [onBack]: back to where it was opened, after stopping a run that's still going. */
    fun show(save: File, onBack: () -> Unit) {
        val stopped = AtomicBoolean(false)
        var copyText: String? = null
        val progress = Progress(launcher)
        val ups = heading(launcher, "", 24f)
        val runs = label(launcher, "", 14f, Factorio.MUTED)
        val clocks = label(launcher, "", 14f, Factorio.MUTED)
        val result = stack(launcher, Frame.SHALLOW, padding = 12, gap = 4) {
            add(ups)
            add(runs)
            add(clocks)
        }
        val copy = button(launcher, "Copy") {
            copyText?.let { text ->
                launcher.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("Pocket Foundry benchmark", text))
            }
        }
        copy.isEnabled = false
        launcher.host.show("Benchmark: ${save.nameWithoutExtension}", WIDTH, "Close", copy,
                           onBack = { stopped.set(true); onBack() }) {
            add(progress.view)
            add(result, top = 8)
        }
        result.shown = false
        progress.show("Loading the save", 0f)
        background("benchmark", {
            Benchmark.run(launcher, save, stopped::get) { done ->
                mainHandler.post {
                    if (!stopped.get()) progress.show("Run ${minOf(done + 1, Benchmark.RUNS)} of ${Benchmark.RUNS}",
                                                done.toFloat() / Benchmark.RUNS)
                }
            }
        }) { outcome ->
            if (stopped.get()) return@background
            outcome.onSuccess { r ->
                progress.view.shown = false
                ups.text = String.format(Locale.ROOT, "%.1f UPS", r.ups)
                runs.text = "Best of ${Benchmark.RUNS} runs of ${Benchmark.TICKS} updates, no mods: " +
                    r.runsMs.joinToString(" · ") { String.format(Locale.ROOT, "%.2f s", it / 1000) }
                clocks.text = r.clocks.joinToString("\n", transform = ::clockLine)
                clocks.shown = r.clocks.isNotEmpty()
                result.shown = true
                copyText = summary(r)
                copy.isEnabled = true
            }.onFailure { error ->
                progress.show(error.message ?: "It didn't run. See Logs.", 0f)
            }
        }
    }

    /** "Cores 6-7: up to 2.44 GHz, allowed 1.40 to 2.44 of 4.32"; never held: "Cores 0-5: up to 3.53 of 3.53 GHz". */
    private fun clockLine(group: CpuClocks.Group): String {
        val peak = ghz(group.peakKhz)
        if (!group.capped) return "Cores ${group.cores}: up to $peak of ${ghz(group.topKhz)} GHz"
        val allowed = if (group.mostAllowedKhz <= group.allowedKhz) ghz(group.allowedKhz)
                      else "${ghz(group.allowedKhz)} to ${ghz(group.mostAllowedKhz)}"
        return "Cores ${group.cores}: up to $peak GHz, allowed $allowed of ${ghz(group.topKhz)}"
    }

    /** One line for a report or the phones list. */
    private fun summary(r: Benchmark.Result): String {
        val held = r.clocks.filter { it.capped }.joinToString(", ") {
            "cores ${it.cores} allowed ${ghz(it.allowedKhz)}-${ghz(it.mostAllowedKhz)} of ${ghz(it.topKhz)} GHz"
        }
        return listOfNotNull(
            String.format(Locale.ROOT, "Factorio benchmark: %s, %.1f UPS (best of %d × %d updates, no mods)",
                          r.save, r.ups, Benchmark.RUNS, Benchmark.TICKS),
            "${Phone.label}, Android ${Build.VERSION.RELEASE}",
            GameImport.installedVersion(launcher)?.let { "Factorio $it" },
            "Pocket Foundry ${launcher.appVersion()}",
            held.ifEmpty { null },
        ).joinToString(" · ")
    }

    private fun ghz(khz: Long) = String.format(Locale.ROOT, "%.2f", khz / 1e6)

    private companion object {
        const val WIDTH = 600
    }
}
