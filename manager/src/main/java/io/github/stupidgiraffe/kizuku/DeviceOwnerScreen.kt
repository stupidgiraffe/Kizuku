package io.github.stupidgiraffe.kizuku

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun DeviceOwnerPanel() {
    val context = LocalContext.current
    val policies = remember(context) { DeviceOwnerPolicies(context.applicationContext) }
    val coroutine = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var enrolled by remember { mutableStateOf<Boolean?>(null) }
    var manage by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    LaunchedEffect(policies, refresh) {
        if (policies.supported) {
            val result = withContext(Dispatchers.IO) { runCatching { policies.enrolled } }
            enrolled = result.getOrNull()
            result.exceptionOrNull()?.let { message = it.message ?: "Unable to read Device Owner status." }
        }
    }
    LabsPanel("Device Owner") {
        when {
            !policies.supported -> LabsDetail("This Android build does not expose device administration.")
            enrolled == null -> {
                LabsDetail("Checking Android enrollment…")
                message?.let { TextButton(onClick = { message = null; refresh++ }) { Text("Check again") } }
            }
            enrolled == true -> {
                LabsDetail("Enrolled. Android enforces these app policies even when Kizuku's privilege service is stopped.")
                Button(enabled = !removing, onClick = { manage = true }) { Text("Manage app policies") }
                TextButton(enabled = !removing, onClick = { remove = true }) { Text("Remove Device Owner") }
            }
            else -> {
                LabsDetail("Optional enrollment on a dedicated Android device. Device Owner has device-wide authority, and Android may refuse enrollment on an already configured device.")
                LabsDetail("Enroll manually through an authorized computer ADB connection, then return here.")
                TextButton(onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    message = if (clipboard == null) "Android's clipboard is unavailable." else {
                        clipboard.setPrimaryClip(ClipData.newPlainText("Device Owner enrollment", policies.enrollmentCommand))
                        "Enrollment command copied."
                    }
                }) { Text("Copy ADB enrollment command") }
                TextButton(onClick = { refresh++ }) { Text("Check enrollment") }
            }
        }
        message?.let { LabsDetail(it) }
    }
    if (manage && enrolled == true) DeviceOwnerAppsDialog(policies) { manage = false }
    if (remove) AlertDialog(
        onDismissRequest = { if (!removing) remove = false },
        title = { Text("Remove Device Owner?") },
        text = { Text("Kizuku will undo the app policies it applied here and give up its Android Device Owner role. You would need to enroll again to manage these policies.") },
        confirmButton = {
            TextButton(enabled = !removing, onClick = {
                removing = true
                coroutine.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { policies.removeOwner() } }
                    message = result.fold({ "Device Owner removed." }, { it.message ?: "Unable to remove Device Owner." })
                    removing = false
                    remove = false
                    refresh++
                }
            }) { Text(if (removing) "Removing…" else "Remove owner role") }
        },
        dismissButton = { TextButton(enabled = !removing, onClick = { remove = false }) { Text("Cancel") } },
    )
}

@Composable
private fun DeviceOwnerAppsDialog(policies: DeviceOwnerPolicies, onDismiss: () -> Unit) {
    val coroutine = rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<DeviceOwnerPolicies.App>?>(null) }
    var query by remember { mutableStateOf("") }
    var selection by remember { mutableStateOf<DeviceOwnerPolicies.Target?>(null) }
    var status by remember { mutableStateOf<DeviceOwnerPolicies.Status?>(null) }
    var pending by remember { mutableStateOf<Pair<DeviceOwnerPolicies.Policy, Boolean>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(policies) {
        val result = withContext(Dispatchers.IO) { runCatching { policies.apps() } }
        apps = result.getOrNull()
        message = result.exceptionOrNull()?.message
    }
    val target = selection
    val confirmation = pending
    val current = status
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(target?.app?.name ?: "Device Owner app policies") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (target == null) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Search installed apps") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val visible = apps?.filter { it.name.contains(query, true) || it.packageName.contains(query, true) }
                    if (visible == null && message == null) LabsDetail("Loading installed apps…")
                    if (visible?.isEmpty() == true) LabsDetail("No eligible apps match.")
                    LazyColumn(Modifier.heightIn(max = 260.dp)) {
                        items(visible.orEmpty(), key = { it.packageName }) { app ->
                            TextButton(enabled = !busy, onClick = {
                                busy = true
                                message = null
                                coroutine.launch {
                                    val selected = withContext(Dispatchers.IO) {
                                        runCatching {
                                            val verified = policies.select(app)
                                            verified to policies.status(verified)
                                        }
                                    }
                                    selected.onSuccess { (verified, policyStatus) ->
                                        selection = verified
                                        status = policyStatus
                                    }.onFailure { message = it.message ?: "Unable to read app policies." }
                                    busy = false
                                }
                            }) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(app.name)
                                    LabsDetail(app.packageName)
                                }
                            }
                        }
                    }
                } else if (confirmation != null) {
                    LabsDetail(target.app.packageName)
                    Text(policyExplanation(confirmation.first, confirmation.second))
                } else if (current != null) {
                    LabsDetail(target.app.packageName)
                    TextButton(enabled = !busy, onClick = {
                        pending = DeviceOwnerPolicies.Policy.SUSPEND to !current.suspended
                    }) { Text(if (current.suspended) "Resume app" else "Suspend app") }
                    TextButton(enabled = !busy, onClick = {
                        pending = DeviceOwnerPolicies.Policy.BLOCK_UNINSTALL to !current.uninstallBlocked
                    }) { Text(if (current.uninstallBlocked) "Allow uninstall" else "Block uninstall") }
                }
                message?.let { LabsDetail(it) }
            }
        },
        confirmButton = {
            if (target != null && confirmation != null) {
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    message = null
                    coroutine.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching { policies.apply(target, confirmation.first, confirmation.second) }
                        }
                        result.onSuccess {
                            status = it
                            pending = null
                        }.onFailure {
                            message = it.message ?: "Android rejected this policy change."
                        }
                        busy = false
                    }
                }) { Text(if (busy) "Applying…" else "Apply policy") }
            } else {
                TextButton(enabled = !busy, onClick = onDismiss) { Text("Done") }
            }
        },
        dismissButton = {
            if (target != null) {
                TextButton(enabled = !busy, onClick = {
                    message = null
                    if (confirmation != null) pending = null else selection = null
                }) { Text(if (confirmation != null) "Cancel" else "Back") }
            }
        },
    )
}

private fun policyExplanation(policy: DeviceOwnerPolicies.Policy, enabled: Boolean) = when (policy) {
    DeviceOwnerPolicies.Policy.SUSPEND ->
        if (enabled) "Android will stop this app from opening and hide its notifications until you resume it."
        else "Android will allow this app to open and show its notifications again."
    DeviceOwnerPolicies.Policy.BLOCK_UNINSTALL ->
        if (enabled) "Android will prevent this app from being uninstalled until you allow it again."
        else "Android will remove Kizuku’s uninstall block for this app."
}
