package app.pocketfoundry

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.pocketfoundry.ui.Arrow
import app.pocketfoundry.ui.FButton
import app.pocketfoundry.ui.Factorio
import app.pocketfoundry.ui.Tone
import app.pocketfoundry.ui.dpi
import app.pocketfoundry.ui.heading
import app.pocketfoundry.ui.immersive
import app.pocketfoundry.ui.stack
import java.io.File

/** The game's output, the WM's and the X server's, newest last, refreshed while open. */
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
        panel.addView(FButton(this, "Back", Tone.RED, Arrow.LEFT).apply { setOnClickListener { finish() } },
                      LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dpi(44)).apply { gravity = Gravity.START })
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

    private fun load() {
        val logs = Paths(this).logs
        val raw = listOf("game.log" to 400, "wm.log" to 20, "x11.log" to 120).joinToString("\n") { (name, lines) ->
            "── $name ──\n" + tail(File(logs, name), lines)
        }
        if (raw == shown) return
        // Follow new output only when the reader is already at the bottom.
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
            // Wrapped lines start under the message, not under the timestamp.
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
        private val TEXT = Color.rgb(0xD5, 0xD9, 0xE0)
        private val DIM = Color.rgb(0x7A, 0x78, 0x76)
        private val HEADER = Factorio.HEADING
        private val AMBER = Color.rgb(0xE6, 0xB4, 0x50)
        private val RED = Color.rgb(0xFF, 0x6B, 0x6B)
        private val TIMESTAMP = Regex("""^\s*\d+\.\d+ """)
        private val ERROR = Regex("""\b(Error|error|ERROR|FATAL|Failed|failed|cannot|Couldn't)\b""")
    }
}
