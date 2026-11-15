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
import androidx.lifecycle.lifecycleScope
import io.github.stupidgiraffe.kizuku.api.IKizukuService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class PluginWebActivity : ComponentActivity() {
    private var web: WebView? = null
    private var plugin = ""
    private lateinit var host: IKizukuService
    private lateinit var info: Bundle
    private lateinit var session: String
    private var origin = HOST
    private val commands = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(8))

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        plugin = intent.getStringExtra("plugin").orEmpty()
        val connection = CoreConnection.service
        if (!plugin.matches(Regex("[A-Za-z][A-Za-z0-9._-]{1,79}")) || connection == null) {
            finish()
            return
        }
        host = connection
        val shell = intent.getBooleanExtra("shell", false)
        lifecycleScope.launch {
            try {
                info = withContext(Dispatchers.IO) {
                    host.controlRuntimePlugin(plugin, if (shell) "web-shell" else "web-read")
                }
                session = checkNotNull(info.getString("web_session"))
                configureWebView(shell)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                toast(failure.message ?: "Cannot open module WebUI.")
                finish()
            }
        }
        lifecycleScope.launch {
            CoreConnection.revision.collect { if (CoreConnection.service != host) finish() }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(shell: Boolean) {
        val shevery = info.getString("format") == "shevery"
        if (shevery) {
            val name = "${info.getInt("hostUid")}:$plugin".toByteArray()
            origin = "${UUID.nameUUIDFromBytes(name)}.kizuku.invalid"
        }
        web = WebView(this).also { view ->
            view.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = shevery
                allowFileAccess = false
                allowContentAccess = false
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
            if (shevery) view.addJavascriptInterface(SheveryBridge(shell), "Shizuku")
            else if (shell) {
                view.addJavascriptInterface(KsuBridge(), "ksu")
                view.addJavascriptInterface(AxeronBridge(), "Axeron")
            }
            view.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    !request.isForMainFrame || request.method != "GET" || !local(request.url)

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    if (!local(request.url) || request.method != "GET") return unavailable(403, "Blocked")
                    val path = request.url.path?.removePrefix("/")?.ifEmpty { "index.html" } ?: "index.html"
                    val descriptor = runCatching { host.openRuntimeWebSessionAsset(plugin, session, path) }.getOrNull()
                        ?: return unavailable(404, "Plugin file unavailable")
                    val mime = when (path.substringAfterLast('.').lowercase()) {
                        "html", "htm" -> "text/html"
                        "js", "mjs" -> "text/javascript"
                        "css" -> "text/css"
                        "svg" -> "image/svg+xml"
                        "png" -> "image/png"
                        "jpg", "jpeg" -> "image/jpeg"
                        "webp" -> "image/webp"
                        "gif" -> "image/gif"
                        "woff" -> "font/woff"
                        "woff2" -> "font/woff2"
                        "json" -> "application/json"
                        else -> "application/octet-stream"
                    }
                    return WebResourceResponse(mime, "UTF-8", 200, "OK", headers,
                        ParcelFileDescriptor.AutoCloseInputStream(descriptor))
                }
            }
            setContentView(view)
            view.loadUrl("https://$origin/index.html")
        }
    }

    override fun onDestroy() {
        commands.shutdownNow()
        web?.apply {
            stopLoading()
            removeJavascriptInterface("ksu")
            removeJavascriptInterface("Axeron")
            removeJavascriptInterface("Shizuku")
            destroy()
        }
        web = null
        super.onDestroy()
    }

    private fun local(uri: Uri) = uri.scheme == "https" && uri.host == origin && uri.port == -1 && uri.userInfo == null
    private fun unavailable(code: Int, reason: String) =
        WebResourceResponse("text/plain", "UTF-8", code, reason, headers, ByteArrayInputStream(reason.toByteArray()))

    private fun execute(command: String, options: String, shevery: Boolean = false): JSONObject {
        val result = try {
            check(CoreConnection.service == host && !isFinishing && !isDestroyed) { "This WebUI session ended." }
            host.runtimeWebSessionCommand(plugin, session, command, options)
        } catch (failure: Exception) {
            Bundle().apply {
                putInt("exit", if (shevery) -1 else 126)
                putString("stderr", failure.message ?: "Command failed.")
            }
        }
        val code = result.getInt("exit")
        val timedOut = result.getBoolean("timed_out")
        return JSONObject().apply {
            put("stdout", result.getString("stdout").orEmpty())
            put("stderr", result.getString("stderr").orEmpty())
            if (shevery) {
                put("ok", code == 0 && !timedOut)
                put("exitCode", code)
                put("timedOut", timedOut)
                put("truncated", result.getBoolean("truncated"))
            } else put("errno", code)
        }
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

    inner class SheveryBridge(private val shellAllowed: Boolean) {
        @JavascriptInterface fun getModuleInfo(): String = JSONObject().apply {
            put("ok", true)
            for (key in listOf("id", "name", "version", "author", "moduleDir", "webRoot"))
                put(key, info.getString(key).orEmpty())
            put("versionCode", info.getLong("versionCode"))
            put("enabled", true)
            put("accessMode", "custom")
            put("trusted", false)
            put("background", info.getBoolean("background"))
            put("permissions", JSONObject().apply {
                put("action", info.getBoolean("action"))
                put("service", info.getBoolean("service"))
                put("webBridge", shellAllowed)
                put("webNetwork", false)
                put("webDownload", false)
                put("declaresShellBridge", info.getBoolean("declares_shell"))
            })
        }.toString()
        @JavascriptInterface fun exec(command: String): String = execWithOptions(command, "")
        @JavascriptInterface fun run(command: String): String = exec(command)
        @JavascriptInterface fun shell(command: String): String = exec(command)
        @JavascriptInterface fun execute(command: String): String = exec(command)
        @JavascriptInterface fun execWithOptions(command: String, options: String): String =
            this@PluginWebActivity.execute(command, options, shevery = true).toString()
        @JavascriptInterface fun toast(message: String) = this@PluginWebActivity.toast(message)
    }

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
            "Content-Security-Policy" to "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; frame-src 'none'; form-action 'none'; object-src 'none'; worker-src 'none'; base-uri 'none'",
            "Cache-Control" to "no-store",
            "X-Content-Type-Options" to "nosniff",
        )
    }
}
