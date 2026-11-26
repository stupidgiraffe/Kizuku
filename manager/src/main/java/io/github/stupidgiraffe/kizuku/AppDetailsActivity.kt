package io.github.stupidgiraffe.kizuku

import android.app.AppOpsManager
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppDetailsActivity : ComponentActivity() {
    private data class AppPermission(val name: String, val label: String, val granted: Boolean, val op: String?, val runtime: Boolean)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        val snapshot = intent.getBundleExtra("snapshot") ?: run { finish(); return }
        val pkg = snapshot.getString("package") ?: run { finish(); return }
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(onClick = { finish() }) { Text("Back") }
                        Text(snapshot.getString("label") ?: pkg, style = MaterialTheme.typography.headlineMedium)
                        Text(pkg, style = MaterialTheme.typography.bodySmall)
                        Details(snapshot, pkg)
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun permissions(pkg: String): List<AppPermission> {
        val info = packageManager.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
        return info.requestedPermissions.orEmpty().mapIndexedNotNull { index, permission ->
            val details = runCatching { packageManager.getPermissionInfo(permission, 0) }.getOrNull() ?: return@mapIndexedNotNull null
            AppPermission(permission, details.loadLabel(packageManager).toString(),
                ((info.requestedPermissionsFlags?.getOrNull(index) ?: 0) and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0,
                AppOpsManager.permissionToOp(permission),
                (details.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE) == PermissionInfo.PROTECTION_DANGEROUS)
        }
    }

    @Composable
    private fun Details(snapshot: Bundle, pkg: String) {
        val scope = rememberCoroutineScope()
        var permissions by remember { mutableStateOf(emptyList<AppPermission>()) }
        var refresh by remember { mutableIntStateOf(0) }
        var working by remember { mutableStateOf(false) }
        var feedback by remember { mutableStateOf<String?>(null) }
        var appops by remember { mutableStateOf<String?>(null) }
        var hostReady by remember { mutableStateOf(CoreConnection.service != null) }
        var forceStop by remember { mutableStateOf(false) }
        val protect = rememberProtectedAction(DeviceProtection.AppPermissions) { feedback = it }
        LaunchedEffect(refresh) {
            try { permissions = withContext(Dispatchers.IO) { permissions(pkg) } }
            catch (failure: Exception) { feedback = failure.message }
            hostReady = CoreConnection.service != null
        }
        fun action(operation: String, name: String = "", value: String = "") {
            protect {
            working = true
            scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        val service = checkNotNull(CoreConnection.service) { "Start Kizuku first." }
                        service.runtimePackageAction(snapshot, operation, name, value)
                    }
                    val out = result.getString("stdout", "").trim()
                    val error = result.getString("stderr", "").trim()
                    feedback = if (result.getInt("exit") == 0) {
                        if (operation.startsWith("appops-")) { appops = out; null } else "Applied."
                    } else "Exit ${result.getInt("exit")} · ${error.ifBlank { out }}"
                    refresh++
                } catch (failure: Exception) { feedback = failure.message ?: "Operation failed." }
                finally { working = false }
            }
            }
        }
        Text("Host access · ${AuthorizationStore.hostLabel(snapshot.getInt(AuthorizationStore.HOST_UID))}",
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null)))
            }) { Text("Android settings") }
            OutlinedButton(enabled = hostReady && !working, onClick = { forceStop = true }) { Text("Force-stop") }
        }
        TextButton(onClick = {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                .onFailure { feedback = it.message }
        }) { Text("Battery optimization settings") }
        if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
        feedback?.let { SelectionContainer { Text(it) } }
        Text("Runtime permissions", style = MaterialTheme.typography.titleMedium)
        val runtimePermissions = permissions.filter { it.runtime }
        if (runtimePermissions.isEmpty()) Text("No runtime permissions requested.")
        runtimePermissions.forEach { permission ->
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(permission.label)
                    Text(permission.name, style = MaterialTheme.typography.bodySmall)
                }
                Switch(permission.granted, enabled = hostReady && !working, onCheckedChange = {
                    action("permission", permission.name, if (it) "grant" else "revoke")
                })
            }
        }
        Text("AppOps", style = MaterialTheme.typography.titleMedium)
        TextButton(enabled = hostReady && !working, onClick = { action("appops-get") }) { Text("Read current operations") }
        appops?.let { SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall) } }
        permissions.mapNotNull { it.op }.distinct().forEach { operation ->
            var menu by remember(operation) { mutableStateOf(false) }
            Box {
                TextButton(enabled = hostReady && !working, onClick = { menu = true }) { Text(operation.removePrefix("android:")) }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    for (mode in listOf("default", "foreground", "allow", "ignore", "deny")) {
                        DropdownMenuItem(text = { Text(mode.replaceFirstChar { it.uppercase() }) }, onClick = {
                            menu = false
                            action("appops-set", operation, mode)
                        })
                    }
                }
            }
        }
        if (forceStop) AlertDialog(onDismissRequest = { forceStop = false }, title = { Text("Force-stop this app?") },
            text = { Text("Unsaved work in this app may be lost.") },
            confirmButton = { TextButton(onClick = { forceStop = false; action("force-stop") }) { Text("Force-stop") } },
            dismissButton = { TextButton(onClick = { forceStop = false }) { Text("Cancel") } })
    }
}
