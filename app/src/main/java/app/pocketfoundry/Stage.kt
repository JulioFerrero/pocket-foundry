package app.pocketfoundry

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File
import java.io.InputStream

/**
 * The launcher's moving background: assets/stage (the blueprint, the 3D build, the working factory)
 * in a WebView. The page reaches nothing but its own assets and, at /game/, the PNGs under the
 * player's imported data/base/graphics: no network.
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class Stage(context: Context, private val paths: Paths) : WebView(context) {
    private var ready = false
    private var scene: String? = null
    private var percent = -1

    init {
        setBackgroundColor(0xFF091726.toInt())
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        setWebContentsDebuggingEnabled(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) = serve(request.url)
            // Ended on purpose by release(); without this the app would go with it.
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail) = true

            override fun onPageFinished(view: WebView, url: String) {
                ready = true
                scene?.let { evaluateJavascript(it, null) }
                if (percent >= 0) evaluateJavascript("stage.progress($percent)", null)
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                Log.i(TAG, "${message.message()} (${message.sourceId().substringAfterLast('/')}:${message.lineNumber()})")
                return true
            }
        }
        loadUrl("https://$HOST/stage/index.html")
    }

    /** Crossfades to "blueprint", "build" or "factory", dimmed and desaturated as asked. */
    fun show(name: String, dim: Float = 1f, saturate: Float = 1f) {
        val js = "stage.show('$name',{dim:$dim,saturate:$saturate})"
        if (js == scene) return
        scene = js
        if (ready) evaluateJavascript(js, null)
    }

    /** The import's progress, 0-100: the 3D build gets that far. */
    fun progress(value: Int) {
        if (value == percent) return
        percent = value
        if (ready) evaluateJavascript("stage.progress($value)", null)
    }

    /**
     * Gone for good, renderer process included: Chromium keeps an idle one (~160 MB) after its
     * last WebView is destroyed, and the game needs that memory more.
     */
    fun release() {
        webViewRenderProcess?.terminate()
        destroy()
    }

    override fun onPause() {
        if (ready) evaluateJavascript("stage.sleep()", null)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (ready) evaluateJavascript("stage.wake()", null)
    }

    private fun serve(url: Uri): WebResourceResponse {
        val path = url.path.orEmpty()
        if (url.host != HOST) return missing()
        return when {
            path.startsWith("/stage/") -> asset("stage/" + path.removePrefix("/stage/"))
            path.startsWith("/game/") -> sprite(path.removePrefix("/game/"))
            else -> missing()
        }
    }

    private fun asset(name: String) =
        runCatching { found(name, context.assets.open(name)) }.getOrElse { missing() }

    private fun sprite(name: String): WebResourceResponse {
        val graphics = File(paths.game, "data/base/graphics").canonicalFile
        val file = File(graphics, name).canonicalFile
        if (!file.path.startsWith(graphics.path + "/") || !file.name.endsWith(".png") || !file.isFile) return missing()
        return found(file.name, file.inputStream())
    }

    private fun found(name: String, data: InputStream): WebResourceResponse {
        val type = when (name.substringAfterLast('.')) {
            "html" -> "text/html"
            "js" -> "text/javascript"
            "json" -> "application/json"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            else -> "application/octet-stream"
        }
        return WebResourceResponse(type, if (type.startsWith("text") || type.endsWith("json") || type.endsWith("xml")) "utf-8" else null, data)
    }

    private fun missing() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), "".byteInputStream())

    private companion object {
        const val TAG = "PocketFoundryStage"
        const val HOST = "pocketfoundry.stage"
    }
}
