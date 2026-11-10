package io.github.stupidgiraffe.kizuku

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.stupidgiraffe.kizuku.api.IKizukuService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.ByteArrayOutputStream

private fun runtime(): IKizukuService = checkNotNull(CoreConnection.service) { "Start Kizuku first." }

@Composable
internal fun RuntimeScreen(state: CoreState) {
    var page by rememberSaveable { mutableStateOf("Shell") }
    val ready = state.status?.getBoolean("axeron_runtime") == true
    Text(when {
        ready -> "Running as ${state.status?.getString("transport")}. Commands and plugins use this privilege."
        state.safeMode -> "Safe Mode has suspended runtime commands and plugins."
        state.status == null -> "Start Kizuku to run commands or manage plugins."
        else -> state.status.getString("runtime_error") ?: "The host cannot start the runtime."
    })
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (choice in listOf("Shell", "Plugins")) {
            FilterChip(selected = choice == page, onClick = { page = choice }, label = { Text(choice) })
        }
    }
    if (page == "Shell") ShellPanel(ready) else PluginsPanel(ready)
}

@Composable
private fun ShellPanel(ready: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("runtime_commands", Context.MODE_PRIVATE) }
    var command by rememberSaveable { mutableStateOf("") }
    var saved by remember {
        val json = JSONArray(prefs.getString("commands", "[]"))
        mutableStateOf(List(json.length()) { json.getString(it) })
    }
    var output by remember { mutableStateOf<Bundle?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val script = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                command = withContext(Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                        val buffer = ByteArray(4096)
                        val bytes = ByteArrayOutputStream()
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            require(bytes.size() + read <= 32768) { "The script exceeds 32 KiB." }
                            bytes.write(buffer, 0, read)
                        }
                        bytes.toString("UTF-8")
                    }
                }
                message = "Script loaded. Review it before running."
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                message = failure.message ?: "Cannot read script."
            }
        }
    }
    fun save(values: List<String>) {
        if (prefs.edit().putString("commands", JSONArray(values).toString()).commit()) saved = values
        else message = "Cannot save commands."
    }
    OutlinedTextField(value = command, onValueChange = { command = it.take(32768) }, label = { Text("Command or script") },
        minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = ready && !busy && command.isNotBlank(), onClick = {
            val submitted = command
            busy = true; message = null
            scope.launch {
                try { output = withContext(Dispatchers.IO) { runtime().runtimeCommand(submitted) } }
                catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    message = failure.message ?: "Command failed."
                } finally { busy = false }
            }
        }) { Text("Run command") }
        OutlinedButton(onClick = { script.launch(arrayOf("text/*", "application/octet-stream")) }) { Text("Load script") }
    }
    TextButton(enabled = command.isNotBlank() && command !in saved,
        onClick = { save((listOf(command) + saved).take(30)) }) { Text("Save command") }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    message?.let { Text(it) }
    output?.let { RuntimeOutput(it) }
    if (saved.isNotEmpty()) Text("Saved commands", style = MaterialTheme.typography.titleMedium)
    for (entry in saved) Row(Modifier.fillMaxWidth()) {
        TextButton(onClick = { command = entry }, modifier = Modifier.weight(1f)) { Text(entry.lineSequence().first().take(80), maxLines = 1) }
        TextButton(onClick = { save(saved - entry) }) { Text("Remove") }
    }
}

@Composable
private fun PluginsPanel(ready: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var plugins by remember { mutableStateOf(emptyList<Bundle>()) }
    var busy by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var output by remember { mutableStateOf<Bundle?>(null) }
    var archive by remember { mutableStateOf<Uri?>(null) }
    var remove by remember { mutableStateOf<String?>(null) }
    val choose = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { archive = it }
    fun operate(work: () -> Bundle? = { null }) {
        busy = true
        message = null
        scope.launch {
            try {
                val (result, rows) = withContext(Dispatchers.IO) { work() to runtime().runtimePlugins().toList() }
                plugins = rows; loaded = true
                if (result?.containsKey("exit") == true) output = result
                result?.getString("startup_error")?.let { message = "Installed and disabled after startup failed. $it" }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                message = failure.message ?: "Plugin operation failed."
                runCatching { withContext(Dispatchers.IO) { runtime().runtimePlugins().toList() } }.getOrNull()?.let { plugins = it }
            } finally { busy = false }
        }
    }
    LaunchedEffect(ready) {
        if (ready) operate()
        else { plugins = emptyList(); loaded = false }
    }
    Button(onClick = { choose.launch(arrayOf("application/zip", "application/octet-stream")) }, enabled = ready && !busy) { Text("Install plugin ZIP") }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (ready && loaded && plugins.isEmpty()) Text("No plugins installed for this privilege level.")
    message?.let { Text(it) }
    output?.let { RuntimeOutput(it) }
    for (plugin in plugins) {
        val id = plugin.getString("id").orEmpty()
        val enabled = plugin.getBoolean("enabled")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(plugin.getString("name").orEmpty(), style = MaterialTheme.typography.titleMedium)
                Text("$id · ${plugin.getString("version").orEmpty()} · ${if (enabled) "Enabled" else "Disabled"}")
                plugin.getString("description")?.takeIf(String::isNotBlank)?.let { Text(it) }
                plugin.getString("error")?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row {
                    TextButton(enabled = !busy && !plugin.containsKey("error"), onClick = {
                        operate { runtime().controlRuntimePlugin(id, if (enabled) "disable" else "enable") }
                    }) { Text(if (enabled) "Disable" else "Enable") }
                    if (enabled && plugin.getBoolean("action")) TextButton(enabled = !busy,
                        onClick = { operate { runtime().controlRuntimePlugin(id, "action") } }) { Text("Run action") }
                    if (enabled && plugin.getBoolean("webui")) TextButton(enabled = !busy, onClick = {
                        context.startActivity(Intent(context, PluginWebActivity::class.java).putExtra("plugin", id))
                    }) { Text("WebUI") }
                }
                Row {
                    if (enabled) TextButton(enabled = !busy, onClick = { operate { runtime().controlRuntimePlugin(id, "restart") } }) { Text("Restart") }
                    TextButton(enabled = !busy, onClick = { remove = id }) { Text("Uninstall") }
                }
            }
        }
    }
    archive?.let { uri ->
        ConfirmRuntime("Install plugin?", "Installer and service scripts run with the active shell or root privileges.", {
            archive = null
            operate { requireNotNull(context.contentResolver.openFileDescriptor(uri, "r")).use { runtime().installRuntimePlugin(it) } }
        }) { archive = null }
    }
    remove?.let { id ->
        ConfirmRuntime("Uninstall $id?", "Stops the plugin, runs its uninstall script, and removes its files.", {
            remove = null
            operate { runtime().controlRuntimePlugin(id, "remove") }
        }) { remove = null }
    }
}

@Composable
internal fun SystemSettingsTool() {
    val scope = rememberCoroutineScope()
    var table by rememberSaveable { mutableStateOf("system") }
    var key by rememberSaveable { mutableStateOf("") }
    var value by rememberSaveable { mutableStateOf("") }
    var pending by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var output by remember { mutableStateOf<Bundle?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    fun execute(operation: String) {
        val requestedTable = table
        val requestedKey = key.trim()
        val requestedValue = value
        busy = true; error = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { runtime().systemSetting(requestedTable, requestedKey, requestedValue, operation) }
                output = result
                if (operation == "get" && result.getInt("exit") == 0)
                    value = result.getString("stdout").orEmpty().trimEnd().takeUnless { it == "null" }.orEmpty()
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = failure.message ?: "Settings operation failed."
            } finally { busy = false }
        }
    }
    Text("System settings editor", style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (name in listOf("system", "secure", "global"))
            FilterChip(selected = table == name, onClick = { table = name }, label = { Text(name) })
    }
    OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("Key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text("Value") }, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(enabled = !busy && key.isNotBlank(), onClick = { execute("get") }) { Text("Read") }
        OutlinedButton(enabled = !busy && key.isNotBlank(), onClick = { pending = "put" }) { Text("Write") }
        TextButton(enabled = !busy && key.isNotBlank(), onClick = { pending = "delete" }) { Text("Delete") }
    }
    pending?.let { operation ->
        ConfirmRuntime(if (operation == "put") "Write setting?" else "Delete setting?", "$table · ${key.trim()}", {
            pending = null; execute(operation)
        }) { pending = null }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    output?.let { RuntimeOutput(it) }
}

@Composable
internal fun InstalledAppControls(ready: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var show by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf(emptyList<ApplicationInfo>()) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<ApplicationInfo?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    suspend fun load() = withContext(Dispatchers.IO) {
        context.packageManager.getInstalledApplications(0).filter {
            it.flags and ApplicationInfo.FLAG_SYSTEM == 0 && it.packageName != context.packageName &&
                it.packageName != CompanionInstaller.PACKAGE
        }.sortedBy { it.packageName }
    }
    OutlinedButton(enabled = ready, onClick = { show = true; scope.launch { apps = load() } }) { Text("Enable or disable installed apps") }
    if (show) AlertDialog(onDismissRequest = { if (!busy) show = false }, title = { Text("Installed app controls") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search packages") }, singleLine = true)
                message?.let { Text(it) }
                Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                    for (app in apps.filter { it.packageName.contains(query, ignoreCase = true) }) {
                        Row(Modifier.fillMaxWidth()) {
                            Text(app.packageName, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
                            TextButton(enabled = !busy, onClick = { selected = app }) { Text(if (app.enabled) "Disable" else "Enable") }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }, confirmButton = { TextButton(enabled = !busy, onClick = { show = false }) { Text("Done") } })
    selected?.let { app ->
        ConfirmRuntime(if (app.enabled) "Disable app?" else "Enable app?", app.packageName, {
            selected = null; busy = true
            scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) { runtime().setPackageEnabled(app.packageName, !app.enabled) }
                    apps = load()
                    val actual = apps.firstOrNull { it.packageName == app.packageName }
                    message = if (result.getInt("exit") == 0 && actual?.enabled == !app.enabled) {
                        if (app.enabled) "App disabled." else "App enabled."
                    } else result.getString("stderr").orEmpty().ifBlank { "Android did not confirm the change." }
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    message = failure.message ?: "App change failed."
                } finally { busy = false }
            }
        }) { selected = null }
    }
}

@Composable
private fun ConfirmRuntime(title: String, detail: String, confirm: () -> Unit, dismiss: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(detail) },
        confirmButton = { TextButton(onClick = confirm) { Text("Confirm") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable
private fun RuntimeOutput(result: Bundle) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(if (result.getBoolean("timed_out")) "Timed out" else "Exit code ${result.getInt("exit")}" +
                if (result.getBoolean("truncated")) " · output limited" else "")
            val output = listOf(result.getString("stdout"), result.getString("stderr")).filterNotNull().filter(String::isNotBlank).joinToString("\n")
            if (output.isNotBlank()) Text(output, modifier = Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}
