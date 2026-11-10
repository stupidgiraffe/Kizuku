package io.github.stupidgiraffe.kizuku

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.annotation.MainThread
import io.github.stupidgiraffe.kizuku.adb.AdbDiscovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class DiagnosticCheck(val label: String, val evidence: String)

/** Read-only observations from Android and the authenticated host. */
@MainThread
internal suspend fun runServiceDoctor(context: Context, controller: ActivationController): List<DiagnosticCheck> {
    val core = controller.state.value
    val adb = controller.adb.value
    val recovery = controller.recoveryDiagnostics()
    return withContext(Dispatchers.IO) {
        val host = CoreConnection.service
        val status = runCatching { host?.status() }.getOrNull()
        val notifications = context.getSystemService(NotificationManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        val activities = context.getSystemService(ActivityManager::class.java)
        val pairingChannel = notifications?.getNotificationChannel(AdbPairingService.CHANNEL)
        val localNetwork = when {
            Build.VERSION.SDK_INT < 37 -> "Permission required from Android 17"
            runCatching { AdbDiscovery.checkLocalNetworkPermission(context) }.isSuccess -> "Allowed"
            else -> "Denied in Kizuku’s app permissions"
        }
        val wireless = when {
            Build.VERSION.SDK_INT < 30 -> "Requires Android 11 or later"
            else -> when (runCatching { Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) }.getOrNull()) {
                0 -> "Off in Android Settings"
                1 -> "On in Android Settings"
                else -> "Could not read Android’s setting"
            }
        }
        buildList {
            add(DiagnosticCheck("Host Binder", when {
                status != null -> "Responded"
                host != null -> "Did not respond"
                else -> "No host attached"
            }))
            if (status != null) {
                val transport = status.getString("transport")
                val uid = status.getInt("uid")
                val pid = status.getInt("pid")
                val protocol = status.getInt("protocol")
                add(DiagnosticCheck("Host identity", "$transport · UID $uid · PID $pid · Protocol $protocol"))
                add(DiagnosticCheck("Host uptime", "${status.getLong("uptime_ms") / 1000} seconds"))
                add(DiagnosticCheck("Safe mode", if (status.getBoolean("safe_mode")) "On" else "Off"))
            }
            add(DiagnosticCheck("Last controller state", core.phase))
            add(DiagnosticCheck("Automatic recovery", when {
                !recovery.enabled -> "Off"
                !recovery.wanted -> "On · no automatic restart requested"
                !recovery.transportEligible -> "On · eligible startup transport unavailable"
                status != null -> "Monitoring this connection"
                recovery.retryInProgress -> "Waiting for or attempting a retry"
                else -> "On · no retry pending"
            }))
            add(DiagnosticCheck("Wireless debugging", wireless))
            add(DiagnosticCheck("Saved wireless pairing", if (adb.paired) "Present on this device" else "No successful pairing saved"))
            add(DiagnosticCheck("Local network access", localNetwork))
            add(DiagnosticCheck("Pairing notifications", when {
                notifications == null -> "Status unavailable"
                !notifications.areNotificationsEnabled() -> "Disabled for Kizuku"
                pairingChannel?.importance == NotificationManager.IMPORTANCE_NONE -> "ADB pairing channel blocked"
                else -> "Allowed"
            }))
            add(DiagnosticCheck("Battery optimization", when (power?.isIgnoringBatteryOptimizations(context.packageName)) {
                true -> "Kizuku is exempt"
                false -> "Kizuku is subject to optimization"
                null -> "Status unavailable"
            }))
            if (Build.VERSION.SDK_INT >= 28) {
                add(DiagnosticCheck("Background activity", when (activities?.isBackgroundRestricted) {
                    true -> "Restricted by Android"
                    false -> "Allowed by Android"
                    null -> "Status unavailable"
                }))
            }
            add(DiagnosticCheck("Android device", "${Build.MANUFACTURER} ${Build.MODEL} · API ${Build.VERSION.SDK_INT}"))
        }
    }
}

internal data class AccessibilityEntry(val label: String, val component: String, val enabled: Boolean)

internal fun readAccessibilityInventory(context: Context): List<AccessibilityEntry> {
    val manager = checkNotNull(context.getSystemService(AccessibilityManager::class.java))
    // Android's enabled-services API returns bound services. The setting also
    // preserves the user's choice during service startup or temporary suppression.
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        .orEmpty().split(':').mapNotNull(ComponentName::unflattenFromString).toSet()
    return manager.installedAccessibilityServiceList.map { info ->
        val service = info.resolveInfo.serviceInfo
        val component = ComponentName(service.packageName, service.name)
        AccessibilityEntry(info.resolveInfo.loadLabel(context.packageManager).toString(), component.flattenToShortString(), component in enabled)
    }.sortedWith(compareBy({ !it.enabled }, { it.label }, { it.component }))
}
