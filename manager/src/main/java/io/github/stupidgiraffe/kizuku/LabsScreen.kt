package io.github.stupidgiraffe.kizuku

import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
internal fun LabsScreen(
    controller: ActivationController,
    active: Boolean = true,
    additionalTools: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportProblem by remember { mutableStateOf<String?>(null) }
    // The document picker can stop the activity and lock Labs while it is open.
    var exportSnapshot by rememberSaveable { mutableStateOf<String?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val snapshot = exportSnapshot
        exportSnapshot = null
        if (uri != null && snapshot != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    checkNotNull(context.contentResolver.openOutputStream(uri)) { "Android couldn't open the destination." }
                        .bufferedWriter().use { it.write(snapshot) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                exportProblem = "Could not save the diagnostic report."
            }
        }
    }
    LabsDeviceAccess(active) {
        ServiceDoctorPanel(controller, exportSnapshot == null, exportProblem) { report ->
            exportSnapshot = report
            exportProblem = null
            try {
                export.launch("kizuku-diagnostics.txt")
            } catch (_: RuntimeException) {
                exportSnapshot = null
                exportProblem = "Android could not open the file picker."
            }
        }
        val state by controller.state.collectAsStateWithLifecycle()
        AccessibilityPanel(state)
        additionalTools()
    }
}

@Composable
private fun ServiceDoctorPanel(
    controller: ActivationController,
    canExport: Boolean,
    exportProblem: String?,
    export: (String) -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var run by remember { mutableIntStateOf(0) }
    var checking by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<List<DiagnosticCheck>?>(null) }
    var checkedAt by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf(false) }

    LaunchedEffect(run) {
        if (run == 0) return@LaunchedEffect
        checking = true
        failure = false
        try {
            report = runServiceDoctor(context, controller)
            checkedAt = DateFormat.getTimeInstance().format(Date())
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            failure = true
        } finally {
            checking = false
        }
    }

    LabsPanel("Service Doctor") {
        LabsDetail("Check the live core connection, recovery state and Android settings used for ADB pairing.")
        Button(onClick = { run++ }, enabled = !checking) {
            Text(if (checking) "Running checks…" else "Run checks")
        }
        if (failure) LabsError("Could not finish the checks. Run them again.")
        exportProblem?.let { LabsError(it) }
        report?.let { checks ->
            HorizontalDivider()
            checkedAt?.let { LabsDetail("Last checked at $it") }
            val reportText = formatServiceDoctorReport(checks)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { clipboard.setText(AnnotatedString(reportText)) }) { Text("Copy report") }
                TextButton(enabled = canExport, onClick = { export(reportText) }) { Text("Export report") }
            }
            LabsDetail("Share this report if you want to include your Android model and connection state.")
            checks.forEach { check ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(check.label, style = MaterialTheme.typography.titleSmall)
                    LabsDetail(check.evidence)
                }
            }
        }
    }
}

internal fun formatServiceDoctorReport(checks: List<DiagnosticCheck>): String = buildString {
    appendLine("Kizuku ${BuildConfig.VERSION_NAME} · Service Doctor")
    checks.forEach { appendLine("${it.label} — ${it.evidence}") }
}

@Composable
private fun AccessibilityPanel(state: CoreState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val systemUser = remember(context) { isPrimarySystemUser(context) }
    val canChange = systemUser && !state.safeMode && state.status?.getBoolean("axeron_runtime") == true
    var inspecting by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var entries by remember { mutableStateOf<List<AccessibilityEntry>?>(null) }
    var readFailed by remember { mutableStateOf(false) }
    var settingsFailed by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var selection by remember { mutableStateOf<AccessibilityEntry?>(null) }
    var changing by remember { mutableStateOf(false) }
    var changeFailure by remember { mutableStateOf<String?>(null) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (inspecting) refresh++ }
    LaunchedEffect(inspecting, refresh) {
        if (!inspecting) return@LaunchedEffect
        entries = null
        val result = withContext(Dispatchers.IO) { runCatching { readAccessibilityInventory(context) } }
        entries = result.getOrNull()
        readFailed = result.isFailure
    }

    LabsPanel("Accessibility services") {
        LabsDetail("Review or change installed services. Android Settings can also manage access.")
        when {
            !systemUser -> LabsDetail("Changes are available from Android’s system user.")
            state.safeMode -> LabsDetail("Safe Mode pauses accessibility changes.")
            !canChange -> LabsDetail("Start the Kizuku runtime to make changes.")
        }
        TextButton(onClick = { inspecting = !inspecting; readFailed = false }) {
            Text(if (inspecting) "Hide services" else "Inspect services")
        }
        TextButton(onClick = {
            settingsFailed = !openLabsSettings(context, Settings.ACTION_ACCESSIBILITY_SETTINGS)
        }) { Text("Open Android accessibility settings") }
        if (settingsFailed) LabsError("Android accessibility settings could not be opened.")
        changeFailure?.let { LabsError(it) }
        if (inspecting) {
            HorizontalDivider()
            val installed = entries
            when {
                readFailed -> LabsError("Could not read services. Hide and inspect them again.")
                installed == null -> LabsDetail("Reading Android accessibility services…")
                installed.isEmpty() -> LabsDetail("Android reports no installed accessibility services.")
                else -> {
                    LabsDetail("${installed.count { it.enabled }} enabled · ${installed.size} installed")
                    OutlinedTextField(value = search, onValueChange = { search = it }, singleLine = true,
                        label = { Text("Search services") }, modifier = Modifier.fillMaxWidth())
                    val visible = installed.filter {
                        it.label.contains(search.trim(), ignoreCase = true) || it.component.contains(search.trim(), ignoreCase = true)
                    }
                    if (visible.isEmpty()) LabsDetail("No services match this search.")
                    visible.forEach { service ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(service.label, style = MaterialTheme.typography.titleSmall)
                            LabsDetail(service.component)
                            Text(
                                if (service.enabled) "Enabled" else "Disabled",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (service.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(enabled = canChange && !changing, onClick = { selection = service }) {
                                Text(if (service.enabled) "Disable" else "Enable")
                            }
                        }
                    }
                }
            }
        }
    }
    selection?.let { service ->
        val enable = !service.enabled
        AlertDialog(
            onDismissRequest = { selection = null },
            title = { Text(if (enable) "Enable accessibility service?" else "Disable accessibility service?") },
            text = {
                ProtectPermissionDialog()
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(service.label)
                    LabsDetail(service.component)
                    LabsDetail(if (enable) "This service can observe Android UI and may perform actions on your behalf. Enable it only if you trust the app."
                    else "Android will stop this service.")
                }
            },
            confirmButton = {
                TextButton(enabled = canChange && !changing, onClick = {
                    selection = null
                    changing = true
                    changeFailure = null
                    scope.launch {
                        try {
                            setAccessibilityServiceEnabled(context, service.component, enable)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            changeFailure = error.message ?: "Android could not change the service."
                        } finally {
                            changing = false
                            refresh++
                        }
                    }
                }) { Text(if (enable) "Enable service" else "Disable service") }
            },
            dismissButton = { TextButton(onClick = { selection = null }) { Text("Cancel") } },
        )
    }
}

@Composable
internal fun LabsPanel(title: String, content: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

@Composable
internal fun LabsDetail(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun LabsError(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}
