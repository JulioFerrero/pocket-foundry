package app.pocketfoundry.ui.screens.launcher

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.StyleSpan
import android.util.Log
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
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
import app.pocketfoundry.core.game.GameEnding
import app.pocketfoundry.core.game.Session
import app.pocketfoundry.core.logs.Reports
import app.pocketfoundry.core.logs.ScreenLog
import app.pocketfoundry.core.runtime.Runtime
import app.pocketfoundry.core.system.REPOSITORY
import app.pocketfoundry.core.system.appVersion
import app.pocketfoundry.core.system.mainHandler
import app.pocketfoundry.ui.kit.link
import app.pocketfoundry.ui.kit.scaleUi
import app.pocketfoundry.ui.kit.DialogHost
import app.pocketfoundry.ui.kit.Tone
import app.pocketfoundry.ui.kit.button
import app.pocketfoundry.ui.kit.label
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
import app.pocketfoundry.ui.kit.padForCutout
import app.pocketfoundry.ui.kit.edgeToEdge
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
        val report = FButton(this, "Report", size = 15f).apply { minWidth = dpi(110) }
        report.setOnClickListener { report() }
        val panel = panel(this, "Logs") {
            add(stack(context, Frame.DEEP) { add(scroll, MATCH, MATCH) }, height = 0, weight = 1f)
            add(buttonBar(context, "Back", ::finish, report, copy))
        }
        // Drawn under the camera cutout as the launcher is, the panel kept clear of it: else the strip beside it
        // shows the theme's own background.
        edgeToEdge()
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Factorio.BODY)
            padForCutout()
            add(panel, start = 24, top = 24, end = 24, bottom = 24)
        })
    }

    override fun onResume() {
        super.onResume()
        immersive()
        mainHandler.post(refresh)
        // Home's "Report it", once: back from the browser, the Logs stay.
        if (intent.getBooleanExtra(REPORT, false)) {
            intent.removeExtra(REPORT)
            mainHandler.post(::report)
        }
    }

    /** What happened in words, for the report's title and its "What happened". */
    private val lastGame: String?
        get() = Session.lastGame?.let { ending ->
            ending.closedBy ?: ending.failure?.let {
                GameEnding.sentence(Drivers.find(this, ending.driver)?.name ?: ending.driver, it, ending.stage)
            }
        }

    private val host by lazy { DialogHost(this) {} }

    /**
     * What happened, in the player's words, and the Logs as a file, sent to Pocket Foundry's Acrarium (Reports) when
     * they tap Send, or the GitHub form instead. Nothing goes anywhere before that tap.
     */
    private fun report() {
        load()
        val what = field("What happened, in a few words", lines = 3).apply { setText(lastGame.orEmpty()) }
        val contact = field("Email or GitHub name, if you'd like an answer (optional)", lines = 1)
        val status = label(this, "", 13f, Factorio.MUTED)
        val github = button(this, "GitHub") {
            host.close()
            reportOnGitHub()
        }
        lateinit var send: FButton
        send = button(this, "Send", Tone.GREEN) {
            send.isEnabled = false
            status.text = "Sending…"
            val played = ScreenLog.driver ?: Drivers.chosen(this)
            val driver = Drivers.find(this, played)?.name ?: played
            val gpu = Gpu.name ?: "GPU unknown"
            val ending = lastGame
            val head = phone
            val comment = what.text.toString().trim()
            val reply = contact.text.toString().trim()
            thread {
                val sent = runCatching { Reports.send(this, driver, gpu, ending, comment, reply, head + whole()) }
                mainHandler.post {
                    sent.onSuccess { id ->
                        host.close()
                        host.show("Report sent", 420, "Close") {
                            add(label(context, "It's number ${id.take(8)}. If you also open an issue on GitHub, " +
                                               "mention it there.", 15f))
                        }
                    }.onFailure {
                        status.text = "Couldn't send it: ${it.message}. Check the connection and try again."
                        send.isEnabled = true
                    }
                }
            }
        }
        host.show("Send a report", 480, "Cancel", github, send) {
            add(label(context, "Sends these logs, whole, and what you write here to Pocket Foundry's developer. " +
                               "Nothing is sent until you tap Send.", 14f))
            add(link(context, "Privacy", Reports.PRIVACY))
            add(stack(context, Frame.DEEP) { add(what) })
            add(stack(context, Frame.DEEP) { add(contact) })
            add(status)
        }
    }

    private fun field(hint: String, lines: Int) = EditText(this).apply {
        this.hint = hint
        textSize = 14f
        setTextColor(Factorio.TEXT)
        setHintTextColor(0xFF8A8580.toInt())
        background = null
        pad(horizontal = 8, vertical = 6)
        if (lines > 1) {
            minLines = lines
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        } else {
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
    }

    /**
     * The Logs onto the clipboard and a new bug report on GitHub, its phone and title filled in: the player sees the
     * form and decides whether to post it. The app sends nothing itself.
     */
    private fun reportOnGitHub() {
        load()
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Pocket Foundry logs", shown))
        val phone = "${Build.MANUFACTURER} ${Build.MODEL}"
        val what = lastGame
        val url = Uri.parse("https://github.com/$REPOSITORY/issues/new").buildUpon()
            .appendQueryParameter("template", "1-bug.yml")
            .appendQueryParameter("title", listOfNotNull("$phone, ${Gpu.name ?: "GPU unknown"}", what).joinToString(": "))
            .appendQueryParameter("phone", phone)
            .apply { if (what != null) appendQueryParameter("what", what) }
            .build()
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url))
            Toast.makeText(this, "The Logs are copied: paste them in the report's Logs box.", Toast.LENGTH_LONG).show()
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No browser to open the report in. The Logs are copied.", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Android's own, still the way on Android 10")
    override fun onBackPressed() {
        if (host.isOpen) host.close() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(refresh)
    }

    /** What a report needs besides the logs. */
    private val phone by lazy {
        // The driver the logs are about, with its version as the driver screen gives it: an imported one's is its
        // file name, as PanVK builds all report the same Mesa version.
        val played = ScreenLog.driver ?: Drivers.chosen(this)
        val driver = Drivers.find(this, played)?.let { "${it.name} (${Drivers.version(this, it)})" }
        val facts = listOfNotNull("${Build.MANUFACTURER} ${Build.MODEL}", "Android ${Build.VERSION.RELEASE}",
                                  Gpu.name ?: "GPU unknown", driver, "Pocket Foundry ${appVersion()}")
        val pads = gamepads(this).joinToString(", ") { it.name + if (it.isExternal) "" else " (built in)" }
        val options = DriverRecords.options(this, played)
        // Every driver checked here, the one the logs are about first: the setup may have passed others over.
        val checks = Drivers.all(this).sortedBy { it.id != played }.mapNotNull { driver ->
            DriverCheck.summary(this, driver.id)?.let { "Driver check, ${driver.name}: $it\n" }
        }
        val speed = DriverCheck.speed(this, played)
        val formats = Gpu.textureFormats.joinToString(", ").ifEmpty { "none" }
        val settings = Settings(this)
        fun onOff(on: Boolean) = if (on) "on" else "off"
        val chosen = listOf("${settings.frameRate} FPS", "sprites ${settings.spriteQuality}",
                            "graphics " + if (settings.lightGraphics) "light" else "full",
                            "FPS counter ${onOff(settings.fpsCounter)}",
                            "pause in background ${onOff(settings.pauseInBackground)}",
                            "detailed logs ${onOff(settings.detailedLogs)}",
                            "fast start ${onOff(settings.fastStart)}",
                            "fast CPU ${onOff(settings.fastCpu)}")
        "── phone ──\n" + facts.joinToString(" · ") + "\n" +
            (lastGame?.let { "Last game: $it\n" } ?: "") +
            "Settings: ${chosen.joinToString(" · ")}\n" +
            (if (pads.isNotEmpty()) "Controllers: $pads\n" else "") +
            (if (options.isNotEmpty()) "Driver options: $options\n" else "") +
            "GPU's compressed textures: $formats\n" +
            (Runtime.misplaced(this)?.let { "Runtime: $it\n" } ?: "") +
            checks.joinToString("") +
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

    /**
     * Every log whole, off the main thread: what a report sends. The screen shows each one's start and end, as it
     * reads them again every 2 s and the clipboard and a GitHub issue hold far less; but a driver's first error can
     * be anywhere in between (28,000 lines on a Pixel 10, #65).
     */
    private fun whole(): String = LOGS.joinToString("\n") { excerpt ->
        val file = excerpt.log(paths)
        "── ${file.name} ──\n" + Excerpt.whole(file)
    }

    private class Excerpt(val log: (Paths) -> File, val last: Int, val first: Int = 0) {
        fun of(file: File): String = empty(file) ?: file.readLines().let { lines ->
            val between = lines.size - first - last
            if (between <= 0) lines else lines.take(first) + "… $between lines: Report sends them all …" + lines.takeLast(last)
        }.joinToString("\n")

        companion object {
            fun whole(file: File): String = empty(file) ?: file.readText().trimEnd()

            fun empty(file: File): String? = when {
                !file.exists() -> "(none yet)"
                file.length() == 0L -> "(empty)"
                else -> null
            }
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
                LOOKUP.matches(line) -> span(ForegroundColorSpan(DIM))
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
        /** From Home after a failed game: report it right away. */
        const val REPORT = "report"
        private const val LOGCAT_PIECE = 3000
        private const val LOGCAT_BURST = 100
        private const val LOGCAT_PAUSE_MS = 250L
        /** Factorio's first lines say what it runs on, and a log full of errors can push them out. */
        private val LOGS = listOf(Excerpt(Paths::gameLog, last = 340, first = 60), Excerpt(Paths::wmLog, 20),
                                  Excerpt(Paths::screenLog, 120), Excerpt(Paths::x11Log, 120),
                                  Excerpt(Paths::driverCheckLog, 40))
        /**
         * libhybris's linker looking for a library in one of Android's namespaces before the one that has it: kept
         * for a load that really fails, dimmed so it doesn't read as one.
         */
        private val LOOKUP = Regex("""library "[^"]+" not found""")
        private val TEXT = Color.rgb(0xD5, 0xD9, 0xE0)
        private val DIM = Color.rgb(0x7A, 0x78, 0x76)
        private val AMBER = Color.rgb(0xE6, 0xB4, 0x50)
        private val RED = Color.rgb(0xFF, 0x6B, 0x6B)
        private val TIMESTAMP = Regex("""^\s*\d+\.\d+ """)
        private val WARNING = Regex("[Ww]arning")
        private val ERROR = Regex("""\b(Error|error|ERROR|FATAL|Failed|failed|cannot|Couldn't)\b""")
    }
}
