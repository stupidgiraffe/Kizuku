package io.github.stupidgiraffe.kizuku

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import io.github.stupidgiraffe.kizuku.api.AndroidIdentity
import io.github.stupidgiraffe.kizuku.core.Capability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AxeronAccessActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val caller = callingPackage
        if (intent.action != "AxRuntime.REQUEST_ACCESS" || caller == null || caller == packageName) {
            finish()
            return
        }
        val identity = runCatching {
            @Suppress("DEPRECATION") val pkg = packageManager.getPackageInfo(caller, PackageManager.GET_PROVIDERS)
            require(pkg.providers.orEmpty().any {
                it.authority == "$caller.axeron" && it.exported && it.enabled &&
                    it.readPermission == "android.permission.INTERACT_ACROSS_USERS_FULL" &&
                    it.writePermission == "android.permission.INTERACT_ACROSS_USERS_FULL"
            })
            AndroidIdentity.resolve(packageManager, requireNotNull(pkg.applicationInfo).uid, caller)
        }.getOrElse { finish(); return }
        window.decorView.filterTouchesWhenObscured = true
        if (Build.VERSION.SDK_INT >= 31) window.setHideOverlayWindows(true)
        val controller = ActivationController.get(this)
        val snapshot = Bundle().apply {
            putString("package", caller)
            putInt("uid", identity.uid())
            putString("identity", AndroidIdentity.encode(identity).toString())
            putString("certificate", identity.members().getValue(caller).first())
            putStringArray("shared_uid", identity.members().keys.toTypedArray())
        }
        setContent {
            MaterialTheme {
                val scope = rememberCoroutineScope()
                var hostUid by remember { mutableStateOf<Int?>(null) }
                var busy by remember { mutableStateOf(true) }
                var problem by remember { mutableStateOf<String?>(null) }
                val protect = rememberProtectedAction(DeviceProtection.AppPermissions) { problem = it }
                LaunchedEffect(Unit) {
                    try {
                        val granted = withContext(Dispatchers.IO) {
                            val host = checkNotNull(CoreConnection.service) { "Start Kizuku and request access again." }
                            val uid = host.status().getInt("uid", -1)
                            check(uid == AuthorizationStore.ROOT_UID || uid == AuthorizationStore.ADB_UID)
                            host.requestAxeronAccess(caller, identity.uid())
                            uid to host.applications().any {
                                it.getString("package") == caller && it.getLong("granted") and Capability.AXERON != 0L
                            }
                        }
                        if (granted.second) { setResult(RESULT_OK); finish() } else hostUid = granted.first
                    } catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        problem = failure.message ?: "Cannot request Axeron access."
                    } finally { busy = false }
                }
                AlertDialog(onDismissRequest = { if (!busy) finish() }, title = { Text("Allow Axeron access?") }, text = {
                    ProtectPermissionDialog()
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(caller, style = MaterialTheme.typography.titleSmall)
                        hostUid?.let { Text("Allows commands through the ${AuthorizationStore.hostLabel(it)} host.") }
                        if (identity.members().size > 1) Text("Shared Android UID: ${identity.members().keys.joinToString()}")
                        Text("Certificate ${snapshot.getString("certificate")}", style = MaterialTheme.typography.labelSmall)
                        problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }, confirmButton = {
                    TextButton(enabled = hostUid != null && !busy, onClick = {
                        protect {
                            busy = true
                            problem = null
                            scope.launch {
                                try {
                                    snapshot.putInt(AuthorizationStore.HOST_UID, checkNotNull(hostUid))
                                    controller.approveAxeronRequest(snapshot)
                                    setResult(RESULT_OK)
                                    finish()
                                } catch (failure: Exception) {
                                    if (failure is CancellationException) throw failure
                                    problem = failure.message ?: "Cannot save Axeron access."
                                } finally { busy = false }
                            }
                        }
                    }) { Text("Remember access") }
                }, dismissButton = { TextButton(enabled = !busy, onClick = { finish() }) { Text("Cancel") } })
            }
        }
    }
}
