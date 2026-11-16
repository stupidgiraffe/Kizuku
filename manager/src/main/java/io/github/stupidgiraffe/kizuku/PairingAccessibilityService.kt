package io.github.stupidgiraffe.kizuku

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Reads the active Android Settings window only during a user-started pairing attempt. */
class PairingAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var developerRowOpened = false
    private var pairingRowOpened = false
    private var nextNavigationAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (!AdbPairingService.assistedSession) {
            disableSelf()
            return
        }
        val controller = ActivationController.get(this)
        val settings = wirelessDebuggingSettingsIntent().setPackage(SETTINGS_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { startActivity(settings) }.isFailure) {
            disableSelf()
            return
        }
        val wirelessTitle = settingsLabel("adb_wireless", "Wireless debugging")
        val pairTitle = settingsLabel("adb_wireless_pairing_code", "Pair device with pairing code")
        scope.launch {
            try {
                while (isActive && AdbPairingService.assistedSession) {
                    val stage = controller.adb.value
                    if (stage.stage in TERMINAL_STAGES) break
                    val root = rootInActiveWindow?.takeIf { it.packageName?.toString() == SETTINGS_PACKAGE }
                    if (root != null) {
                        val code = root.findAccessibilityNodeInfosByViewId(PAIRING_CODE_ID)
                            .firstOrNull { it.isVisibleToUser }?.text?.toString()
                            ?.takeIf { SIX_DIGITS.matches(it) }
                        val endpoint = stage.pairing
                        if (code != null && stage.stage == "Waiting for code" && endpoint != null) {
                            if (controller.pair(endpoint.host, endpoint.port, code) != null) break
                        } else if (code == null && !pairingRowOpened) {
                            navigate(root, wirelessTitle, pairTitle)
                        }
                    }
                    delay(450)
                }
            } finally {
                disableSelf()
            }
        }
    }

    private fun navigate(root: AccessibilityNodeInfo, wirelessTitle: String, pairTitle: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now < nextNavigationAt) return
        if (clickTitle(root, pairTitle)) {
            pairingRowOpened = true
            return
        }
        // Android uses this fragment for the wireless screen. A wireless title in
        // its toolbar is not a preference and should never be clicked.
        val wirelessEnabled = Settings.Global.getInt(contentResolver, "adb_wifi_enabled", 0) == 1
        if (wirelessEnabled && root.findAccessibilityNodeInfosByText(wirelessTitle).any {
                it.isVisibleToUser && it.viewIdResourceName == "com.android.settings:id/action_bar_title"
            }) {
            // The pairing preference can be below the fold once devices are paired.
            root.findAccessibilityNodeInfosByViewId("com.android.settings:id/recycler_view")
                .firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            nextNavigationAt = now + 900
            return
        }
        if (!developerRowOpened && clickTitle(root, wirelessTitle)) {
            developerRowOpened = true
            nextNavigationAt = now + 900
        }
    }

    private fun clickTitle(root: AccessibilityNodeInfo, title: String): Boolean {
        val node = root.findAccessibilityNodeInfosByText(title).firstOrNull {
            it.text?.toString() == title && it.isVisibleToUser && it.viewIdResourceName == "android:id/title"
        } ?: return false
        var candidate: AccessibilityNodeInfo? = node
        repeat(4) {
            val current = candidate ?: return false
            if (current.isCheckable || !current.isEnabled || current.packageName?.toString() != SETTINGS_PACKAGE) return false
            if (current.isClickable) return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            candidate = current.parent
        }
        return false
    }

    @SuppressLint("DiscouragedApi")
    private fun settingsLabel(name: String, fallback: String): String = runCatching {
        val resources = packageManager.getResourcesForApplication(SETTINGS_PACKAGE)
        resources.getString(resources.getIdentifier(name, "string", SETTINGS_PACKAGE))
    }.getOrDefault(fallback)

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() { scope.cancel(); disableSelf() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private companion object {
        const val SETTINGS_PACKAGE = "com.android.settings"
        const val PAIRING_CODE_ID = "$SETTINGS_PACKAGE:id/pairing_code"
        val SIX_DIGITS = Regex("[0-9]{6}")
        val TERMINAL_STAGES = setOf("Cancelled", "Connected", "Paired", "Failed")
    }
}
