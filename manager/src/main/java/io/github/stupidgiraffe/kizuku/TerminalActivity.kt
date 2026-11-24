package io.github.stupidgiraffe.kizuku

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import frb.axeron.server.IAxshProcess
import io.github.stupidgiraffe.kizuku.api.IKizukuService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class RuntimeTerminalModel : ViewModel() {
    var session by mutableStateOf<TerminalSession?>(null)
        private set
    var notice by mutableStateOf<String?>(null)
    var exit by mutableStateOf<Int?>(null)
    private val lifetime = Binder()
    private var starting = false

    fun start(callbacks: TerminalCallbacks) {
        if (starting || session != null) return
        starting = true
        viewModelScope.launch {
            try {
                val transport = withContext(Dispatchers.IO) {
                    val host = checkNotNull(CoreConnection.service) { "Start Kizuku first." }
                    open(host)
                }
                session = TerminalSession(transport, callbacks)
            } catch (failure: Exception) {
                notice = failure.message ?: "Cannot open a terminal."
            } finally { starting = false }
        }
    }

    private fun open(host: IKizukuService): TerminalSession.Transport {
        val input = ParcelFileDescriptor.createPipe()
        val output = ParcelFileDescriptor.createPipe()
        val status = ParcelFileDescriptor.createPipe()
        try {
            val remote = IAxshProcess.Stub.asInterface(host.openRuntimeTerminal(
                arrayOf("/system/bin/sh", "-i"), arrayOf("TERM=xterm-256color", "COLORTERM=truecolor"),
                null, input[0], output[1], status[1], lifetime))
            input[0].close()
            output[1].close()
            status[1].close()
            val source = ParcelFileDescriptor.AutoCloseInputStream(output[0])
            val target = ParcelFileDescriptor.AutoCloseOutputStream(input[1])
            val report = DataInputStream(ParcelFileDescriptor.AutoCloseInputStream(status[0]))
            return object : TerminalSession.Transport {
                override fun input() = source
                override fun output() = target
                override fun resize(size: Long) = remote.setWindowSize(size)
                override fun destroy() = remote.destroy()
                override fun awaitExit(): Int {
                    return try {
                        val bytes = ByteArray(4)
                        report.readFully(bytes)
                        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int
                    } catch (_: java.io.EOFException) {
                        remote.waitFor()
                    } finally { report.close() }
                }
                override fun close() {
                    runCatching { source.close() }
                    runCatching { target.close() }
                    runCatching { report.close() }
                }
            }
        } catch (failure: Exception) {
            for (fd in input + output + status) runCatching { fd.close() }
            throw failure
        }
    }

    override fun onCleared() { session?.finishIfRunning() }
}

class TerminalActivity : ComponentActivity() {
    private val model by viewModels<RuntimeTerminalModel>()
    private var view: TerminalView? = null
    private val callbacks = object : TerminalCallbacks() {
        override fun onTextChanged(session: TerminalSession) { view?.onScreenUpdated() }
        override fun onSessionFinished(session: TerminalSession) {
            model.exit = session.exitStatus
            view?.onScreenUpdated()
        }
        override fun onColorsChanged(session: TerminalSession) { view?.invalidate() }
        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Terminal", text))
        }
        override fun onPasteTextFromClipboard(session: TerminalSession?) { paste() }
        override fun onSingleTapUp(event: MotionEvent) { keyboard() }
        override fun logError(tag: String, message: String) { model.notice = message }
        override fun logWarn(tag: String, message: String) { model.notice = message }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model.session?.updateTerminalSessionClient(callbacks)
        model.start(callbacks)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { TerminalContent() } }
    }

    override fun onDestroy() { view = null; super.onDestroy() }

    private fun keyboard() {
        view?.let {
            it.requestFocus()
            getSystemService(InputMethodManager::class.java).showSoftInput(it, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun paste() {
        val text = getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: return
        if (text.length > 65536) { model.notice = "Paste up to 64 KiB at a time."; return }
        model.session?.emulator?.paste(text)
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun TerminalContent() {
        val context = LocalContext.current
        val prefs = remember { context.getSharedPreferences("runtime_commands", Context.MODE_PRIVATE) }
        var snippets by remember {
            val json = runCatching { JSONArray(prefs.getString("commands", "[]")) }.getOrDefault(JSONArray())
            mutableStateOf(List(json.length()) { json.optString(it) })
        }
        var snippet by remember { mutableStateOf("") }
        var showSnippets by remember { mutableStateOf(false) }
        var transcript by remember { mutableStateOf<String?>(null) }
        var search by remember { mutableStateOf("") }
        val session = model.session
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { finish() }) { Text("Close") }
                    Text(if (model.exit == null) "Terminal" else "Exit ${model.exit}", Modifier.padding(top = 13.dp))
                    TextButton(enabled = session?.isRunning == true, onClick = { session?.finishIfRunning() }) { Text("Terminate") }
                }
                model.notice?.let { Text(it, Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.error) }
                if (session == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.weight(1f))
                } else AndroidView(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    factory = { host ->
                        TerminalView(host, null).apply {
                            setTerminalViewClient(callbacks)
                            setTextSize((14 * resources.displayMetrics.scaledDensity).toInt())
                            setTypeface(Typeface.MONOSPACE)
                            setBackgroundColor(android.graphics.Color.BLACK)
                            attachSession(session)
                            view = this
                        }
                    },
                    update = { terminal -> view = terminal },
                )
                FlowRow(Modifier.fillMaxWidth().padding(horizontal = 5.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    for ((label, sequence) in listOf(
                        "Esc" to "\u001b", "Tab" to "\t", "Ctrl-C" to "\u0003", "Ctrl-D" to "\u0004",
                        "↑" to "\u001b[A", "↓" to "\u001b[B", "←" to "\u001b[D", "→" to "\u001b[C",
                    )) TextButton(enabled = session?.isRunning == true, onClick = { session?.write(sequence) },
                        contentPadding = PaddingValues(horizontal = 10.dp)) { Text(label) }
                }
                FlowRow(Modifier.padding(horizontal = 5.dp)) {
                    TextButton(onClick = ::keyboard) { Text("Keyboard") }
                    TextButton(enabled = session?.isRunning == true, onClick = ::paste) { Text("Paste") }
                    TextButton(onClick = {
                        transcript = session?.emulator?.screen?.transcriptText.orEmpty()
                        search = ""
                    }) { Text("Search / copy") }
                    TextButton(onClick = { showSnippets = true }) { Text("Snippets") }
                }
            }
        }
        transcript?.let { output ->
            AlertDialog(onDismissRequest = { transcript = null }, title = { Text("Terminal output") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(search, { search = it }, label = { Text("Search output") }, singleLine = true)
                        val lines = remember(output, search) {
                            output.lines().filter { search.isBlank() || it.contains(search, ignoreCase = true) }
                        }
                        SelectionContainer {
                            LazyColumn(Modifier.heightIn(max = 350.dp)) {
                                items(lines) { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Terminal", output))
                }) { Text("Copy all") } },
                dismissButton = { TextButton(onClick = { transcript = null }) { Text("Close") } })
        }
        if (showSnippets) {
            AlertDialog(onDismissRequest = { showSnippets = false }, title = { Text("Saved commands") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(snippet, { snippet = it.take(32768) }, label = { Text("Command or script") })
                        TextButton(enabled = snippet.isNotBlank(), onClick = {
                            val next = (listOf(snippet) + snippets).distinct().take(30)
                            if (prefs.edit().putString("commands", JSONArray(next).toString()).commit()) {
                                snippets = next
                                snippet = ""
                            } else model.notice = "Cannot save this snippet."
                        }) { Text("Save snippet") }
                        LazyColumn(Modifier.heightIn(max = 250.dp)) {
                            items(snippets) { command ->
                                Row {
                                    TextButton(enabled = session?.isRunning == true,
                                        modifier = Modifier.weight(1f), onClick = {
                                            session?.write(command + "\n")
                                            showSnippets = false
                                        }) { Text(command.lineSequence().first().take(80), maxLines = 2) }
                                    TextButton(onClick = {
                                        val next = snippets - command
                                        if (prefs.edit().putString("commands", JSONArray(next).toString()).commit()) snippets = next
                                        else model.notice = "Cannot remove this snippet."
                                    }) { Text("Remove") }
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showSnippets = false }) { Text("Close") } })
        }
    }
}
