package app.pocketfoundry.launcher

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.StyleSpan
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import app.pocketfoundry.Paths
import app.pocketfoundry.appVersion
import app.pocketfoundry.controller.gamepads
import app.pocketfoundry.drivers.Drivers
import app.pocketfoundry.drivers.Gpu
import app.pocketfoundry.mainHandler
import app.pocketfoundry.ui.FButton
import app.pocketfoundry.ui.Factorio
import app.pocketfoundry.ui.Frame
import app.pocketfoundry.ui.MATCH
import app.pocketfoundry.ui.add
import app.pocketfoundry.ui.buttonBar
import app.pocketfoundry.ui.dpi
import app.pocketfoundry.ui.immersive
import app.pocketfoundry.ui.pad
import app.pocketfoundry.ui.panel
import app.pocketfoundry.ui.stack
import java.io.File

/** The phone's facts and the ends of the game's logs, every 2 s, to read or copy into a report. */
class LogActivity : Activity() {
    private val paths by lazy { Paths(this) }
    private val text by lazy {
        TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(TEXT)
            setLineSpacing(0f, 1.15f)
            setTextIsSelectable(true)
            pad(12)
        }
    }
    private val scroll by lazy { ScrollView(this).apply { addView(text) } }
    private var shown = ""
    private val refresh: Runnable = Runnable {
        load()
        mainHandler.postDelayed(refresh, 2000)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val copy = FButton(this, "Copy", size = 15f).apply { minWidth = dpi(110) }
        val copied = Runnable { copy.text = "Copy" }
        copy.setOnClickListener {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Pocket Foundry logs", shown))
            copy.text = "Copied"
            mainHandler.removeCallbacks(copied)
            mainHandler.postDelayed(copied, 2000)
        }
        val panel = panel(this, "Logs") {
            add(stack(context, Frame.DEEP) { add(scroll, MATCH, MATCH) }, height = 0, weight = 1f)
            add(buttonBar(context, "Back", ::finish, copy))
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Factorio.BODY)
            add(panel, start = 24, top = 24, end = 24, bottom = 24)
        })
    }

    override fun onResume() {
        super.onResume()
        immersive()
        mainHandler.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(refresh)
    }

    /** What a report needs besides the logs. */
    private val phone by lazy {
        // Imported drivers also show their file name: PanVK builds all report the same Mesa version.
        val driver = Drivers.find(this, Drivers.chosen(this))
            ?.let { if (it.imported) "${it.name} (${it.detail.substringAfter(" · ")})" else it.name }
        val facts = listOfNotNull("${Build.MANUFACTURER} ${Build.MODEL}", "Android ${Build.VERSION.RELEASE}",
                                  Gpu.name ?: "GPU unknown", driver, "Pocket Foundry ${appVersion()}")
        val pads = gamepads(this).joinToString(", ") { it.name + if (it.isExternal) "" else " (built in)" }
        val options = Drivers.options(this, Drivers.chosen(this))
        "── phone ──\n" + facts.joinToString(" · ") + "\n" +
            (if (pads.isNotEmpty()) "Controllers: $pads\n" else "") +
            (if (options.isNotEmpty()) "Driver options: $options\n" else "")
    }

    private fun load() {
        val raw = phone + LOG_TAILS.joinToString("\n") { (log, lines) ->
            val file = log(paths)
            "── ${file.name} ──\n" + tail(file, lines)
        }
        if (raw == shown) return
        val atBottom = !scroll.canScrollVertically(1)
        shown = raw
        text.text = colorize(raw)
        if (atBottom) scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun tail(file: File, lines: Int): String = when {
        !file.exists() -> "(none yet)"
        file.length() == 0L -> "(empty)"
        else -> file.readLines().takeLast(lines).joinToString("\n")
    }

    private fun colorize(raw: String): CharSequence {
        val out = SpannableStringBuilder()
        for (line in raw.lines()) {
            val start = out.length
            val end = start + line.length
            val stamp = TIMESTAMP.find(line)
            out.append(line).append('\n')
            fun span(what: Any, to: Int = end) = out.setSpan(what, start, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            when {
                line.startsWith("── ") -> {
                    span(ForegroundColorSpan(Factorio.HEADING))
                    span(StyleSpan(Typeface.BOLD))
                }
                ERROR.containsMatchIn(line) -> span(ForegroundColorSpan(RED))
                WARNING.containsMatchIn(line) -> span(ForegroundColorSpan(AMBER))
                stamp != null -> span(ForegroundColorSpan(DIM), start + stamp.range.last + 1)
            }
            // Wrapped lines start under the message, not under the timestamp.
            if (stamp != null) span(LeadingMarginSpan.Standard(0, text.paint.measureText(stamp.value).toInt()), end + 1)
        }
        return out
    }

    companion object {
        /** Each log with how many of its last lines to show. */
        private val LOG_TAILS = listOf(Paths::gameLog to 400, Paths::wmLog to 20, Paths::screenLog to 20, Paths::x11Log to 120)
        private val TEXT = Color.rgb(0xD5, 0xD9, 0xE0)
        private val DIM = Color.rgb(0x7A, 0x78, 0x76)
        private val AMBER = Color.rgb(0xE6, 0xB4, 0x50)
        private val RED = Color.rgb(0xFF, 0x6B, 0x6B)
        private val TIMESTAMP = Regex("""^\s*\d+\.\d+ """)
        private val WARNING = Regex("[Ww]arning")
        private val ERROR = Regex("""\b(Error|error|ERROR|FATAL|Failed|failed|cannot|Couldn't)\b""")
    }
}
