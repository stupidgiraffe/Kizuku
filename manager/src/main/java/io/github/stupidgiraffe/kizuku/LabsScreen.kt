package io.github.stupidgiraffe.kizuku

import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
internal fun LabsScreen(
    controller: ActivationController,
    active: Boolean = true,
    additionalTools: @Composable () -> Unit = {},
) {
    LabsDeviceAccess(active) {
        ServiceDoctorPanel(controller)
        AccessibilityPanel()
        additionalTools()
    }
    if (active) {
        LabsPanel("Compatibility boundaries") {
            LabsDetail("Device Owner, Dhizuku and the SU bridge are unavailable in this build.")
        }
    }
}

@Composable
private fun ServiceDoctorPanel(controller: ActivationController) {
    val context = LocalContext.current
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
        report?.let { checks ->
            HorizontalDivider()
            checkedAt?.let { LabsDetail("Last checked at $it") }
            checks.forEach { check ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(check.label, style = MaterialTheme.typography.titleSmall)
                    LabsDetail(check.evidence)
                }
            }
        }
    }
}

@Composable
private fun AccessibilityPanel() {
    val context = LocalContext.current
    var inspecting by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var entries by remember { mutableStateOf<List<AccessibilityEntry>?>(null) }
    var readFailed by remember { mutableStateOf(false) }
    var settingsFailed by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (inspecting) refresh++ }
    LaunchedEffect(inspecting, refresh) {
        if (!inspecting) return@LaunchedEffect
        entries = null
        val result = withContext(Dispatchers.IO) { runCatching { readAccessibilityInventory(context) } }
        entries = result.getOrNull()
        readFailed = result.isFailure
    }

    LabsPanel("Accessibility services") {
        LabsDetail("Review installed services and see which ones are enabled. Android Settings manages access.")
        TextButton(onClick = { inspecting = !inspecting; readFailed = false }) {
            Text(if (inspecting) "Hide services" else "Inspect services")
        }
        TextButton(onClick = {
            settingsFailed = !openLabsSettings(context, Settings.ACTION_ACCESSIBILITY_SETTINGS)
        }) { Text("Open Android accessibility settings") }
        if (settingsFailed) LabsError("Android accessibility settings could not be opened.")
        if (inspecting) {
            HorizontalDivider()
            val installed = entries
            when {
                readFailed -> LabsError("Could not read services. Hide and inspect them again.")
                installed == null -> LabsDetail("Reading Android accessibility services…")
                installed.isEmpty() -> LabsDetail("Android reports no installed accessibility services.")
                else -> {
                    LabsDetail("${installed.count { it.enabled }} enabled · ${installed.size} installed")
                    installed.forEach { service ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(service.label, style = MaterialTheme.typography.titleSmall)
                            LabsDetail(service.component)
                            Text(
                                if (service.enabled) "Enabled" else "Disabled",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (service.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
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
