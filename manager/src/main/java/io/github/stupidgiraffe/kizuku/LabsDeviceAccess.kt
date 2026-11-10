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

private const val PROTECT_LABS = "protect_labs"
private enum class AuthAction { Unlock, Enable, Disable }
private class AuthAttempt(val action: AuthAction)

/** A foreground UI lock. Android and the host independently enforce privileges. */
@Composable
internal fun LabsDeviceAccess(active: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("labs", Context.MODE_PRIVATE) }
    val keyguard = remember(context) { context.getSystemService(KeyguardManager::class.java) }
    val currentActive by rememberUpdatedState(active)
    var protectionEnabled by remember(preferences) { mutableStateOf(preferences.getBoolean(PROTECT_LABS, false)) }
    var available by remember(context) { mutableStateOf(deviceAuthenticationAvailable(context, keyguard)) }
    var unlocked by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<AuthAttempt?>(null) }
    var legacyAttempt by remember { mutableStateOf<AuthAttempt?>(null) }
    var signal by remember { mutableStateOf<CancellationSignal?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }

    fun completeAuthentication(attempt: AuthAttempt, success: Boolean) {
        if (pending !== attempt) return
        pending = null
        signal = null
        if (!currentActive) return
        if (!success) {
            feedback = "Device authentication did not complete. Try again."
            return
        }
        if (attempt.action != AuthAction.Unlock) {
            protectionEnabled = attempt.action == AuthAction.Enable
            preferences.edit().putBoolean(PROTECT_LABS, protectionEnabled).apply()
        }
        unlocked = true
        feedback = null
    }

    val credentialLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val attempt = legacyAttempt
        legacyAttempt = null
        if (attempt != null) completeAuthentication(attempt, it.resultCode == Activity.RESULT_OK)
    }

    @Suppress("DEPRECATION") // Android 8–10 use the device credential activity.
    fun authenticate(action: AuthAction) {
        if (!currentActive || pending != null || legacyAttempt != null) return
        available = deviceAuthenticationAvailable(context, keyguard)
        if (!available) {
            feedback = "Set a PIN, pattern or password in Android Settings first."
            return
        }
        feedback = null
        val attempt = AuthAttempt(action)
        pending = attempt
        val title = when (action) {
            AuthAction.Unlock -> "Unlock Labs tools"
            AuthAction.Enable -> "Enable Labs protection"
            AuthAction.Disable -> "Disable Labs protection"
        }
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val cancellation = CancellationSignal()
                signal = cancellation
                showDevicePrompt(context, title, cancellation) { completeAuthentication(attempt, it) }
            } else {
                val intent = keyguard?.createConfirmDeviceCredentialIntent(title, "Confirm your Android screen lock")
                if (intent == null) completeAuthentication(attempt, false) else {
                    legacyAttempt = attempt
                    credentialLauncher.launch(intent)
                }
            }
        } catch (_: RuntimeException) {
            legacyAttempt = null
            completeAuthentication(attempt, false)
        }
    }

    fun resetVisit() {
        pending = null
        signal?.cancel()
        signal = null
        unlocked = false
        feedback = null
    }

    LaunchedEffect(active) { if (!active) resetVisit() }
    DisposableEffect(Unit) { onDispose { resetVisit() } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        available = deviceAuthenticationAvailable(context, keyguard)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { unlocked = false }

    if (!active) return
    if (!protectionEnabled || unlocked) content()
    LabsPanel("Device authentication") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Protect Labs tools", style = MaterialTheme.typography.titleSmall)
                LabsDetail(if (Build.VERSION.SDK_INT >= 30) {
                    "Require your Android screen lock or a strong biometric. Labs locks again when you leave."
                } else "Require your Android screen lock. Labs locks again when you leave.")
            }
            Switch(
                checked = protectionEnabled,
                enabled = pending == null && legacyAttempt == null,
                onCheckedChange = { authenticate(if (it) AuthAction.Enable else AuthAction.Disable) },
                modifier = Modifier.semantics { contentDescription = "Protect Labs tools" },
            )
        }
        if (protectionEnabled && !unlocked) {
            Button(onClick = { authenticate(AuthAction.Unlock) }, enabled = pending == null && legacyAttempt == null && available) {
                Text("Unlock Labs tools")
            }
        }
        if (!available) {
            LabsDetail("Set a PIN, pattern or password to use this protection.")
            TextButton(onClick = {
                if (!openLabsSettings(context, Settings.ACTION_SECURITY_SETTINGS)) {
                    feedback = "Android security settings could not be opened."
                }
            }) { Text("Open Android security settings") }
        }
        feedback?.let { LabsError(it) }
    }
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
