package io.github.stupidgiraffe.kizuku

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

class DhizukuPermissionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action !in ACTIONS) { finish(); return }
        val args = intent.getBundleExtra("bundle") ?: intent.extras
        val uid = args?.getInt("uid", -1) ?: -1
        val listener = args?.getBinder("request_permission_binder")
        // The SDK's exported activity extras are advisory. Identity and consent
        // come from the recent authenticated Binder request and this local UI.
        val client = DhizukuAuthorization.pending(this, uid)
        val role = DeviceOwnerPolicies(this).role
        if (client == null || role == null) {
            respond(listener, false)
            finish()
            return
        }
        window.decorView.filterTouchesWhenObscured = true
        if (Build.VERSION.SDK_INT >= 31) window.setHideOverlayWindows(true)
        setContent {
            MaterialTheme {
                var error by remember { mutableStateOf<String?>(null) }
                val protect = rememberProtectedAction(DeviceProtection.AppPermissions) { error = it }
                fun complete(allowed: Boolean) {
                    respond(listener, allowed)
                    finish()
                }
                AlertDialog(
                    onDismissRequest = { complete(false) },
                    title = { Text("Allow Dhizuku package access?") },
                    text = {
                        ProtectPermissionDialog()
                        Column {
                            Text(client.packageName())
                            Text("Android ${role.label} can let this app hide and unhide apps through device policy.")
                            Text("Certificate ${client.members().getValue(client.packageName()).first()}")
                            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            protect {
                                runCatching { DhizukuAuthorization.approve(this, client, role) }
                                    .onSuccess { complete(true) }
                                    .onFailure { error = it.message ?: "Could not save Dhizuku access." }
                            }
                        }) { Text("Allow package access") }
                    },
                    dismissButton = { TextButton(onClick = { complete(false) }) { Text("Deny") } },
                )
            }
        }
    }

    private fun respond(listener: IBinder?, granted: Boolean) {
        if (listener == null) return
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken("com.rosan.dhizuku.aidl.IDhizukuRequestPermissionListener")
            data.writeInt(if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED)
            runCatching { listener.transact(IBinder.FIRST_CALL_TRANSACTION, data, null, IBinder.FLAG_ONEWAY) }
        } finally { data.recycle() }
    }

    private companion object {
        val ACTIONS = setOf(
            "com.rosan.dhizuku.action.request.permission",
            "io.github.stupidgiraffe.kizuku.action.REQUEST_DHIZUKU_PERMISSION",
        )
    }
}
