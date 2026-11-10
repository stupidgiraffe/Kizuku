package io.github.stupidgiraffe.kizuku

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class PluginWebActivity : ComponentActivity() {
    private var web: WebView? = null
    private var plugin = ""
    private val commands = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(8))

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        plugin = intent.getStringExtra("plugin").orEmpty()
        if (!plugin.matches(Regex("[A-Za-z][A-Za-z0-9._-]{1,79}")) || CoreConnection.service == null) {
            finish()
            return
        }
        web = WebView(this).also { view ->
            view.settings.apply {
                javaScriptEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
            view.addJavascriptInterface(KsuBridge(), "ksu")
            view.addJavascriptInterface(AxeronBridge(), "Axeron")
            view.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    !request.isForMainFrame || request.method != "GET" || !local(request.url)

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    if (!local(request.url) || request.method != "GET") return unavailable(403, "Blocked")
                    val path = request.url.path?.removePrefix("/")?.ifEmpty { "index.html" } ?: "index.html"
                    val descriptor = runCatching { CoreConnection.service?.openRuntimeWebAsset(plugin, path) }.getOrNull()
                        ?: return unavailable(404, "Plugin file unavailable")
                    val mime = when (path.substringAfterLast('.').lowercase()) {
                        "html", "htm" -> "text/html"
                        "js", "mjs" -> "text/javascript"
                        "css" -> "text/css"
                        "svg" -> "image/svg+xml"
                        "png" -> "image/png"
                        "jpg", "jpeg" -> "image/jpeg"
                        "webp" -> "image/webp"
                        "woff2" -> "font/woff2"
                        else -> "application/octet-stream"
                    }
                    return WebResourceResponse(mime, "UTF-8", 200, "OK", headers,
                        ParcelFileDescriptor.AutoCloseInputStream(descriptor))
                }
            }
            setContentView(view)
            view.loadUrl("https://$HOST/index.html")
        }
    }

    override fun onDestroy() {
        commands.shutdownNow()
        web?.apply {
            stopLoading()
            removeJavascriptInterface("ksu")
            removeJavascriptInterface("Axeron")
            destroy()
        }
        web = null
        super.onDestroy()
    }

    private fun local(uri: Uri) = uri.scheme == "https" && uri.host == HOST && uri.port == -1 && uri.userInfo == null
    private fun unavailable(code: Int, reason: String) =
        WebResourceResponse("text/plain", "UTF-8", code, reason, headers, ByteArrayInputStream(reason.toByteArray()))

    private fun execute(command: String, options: String): JSONObject = try {
        val config = if (options.isBlank()) JSONObject() else JSONObject(options)
        val environment = config.optJSONObject("env")?.let { values ->
            values.keys().asSequence().joinToString(" ") { key ->
                require(key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "Invalid environment name" }
                val value = values.getString(key).replace("'", "'\\''")
                "export $key='$value';"
            }
        }.orEmpty()
        val host = checkNotNull(CoreConnection.service) { "Kizuku is disconnected." }
        val result = host.runtimeWebCommand(plugin, "$environment\n$command", config.optString("cwd").ifEmpty { null })
        JSONObject().put("errno", result.getInt("exit"))
            .put("stdout", result.getString("stdout").orEmpty()).put("stderr", result.getString("stderr").orEmpty())
    } catch (failure: Exception) {
        JSONObject().put("errno", 126).put("stdout", "").put("stderr", failure.message ?: "Command failed.")
    }

    private fun async(command: String, options: String, callback: String) {
        require(callback.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,119}"))) { "Invalid WebUI callback name" }
        fun deliver(result: JSONObject) {
            val args = "${result.getInt("errno")},${JSONObject.quote(result.getString("stdout"))},${JSONObject.quote(result.getString("stderr"))}"
            runOnUiThread { web?.evaluateJavascript("window[${JSONObject.quote(callback)}]?.($args)", null) }
        }
        try { commands.execute { deliver(execute(command, options)) } }
        catch (_: java.util.concurrent.RejectedExecutionException) {
            deliver(JSONObject().put("errno", 125).put("stdout", "").put("stderr", "Command queue is full."))
        }
    }

    private fun toast(message: String) = runOnUiThread { Toast.makeText(this, message.take(200), Toast.LENGTH_SHORT).show() }

    inner class KsuBridge {
        @JavascriptInterface fun exec(command: String): String {
            val result = execute(command, "")
            return listOf(result.getString("stdout"), result.getString("stderr")).filter(String::isNotEmpty).joinToString("\n")
        }
        @JavascriptInterface fun exec(command: String, callback: String) = async(command, "", callback)
        @JavascriptInterface fun exec(command: String, options: String, callback: String) = async(command, options, callback)
        @JavascriptInterface fun toast(message: String) = this@PluginWebActivity.toast(message)
        @JavascriptInterface fun exit() = runOnUiThread { finish() }
        @JavascriptInterface fun moduleInfo(): String = runCatching {
            val row = CoreConnection.service?.runtimePlugins()?.firstOrNull { it.getString("id") == plugin }
            JSONObject().apply {
                row?.keySet()?.forEach { key ->
                    put(key, if (key in setOf("enabled", "action", "webui")) row.getBoolean(key) else row.getString(key).orEmpty())
                }
            }.toString()
        }.getOrDefault("{}")
    }

    inner class AxeronBridge {
        @JavascriptInterface fun exec(command: String, options: String): String = execute(command, options).toString()
        @JavascriptInterface fun exec(command: String, options: String, callback: String) = async(command, options, callback)
        @JavascriptInterface fun toast(message: String) = this@PluginWebActivity.toast(message)
    }

    companion object {
        private const val HOST = "mui.kernelsu.org"
        private val headers = mapOf(
            "Content-Security-Policy" to "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'none'; frame-src 'none'; form-action 'none'; object-src 'none'; worker-src 'none'; base-uri 'none'",
            "Cache-Control" to "no-store",
            "X-Content-Type-Options" to "nosniff",
        )
    }
}
