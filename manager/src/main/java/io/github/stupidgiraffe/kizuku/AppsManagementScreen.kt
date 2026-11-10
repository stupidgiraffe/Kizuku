package io.github.stupidgiraffe.kizuku

import android.os.Build
import android.os.Bundle
import android.text.BidiFormatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.stupidgiraffe.kizuku.core.Capability
import io.github.stupidgiraffe.kizuku.core.GrantLedger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val appScopes = Capability.SHIZUKU or Capability.AXERON
private const val SHIZUKU_WARNING = "Shizuku grants system Binder access and can run legacy processes and UserServices with the active host's privileges (ADB shell or root). Axeron access is managed separately."

private enum class AppFilter(val label: String) {
    ALL("All apps"), AUTHORIZED("Authorized"), REQUESTED("Requests")
}

private enum class AppPermission(val label: String, val scope: Long, val description: String) {
    SHIZUKU("Shizuku Binder", Capability.SHIZUKU, SHIZUKU_WARNING),
    AXERON("Axeron", Capability.AXERON, "Allows shell commands. A remembered grant also allows root commands whenever Kizuku runs as root."),
    DIAGNOSTICS("Native diagnostics", Capability.DIAGNOSTICS, "Allows this app to read the host UID, PID, Android API level and uptime.")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AppsManagementScreen(
    state: CoreState,
    controller: ActivationController,
    packageControls: (@Composable () -> Unit)? = null,
) {
    val batch by controller.shizukuBatch.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    var search by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(AppFilter.ALL) }
    var menuOpen by remember { mutableStateOf(false) }
    var approval by remember { mutableStateOf<Pair<Bundle, AppPermission>?>(null) }
    var bulkApproval by remember { mutableStateOf<Pair<ShizukuBatchAction, List<Bundle>>?>(null) }
    var showFailures by remember { mutableStateOf(false) }
    val clients = remember(state.apps) { state.apps.filter { it.hasScope("available_scopes", appScopes) } }
    val query = search.trim()
    val visible = remember(clients, query, filter) {
        clients.filter { app ->
            (app.packageName.contains(query, ignoreCase = true) || app.label.contains(query, ignoreCase = true)) &&
                when (filter) {
                    AppFilter.ALL -> true
                    AppFilter.AUTHORIZED -> app.hasPermission(appScopes)
                    AppFilter.REQUESTED -> app.hasScope("requested", appScopes)
                }
        }
    }
    val eligibleForGrant = remember(clients) {
        clients.filter { it.hasScope("manifest_scopes", Capability.SHIZUKU) && !it.hasScope("remembered", Capability.SHIZUKU) }
    }
    val eligibleForRevoke = remember(clients) { clients.filter { it.hasPermission(Capability.SHIZUKU) } }
    val actionsEnabled = state.appsLoaded && !state.busy && !batch.running
    val availability = when {
        state.safeMode -> "Safe Mode pauses all client access."
        state.status == null -> "Remembered permissions apply when the core starts."
        else -> null
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        item {
            Column {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text("Search installed clients") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    trailingIcon = if (search.isEmpty()) null else {
                        { IconButton(onClick = { search = "" }) { Icon(Icons.Outlined.Close, contentDescription = "Clear search") } }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppFilter.entries.forEach { option ->
                        FilterChip(selected = filter == option, onClick = { filter = option; keyboard?.hide() }, label = { Text(option.label) })
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val summary = when {
                        !state.appsLoaded -> "Compatible clients"
                        visible.size == 1 && clients.size == 1 -> "1 client"
                        visible.size == clients.size -> "${visible.size} clients"
                        else -> "${visible.size} of ${clients.size} clients"
                    }
                    Text(summary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    Box {
                        TextButton(enabled = actionsEnabled && (eligibleForGrant.isNotEmpty() || eligibleForRevoke.isNotEmpty()), onClick = {
                            menuOpen = true
                            keyboard?.hide()
                        }) { Text("Shizuku actions") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Grant All Shizuku") }, enabled = actionsEnabled && eligibleForGrant.isNotEmpty(), onClick = {
                                menuOpen = false
                                bulkApproval = ShizukuBatchAction.GRANT to eligibleForGrant
                            })
                            DropdownMenuItem(text = { Text("Revoke All Shizuku") }, enabled = actionsEnabled && eligibleForRevoke.isNotEmpty(), onClick = {
                                menuOpen = false
                                bulkApproval = ShizukuBatchAction.REVOKE to eligibleForRevoke
                            })
                        }
                    }
                }
                availability?.let { SecondaryText(it) }
            }
        }
        if (packageControls != null) item { packageControls() }
        if (batch.running) item { AnnouncedText("Updating Shizuku permissions…") }
        batch.result?.let { result -> item { BatchFeedback(result) { showFailures = true } } }
        when {
            state.appsError != null -> item { AnnouncedText(state.appsError, error = true) }
            !state.appsLoaded -> item { AnnouncedText("Loading installed clients…") }
            clients.isEmpty() -> item { SecondaryText("No Shizuku or Axeron clients installed. Compatible apps appear here after installation.") }
            visible.isEmpty() -> item { SecondaryText("No clients match the current search and filter.") }
        }
        items(visible, key = { it.packageName }) { app ->
            AppPermissionCard(app, state, appScopes, actionsEnabled) { permission, enabled ->
                if (enabled) approval = app to permission
                else controller.decide(app, permission.scope, null)
            }
        }
    }

    approval?.let { (app, permission) ->
        PermissionDialog(app, permission, state.status != null, actionsEnabled, controller) { approval = null }
    }
    bulkApproval?.let { (action, apps) ->
        BulkShizukuDialog(action, apps, enabled = actionsEnabled, onDismiss = { bulkApproval = null }) {
            controller.changeAllShizuku(action, apps)
            bulkApproval = null
        }
    }
    batch.result?.takeIf { showFailures }?.let { result ->
        AlertDialog(
            onDismissRequest = { showFailures = false },
            title = { Text("Shizuku changes to review") },
            text = {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    result.failures.forEach { (pkg, reason) ->
                        Column {
                            Text(pkg, style = MaterialTheme.typography.titleSmall)
                            SecondaryText(reason)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showFailures = false }) { Text("Close") } },
        )
    }
}

@Composable
internal fun NativeDiagnosticsPermissions(state: CoreState, controller: ActivationController) {
    val batch by controller.shizukuBatch.collectAsStateWithLifecycle()
    var approval by remember { mutableStateOf<Bundle?>(null) }
    val clients = remember(state.apps) { state.apps.filter { it.hasScope("available_scopes", Capability.DIAGNOSTICS) } }
    val actionsEnabled = state.appsLoaded && !state.busy && !batch.running
    Text("Native diagnostics permissions", style = MaterialTheme.typography.titleMedium)
    when {
        state.appsError != null -> AnnouncedText(state.appsError, error = true)
        !state.appsLoaded -> AnnouncedText("Loading installed clients…")
        clients.isEmpty() -> SecondaryText("No installed apps declare or request native diagnostics.")
    }
    clients.forEach { app ->
        AppPermissionCard(app, state, Capability.DIAGNOSTICS, actionsEnabled) { _, enabled ->
            if (enabled) approval = app else controller.decide(app, Capability.DIAGNOSTICS, null)
        }
    }
    approval?.let {
        PermissionDialog(it, AppPermission.DIAGNOSTICS, state.status != null, actionsEnabled, controller) { approval = null }
    }
}

@Composable
private fun AppPermissionCard(
    app: Bundle,
    state: CoreState,
    scopes: Long,
    actionsEnabled: Boolean,
    onToggle: (AppPermission, Boolean) -> Unit,
) {
    val label = remember(app.label) { BidiFormatter.getInstance().unicodeWrap(app.label) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppIcon(app)
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val members = app.getStringArray("shared_uid").orEmpty()
            if (members.size > 1) SecondaryText("Shared UID · ${members.joinToString()}")
            AppPermission.entries.forEach { permission ->
                val scope = permission.scope
                if (scopes and scope != 0L && app.hasScope("available_scopes", scope)) {
                    Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(permission.label, style = MaterialTheme.typography.bodyMedium)
                            SecondaryText(app.permissionStatus(scope, state.status != null, state.safeMode))
                        }
                        Switch(
                            checked = app.hasPermission(scope),
                            enabled = actionsEnabled && app.getBoolean("verified"),
                            onCheckedChange = { onToggle(permission, it) },
                            modifier = Modifier.testTag("capability:${app.packageName}:$scope").semantics {
                                testTagsAsResourceId = true
                                contentDescription = "${permission.label} for $label"
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppIcon(app: Bundle) {
    val pm = LocalContext.current.packageManager
    val px = with(LocalDensity.current) { 40.dp.roundToPx() }
    val icon by produceState<ImageBitmap?>(null, app.packageName, app.getLong("package_update"), px) {
        value = withContext(Dispatchers.IO) {
            runCatching { pm.getApplicationIcon(app.packageName).toBitmap(px, px).asImageBitmap() }.getOrNull()
        }
    }
    val bitmap = icon
    if (bitmap != null) Image(bitmap, contentDescription = null, modifier = Modifier.size(40.dp))
    else Icon(Icons.Outlined.Apps, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
}

@Composable
private fun PermissionDialog(
    app: Bundle,
    permission: AppPermission,
    connected: Boolean,
    enabled: Boolean,
    controller: ActivationController,
    onDismiss: () -> Unit,
) {
    val title = if (permission == AppPermission.DIAGNOSTICS) "native diagnostics" else permission.label
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Authorize $title?") },
        text = {
            ProtectPermissionDialog()
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(app.packageName, style = MaterialTheme.typography.titleSmall)
                Text(permission.description)
                SecondaryText("UID ${app.getInt("uid")}\nSHA-256\n${app.getString("certificate")}")
                app.getStringArray("shared_uid")?.takeIf { it.size > 1 }?.let {
                    Text("Packages sharing this UID\n${it.joinToString("\n")}")
                }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                listOf(
                    "Once" to GrantLedger.Lifetime.ONCE,
                    "This service session" to GrantLedger.Lifetime.SESSION,
                    "Remember this certificate" to GrantLedger.Lifetime.PERMANENT,
                ).forEach { (label, lifetime) ->
                    TextButton(enabled = enabled && (connected || lifetime == GrantLedger.Lifetime.PERMANENT), onClick = {
                        controller.decide(app, permission.scope, lifetime.ordinal)
                        onDismiss()
                    }) { Text(label) }
                }
            }
        },
        dismissButton = {
            TextButton(enabled = enabled, onClick = { controller.decide(app, permission.scope, null); onDismiss() }) { Text("Deny") }
        },
    )
}

@Composable
private fun BulkShizukuDialog(
    action: ShizukuBatchAction,
    apps: List<Bundle>,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val granting = action == ShizukuBatchAction.GRANT
    val appCount = "${apps.size} ${if (apps.size == 1) "app" else "apps"}"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (granting) "Grant Shizuku to $appCount?" else "Revoke Shizuku for $appCount?") },
        text = {
            ProtectPermissionDialog()
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (granting) SHIZUKU_WARNING else "Removes remembered and temporary Shizuku grants for the listed apps.")
                Text(if (granting) "Remembers permission for every app listed below, including any with temporary access. Search and filters only affect the list behind this dialog."
                    else "Search and filters only affect the list behind this dialog.")
                apps.forEach { app ->
                    Column {
                        Text(BidiFormatter.getInstance().unicodeWrap(app.label), style = MaterialTheme.typography.titleSmall)
                        SecondaryText(app.packageName)
                        app.getStringArray("shared_uid")?.takeIf { it.size > 1 }?.let { SecondaryText("Shared UID · ${it.joinToString()}") }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = enabled, onClick = onConfirm) { Text(if (granting) "Grant Shizuku" else "Revoke Shizuku") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ProtectPermissionDialog() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.filterTouchesWhenObscured = true
        val window = (view.parent as? DialogWindowProvider)?.window
        if (Build.VERSION.SDK_INT >= 31) window?.setHideOverlayWindows(true)
        onDispose { }
    }
}

@Composable
private fun BatchFeedback(result: ShizukuBatchResult, onReviewFailures: () -> Unit) {
    val title = if (result.action == ShizukuBatchAction.GRANT) "Grant All Shizuku" else "Revoke All Shizuku"
    val summary = buildList {
        if (result.coreUpdates != 0) add("${result.coreUpdates} confirmed on core")
        if (result.offlineUpdates != 0) add(if (result.action == ShizukuBatchAction.GRANT)
            "${result.offlineUpdates} remembered for next start" else "${result.offlineUpdates} revoked with core offline")
        if (result.failures.isNotEmpty()) add("${result.failures.size} need review")
    }.joinToString(" · ")
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            AnnouncedText(summary, error = result.failures.isNotEmpty())
            if (result.failures.isNotEmpty()) TextButton(onClick = onReviewFailures) { Text("Review affected apps") }
        }
    }
}

@Composable
private fun SecondaryText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun AnnouncedText(text: String, error: Boolean = false) {
    Text(text, style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

private val Bundle.packageName get() = getString("package").orEmpty()
private val Bundle.label get() = getString("label") ?: packageName
private fun Bundle.hasScope(key: String, mask: Long) = getLong(key) and mask != 0L
private fun Bundle.hasPermission(mask: Long) = hasScope("assigned", mask) || hasScope("remembered", mask)
private fun Bundle.permissionStatus(mask: Long, connected: Boolean, safeMode: Boolean): String {
    val requested = hasScope("requested", mask)
    val status = when {
        safeMode && hasPermission(mask) -> "Paused by Safe Mode"
        hasScope("remembered", mask) && (!connected || !hasScope("granted", mask)) -> "Remembered for next start"
        hasScope("remembered", mask) -> "Remembered"
        hasScope("assigned", mask) -> "Temporary access"
        requested -> return "Requested"
        else -> return "Off"
    }
    return if (requested) "$status · request pending" else status
}
