package app.pocketfoundry.ui.screens.launcher

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.StyleSpan
import android.util.Log
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import app.pocketfoundry.core.system.Paths
import app.pocketfoundry.core.system.Settings
import app.pocketfoundry.core.system.TAG
import app.pocketfoundry.core.controller.gamepads
import app.pocketfoundry.core.drivers.DriverCheck
import app.pocketfoundry.core.drivers.DriverRecords
import app.pocketfoundry.core.drivers.Catalog
import app.pocketfoundry.core.drivers.Drivers
import app.pocketfoundry.core.drivers.Gpu
import app.pocketfoundry.core.drivers.SpeedTest
import app.pocketfoundry.core.logs.ScreenLog
import app.pocketfoundry.core.runtime.Runtime
import app.pocketfoundry.core.system.appVersion
import app.pocketfoundry.core.system.mainHandler
import app.pocketfoundry.ui.kit.scaleUi
import app.pocketfoundry.ui.kit.FButton
import app.pocketfoundry.ui.kit.Factorio
import app.pocketfoundry.ui.kit.Frame
import app.pocketfoundry.ui.kit.MATCH
import app.pocketfoundry.ui.kit.add
import app.pocketfoundry.ui.kit.buttonBar
import app.pocketfoundry.ui.kit.dpi
import app.pocketfoundry.ui.kit.immersive
import app.pocketfoundry.ui.kit.pad
import app.pocketfoundry.ui.kit.panel
import app.pocketfoundry.ui.kit.stack
import java.io.File
import kotlin.concurrent.thread

/** The phone's facts and the ends of the game's logs, to read or copy into a report. */
class LogActivity : Activity() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        scaleUi(base)
    }

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
            toLogcat(shown)
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
        // The driver the logs are about. Imported drivers also show their file name: PanVK builds all report the same
        // Mesa version.
        val played = ScreenLog.driver ?: Drivers.chosen(this)
        val driver = Drivers.find(this, played)
            ?.let { if (it.origin == Drivers.Origin.APP) it.name else "${it.name} (${it.note})" }
        val facts = listOfNotNull("${Build.MANUFACTURER} ${Build.MODEL}", "Android ${Build.VERSION.RELEASE}",
                                  Gpu.name ?: "GPU unknown", driver, "Pocket Foundry ${appVersion()}")
        val pads = gamepads(this).joinToString(", ") { it.name + if (it.isExternal) "" else " (built in)" }
        val options = DriverRecords.options(this, played)
        val checked = DriverCheck.summary(this, played)
        val speed = DriverCheck.speed(this, played)
        val formats = Gpu.textureFormats.joinToString(", ").ifEmpty { "none" }
        val settings = Settings(this)
        fun onOff(on: Boolean) = if (on) "on" else "off"
        val chosen = listOf("${settings.frameRate} FPS", "sprites ${settings.spriteQuality}",
                            "graphics " + if (settings.lightGraphics) "light" else "full",
                            "FPS counter ${onOff(settings.fpsCounter)}",
                            "pause in background ${onOff(settings.pauseInBackground)}",
                            "detailed logs ${onOff(settings.detailedLogs)}")
        "── phone ──\n" + facts.joinToString(" · ") + "\n" +
            "Settings: ${chosen.joinToString(" · ")}\n" +
            (if (pads.isNotEmpty()) "Controllers: $pads\n" else "") +
            (if (options.isNotEmpty()) "Driver options: $options\n" else "") +
            "GPU's compressed textures: $formats\n" +
            (Runtime.misplaced(this)?.let { "Runtime: $it\n" } ?: "") +
            (if (checked != null) "Driver check: $checked\n" else "") +
            (if (speed.isNotEmpty()) "Speed test: ${SpeedTest.summary(speed)}\n" else "") +
            "Driver list: ${Catalog.status ?: "not fetched yet"}\n"
    }

    private fun load() {
        val raw = phone + LOGS.joinToString("\n") { excerpt ->
            val file = excerpt.log(paths)
            "── ${file.name} ──\n" + excerpt.of(file)
        }
        if (raw == shown) return
        val atBottom = !scroll.canScrollVertically(1)
        shown = raw
        text.text = colorize(raw)
        if (atBottom) scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    /**
     * The Logs into Android's own log too: on a phone in a cloud lab, whose clipboard doesn't reach the tester's, its
     * log panel shows them, and copies. Logcat cuts entries at about 4 KB, so longer lines go in pieces, and drops
     * lines that come faster than a few hundred a second, so they go in bursts.
     */
    private fun toLogcat(text: String) = thread {
        text.lines().flatMap { it.chunked(LOGCAT_PIECE).ifEmpty { listOf("") } }.forEachIndexed { i, line ->
            if (i > 0 && i % LOGCAT_BURST == 0) Thread.sleep(LOGCAT_PAUSE_MS)
            Log.i(TAG, line)
        }
    }

    private class Excerpt(val log: (Paths) -> File, val last: Int, val first: Int = 0) {
        fun of(file: File): String = when {
            !file.exists() -> "(none yet)"
            file.length() == 0L -> "(empty)"
            else -> file.readLines().let { lines ->
                val between = lines.size - first - last
                if (between <= 0) lines else lines.take(first) + "… $between lines …" + lines.takeLast(last)
            }.joinToString("\n")
        }
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
        private const val LOGCAT_PIECE = 3000
        private const val LOGCAT_BURST = 100
        private const val LOGCAT_PAUSE_MS = 250L
        /** Factorio's first lines say what it runs on, and a log full of errors can push them out. */
        private val LOGS = listOf(Excerpt(Paths::gameLog, last = 340, first = 60), Excerpt(Paths::wmLog, 20),
                                  Excerpt(Paths::screenLog, 120), Excerpt(Paths::x11Log, 120),
                                  Excerpt(Paths::driverCheckLog, 40))
        private val TEXT = Color.rgb(0xD5, 0xD9, 0xE0)
        private val DIM = Color.rgb(0x7A, 0x78, 0x76)
        private val AMBER = Color.rgb(0xE6, 0xB4, 0x50)
        private val RED = Color.rgb(0xFF, 0x6B, 0x6B)
        private val TIMESTAMP = Regex("""^\s*\d+\.\d+ """)
        private val WARNING = Regex("[Ww]arning")
        private val ERROR = Regex("""\b(Error|error|ERROR|FATAL|Failed|failed|cannot|Couldn't)\b""")
    }
}
