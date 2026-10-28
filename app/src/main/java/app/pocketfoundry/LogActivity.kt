package app.pocketfoundry

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.input.InputManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.pocketfoundry.controller.Gamepads
import app.pocketfoundry.ui.Arrow
import app.pocketfoundry.ui.FButton
import app.pocketfoundry.ui.Factorio
import app.pocketfoundry.ui.Tone
import app.pocketfoundry.ui.dpi
import app.pocketfoundry.ui.heading
import app.pocketfoundry.ui.immersive
import app.pocketfoundry.ui.stack
import java.io.File

class LogActivity : Activity() {
    private lateinit var text: TextView
    private lateinit var scroll: ScrollView
    private val handler = Handler(Looper.getMainLooper())
    private var shown = ""
    private val refresh = object : Runnable {
        override fun run() {
            load()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (12 * resources.displayMetrics.density).toInt()
        text = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(TEXT)
            setLineSpacing(0f, 1.15f)
            setTextIsSelectable(true)
            setPadding(pad, pad, pad, pad)
        }
        scroll = ScrollView(this).apply { addView(text) }
        val panel = stack(this, Factorio.PANEL, sunken = false, padding = 8, gap = 10)
        panel.addView(heading(this, "Logs"))
        val inset = stack(this, Factorio.INSET_DARK, sunken = true, padding = 0, gap = 0)
        inset.addView(scroll, LinearLayout.LayoutParams(MATCH, MATCH))
        panel.addView(inset, LinearLayout.LayoutParams(MATCH, 0, 1f))
        val copy = FButton(this, "Copy", size = 15f).apply { minWidth = dpi(110) }
        val copied = Runnable { copy.text = "Copy" }
        copy.setOnClickListener {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Pocket Foundry logs", shown))
            copy.text = "Copied"
            handler.removeCallbacks(copied)
            handler.postDelayed(copied, 2000)
        }
        panel.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(FButton(context, "Back", Tone.RED, Arrow.LEFT).apply { setOnClickListener { finish() } },
                    LinearLayout.LayoutParams(WRAP, dpi(44)))
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(copy, LinearLayout.LayoutParams(WRAP, dpi(44)))
        }, LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Factorio.BODY)
            addView(panel, FrameLayout.LayoutParams(MATCH, MATCH).apply { setMargins(dpi(24), dpi(24), dpi(24), dpi(24)) })
        })
    }

    override fun onResume() {
        super.onResume()
        immersive()
        handler.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresh)
    }

    /** What a report needs besides the logs. */
    private val phone by lazy {
        val driver = Drivers.all(this).firstOrNull { it.id == Drivers.chosen(this) }?.name
        val input = getSystemService(InputManager::class.java)
        val pads = input.inputDeviceIds.asSequence().mapNotNull { input.getInputDevice(it) }.filter { Gamepads.isGamepad(it) }
            .joinToString(", ") { it.name + if (it.isExternal) "" else " (built in)" }
        "── phone ──\n" + listOfNotNull("${Build.MANUFACTURER} ${Build.MODEL}", "Android ${Build.VERSION.RELEASE}",
            Gpu.name ?: "GPU unknown", driver, "Pocket Foundry ${packageManager.getPackageInfo(packageName, 0).versionName}")
            .joinToString(" · ") + "\n" + (if (pads.isNotEmpty()) "Controllers: $pads\n" else "")
    }

    private fun load() {
        val logs = Paths(this).logs
        val raw = phone + listOf("game.log" to 400, "wm.log" to 20, "screen.log" to 20, "x11.log" to 120).joinToString("\n") { (name, lines) ->
            "── $name ──\n" + tail(File(logs, name), lines)
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
            out.append(line).append('\n')
            fun span(color: Int, from: Int = start, to: Int = start + line.length) =
                out.setSpan(ForegroundColorSpan(color), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            when {
                line.startsWith("── ") -> {
                    span(HEADER)
                    out.setSpan(StyleSpan(Typeface.BOLD), start, start + line.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                ERROR.containsMatchIn(line) -> span(RED)
                line.contains("Warning") || line.contains("warning") -> span(AMBER)
                else -> TIMESTAMP.find(line)?.let { span(DIM, start, start + it.range.last + 1) }
            }
            TIMESTAMP.find(line)?.let {
                val indent = text.paint.measureText(it.value).toInt()
                out.setSpan(LeadingMarginSpan.Standard(0, indent), start, start + line.length + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return out
    }

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private val TEXT = Color.rgb(0xD5, 0xD9, 0xE0)
        private val DIM = Color.rgb(0x7A, 0x78, 0x76)
        private val HEADER = Factorio.HEADING
        private val AMBER = Color.rgb(0xE6, 0xB4, 0x50)
        private val RED = Color.rgb(0xFF, 0x6B, 0x6B)
        private val TIMESTAMP = Regex("""^\s*\d+\.\d+ """)
        private val ERROR = Regex("""\b(Error|error|ERROR|FATAL|Failed|failed|cannot|Couldn't)\b""")
    }
}
