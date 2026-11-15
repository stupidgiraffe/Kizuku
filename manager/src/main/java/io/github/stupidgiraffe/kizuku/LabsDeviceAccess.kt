package io.github.stupidgiraffe.kizuku

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

internal enum class DeviceProtection(val key: String, val promptTitle: String) {
    Labs("protect_labs", "Unlock Labs tools"),
    AppPermissions("protect_app_permissions", "Confirm app permission change"),
    Companion("protect_companion", "Confirm companion change"),
}

private fun protectionPreferences(context: Context) = context.getSharedPreferences("labs", Context.MODE_PRIVATE)

/** A device prompt at the point of use. Each protected operation requires its own authentication. */
@Composable
internal fun rememberProtectedAction(scope: DeviceProtection, onFailure: (String) -> Unit): (() -> Unit) -> Unit {
    val preferences = protectionPreferences(LocalContext.current)
    val auth = rememberDeviceAuthentication(onFailure)
    return { action ->
        if (preferences.getBoolean(scope.key, false)) auth.request(scope.promptTitle, action)
        else action()
    }
}

@Composable
internal fun DeviceProtectionSettings() {
    val context = LocalContext.current
    val preferences = remember(context) { protectionPreferences(context) }
    var permissions by remember(preferences) { mutableStateOf(preferences.getBoolean(DeviceProtection.AppPermissions.key, false)) }
    var companion by remember(preferences) { mutableStateOf(preferences.getBoolean(DeviceProtection.Companion.key, false)) }
    var feedback by remember { mutableStateOf<String?>(null) }
    val auth = rememberDeviceAuthentication { feedback = it }
    fun change(scope: DeviceProtection, label: String, enabled: Boolean, committed: () -> Unit) {
        feedback = null
        auth.request(if (enabled) "Protect $label" else "Stop protecting $label") {
            preferences.edit().putBoolean(scope.key, enabled).apply()
            committed()
        }
    }

    LabsPanel("Device authentication") {
        LabsDetail(if (Build.VERSION.SDK_INT >= 30) {
            "Require your Android screen lock or a strong biometric for selected changes. Labs has its own lock."
        } else "Require your Android screen lock for selected changes. Labs has its own lock.")
        ProtectionSwitch("Protect app permissions", "Require authentication when granting or revoking Shizuku, Axeron or diagnostics access.",
            permissions, !auth.busy) { enabled ->
            change(DeviceProtection.AppPermissions, "app permissions", enabled) { permissions = enabled }
        }
        ProtectionSwitch("Protect companion management", "Require authentication before installing or removing the compatibility companion.",
            companion, !auth.busy) { enabled ->
            change(DeviceProtection.Companion, "companion management", enabled) { companion = enabled }
        }
        if (!auth.available) DeviceAuthenticationSettingsLink(context) { feedback = it }
        feedback?.let { LabsError(it) }
    }
}

/** Labs has a foreground visit lock. Android and the host independently enforce privileges. */
@Composable
internal fun LabsDeviceAccess(active: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val preferences = remember(context) { protectionPreferences(context) }
    val currentActive by rememberUpdatedState(active)
    var protectionEnabled by remember(preferences) { mutableStateOf(preferences.getBoolean(DeviceProtection.Labs.key, false)) }
    var unlocked by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    val auth = rememberDeviceAuthentication { feedback = it }

    fun authenticate(title: String, onSuccess: () -> Unit) {
        feedback = null
        auth.request(title) {
            if (currentActive) {
                onSuccess()
                unlocked = true
            }
        }
    }

    LaunchedEffect(active) {
        if (!active) {
            auth.cancel()
            unlocked = false
            feedback = null
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { unlocked = false }

    if (!active) return
    LabsPanel("Device authentication") {
        ProtectionSwitch("Protect Labs tools", "Require device authentication to open Labs. Labs locks again when you leave.",
            protectionEnabled, !auth.busy) { enabled ->
            authenticate(if (enabled) "Enable Labs protection" else "Disable Labs protection") {
                preferences.edit().putBoolean(DeviceProtection.Labs.key, enabled).apply()
                protectionEnabled = enabled
            }
        }
        if (protectionEnabled && !unlocked) {
            Button(
                onClick = { authenticate(DeviceProtection.Labs.promptTitle) {} },
                enabled = !auth.busy && auth.available,
            ) { Text("Unlock Labs tools") }
        }
        if (!auth.available) DeviceAuthenticationSettingsLink(context) { feedback = it }
        feedback?.let { LabsError(it) }
    }
    if (!protectionEnabled || unlocked) content()
}

@Composable
private fun ProtectionSwitch(label: String, detail: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            LabsDetail(detail)
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = change,
            modifier = Modifier.semantics { contentDescription = label })
    }
}

@Composable
private fun DeviceAuthenticationSettingsLink(context: Context, onFailure: (String) -> Unit) {
    LabsDetail("Set a PIN, pattern or password to use device authentication.")
    TextButton(onClick = {
        if (!openLabsSettings(context, Settings.ACTION_SECURITY_SETTINGS)) {
            onFailure("Android security settings could not be opened.")
        }
    }) { Text("Open Android security settings") }
}

private class DeviceAuthentication(
    val available: Boolean,
    val busy: Boolean,
    val request: (String, () -> Unit) -> Unit,
    val cancel: () -> Unit,
)

private class AuthAttempt(val onSuccess: () -> Unit)

@Composable
@Suppress("DEPRECATION") // Android 8–10 use the device credential activity.
private fun rememberDeviceAuthentication(onFailure: (String) -> Unit): DeviceAuthentication {
    val context = LocalContext.current
    val keyguard = remember(context) { context.getSystemService(KeyguardManager::class.java) }
    val reportFailure by rememberUpdatedState(onFailure)
    var available by remember(context) { mutableStateOf(deviceAuthenticationAvailable(context, keyguard)) }
    var pending by remember { mutableStateOf<AuthAttempt?>(null) }
    var legacyAttempt by remember { mutableStateOf<AuthAttempt?>(null) }
    var signal by remember { mutableStateOf<CancellationSignal?>(null) }

    fun complete(attempt: AuthAttempt, success: Boolean) {
        if (pending !== attempt) return
        pending = null
        signal = null
        if (success) attempt.onSuccess()
        else reportFailure("Device authentication did not complete. Try again.")
    }

    val credentialLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val attempt = legacyAttempt
        legacyAttempt = null
        if (attempt != null) complete(attempt, it.resultCode == Activity.RESULT_OK)
    }

    fun request(title: String, onSuccess: () -> Unit) {
        if (pending != null || legacyAttempt != null) return
        available = deviceAuthenticationAvailable(context, keyguard)
        if (!available) {
            reportFailure("Device authentication is unavailable. Set a PIN, pattern or password in Android Settings.")
            return
        }
        val attempt = AuthAttempt(onSuccess)
        pending = attempt
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val cancellation = CancellationSignal()
                signal = cancellation
                showDevicePrompt(context, title, cancellation) { complete(attempt, it) }
            } else {
                val intent = keyguard?.createConfirmDeviceCredentialIntent(title, "Confirm your Android screen lock")
                if (intent == null) complete(attempt, false) else {
                    legacyAttempt = attempt
                    credentialLauncher.launch(intent)
                }
            }
        } catch (_: RuntimeException) {
            legacyAttempt = null
            complete(attempt, false)
        }
    }

    fun cancel() {
        pending = null
        signal?.cancel()
        signal = null
    }

    DisposableEffect(Unit) { onDispose { cancel() } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        available = deviceAuthenticationAvailable(context, keyguard)
    }
    return DeviceAuthentication(available, pending != null || legacyAttempt != null, ::request, ::cancel)
}

private fun deviceAuthenticationAvailable(context: Context, keyguard: KeyguardManager?): Boolean =
    if (Build.VERSION.SDK_INT >= 30) {
        context.getSystemService(BiometricManager::class.java)?.canAuthenticate(authenticators()) == BiometricManager.BIOMETRIC_SUCCESS
    } else keyguard?.isDeviceSecure == true

@RequiresApi(30)
private fun authenticators() = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL

@RequiresApi(30)
private fun showDevicePrompt(context: Context, title: String, cancellation: CancellationSignal, complete: (Boolean) -> Unit) {
    BiometricPrompt.Builder(context)
        .setTitle(title)
        .setAllowedAuthenticators(authenticators())
        .build()
        .authenticate(cancellation, context.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = complete(true)
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = complete(false)
        })
}

internal fun openLabsSettings(context: Context, action: String): Boolean =
    runCatching { context.startActivity(Intent(action)) }.isSuccess
