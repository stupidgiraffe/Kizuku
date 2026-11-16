package io.github.stupidgiraffe.kizuku

import android.app.NotificationManager
import android.app.RemoteInput
import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stupidgiraffe.kizuku.api.AndroidIdentity
import io.github.stupidgiraffe.kizuku.core.GrantLedger
import io.github.stupidgiraffe.kizuku.core.Capability
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Runs in the manager UID. Host ADB never pairs or supplies the manager's private key. */
@RunWith(AndroidJUnit4::class)
class AdbActivationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val app = instrumentation.targetContext
    private val controller get() = ActivationController.get(app)
    private val client = "io.github.stupidgiraffe.kizuku.testclient"

    @Test fun exerciseAppActivation() {
        val scenario = InstrumentationRegistry.getArguments().getString("scenario")
        val accessibility = automation.serviceInfo
        accessibility.flags = accessibility.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        automation.serviceInfo = accessibility
        openManager()
        when (scenario) {
            "apps" -> exerciseAppPermissions()
            "denied" -> {
                main { controller.startTcp("127.0.0.1", 37055, false) }
                await("denied local-network request") { !controller.state.value.busy && controller.state.value.message?.contains("Local network") == true }
                assertNull(CoreConnection.service)
            }
            "cancel" -> {
                main { controller.startTcp("127.0.0.1", 1, false); controller.cancelActivation() }
                SystemClock.sleep(800)
                assertFalse(controller.state.value.busy)
                assertEquals("Cancelled", controller.adb.value.stage)
                assertNull(CoreConnection.service)
            }
            "tcp" -> {
                main { controller.startTcp("127.0.0.1", 37055, false) }
                await("custom-port ADB core", 45000) {
                    findText("Allow")?.let(::click)
                    CoreConnection.service != null
                }
                await("finished TCP activation") { !controller.state.value.busy }
                main { controller.discoverPairing(); controller.cancelActivation(); CoreConnection.changed() }
                SystemClock.sleep(800)
                assertNotNull("Cancelling a new pairing search preserves the connected core", CoreConnection.service)
                assertConnectedAndStop()
            }
            "pair" -> pairThroughNotification()
            "assisted" -> pairThroughAccessibility()
            "reconnect" -> {
                assertTrue("pairing survived a manager process restart", controller.adb.value.paired)
                main { controller.startWireless() }
                await("paired wireless reconnect", 45000) { CoreConnection.service != null }
                assertConnectedAndStop()
            }
            else -> error("Unknown ADB acceptance scenario")
        }
    }
    private fun exerciseAppPermissions() {
        await("installed compatible client") { controller.state.value.apps.any { it.getString("package") == client } }
        assertNull(CoreConnection.service)
        val both = Capability.SHIZUKU or Capability.AXERON
        assertEquals("Both manifest contracts are detected independently", both, row().getLong("manifest_scopes") and both)
        clickText("Apps")
        val eligible = controller.state.value.apps.count { it.getLong("manifest_scopes") and Capability.SHIZUKU != 0L }
        assertTrue(eligible > 0)
        clickText("All ON ($eligible)")
        clickText("Cancel")
        clickProbeBinderSwitch(checked = false)
        clickText("Remember this certificate")
        await("remembered Binder grant") { row().getLong("granted") == Capability.SHIZUKU }
        clickProbeBinderSwitch(checked = true)
        await("revoked offline grant") { row().getLong("granted") == 0L }

        val nativeOnly = instrumentation.context.packageName
        @Suppress("DEPRECATION")
        val nativeUid = app.packageManager.getApplicationInfo(nativeOnly, 0).uid
        val nativeIdentity = AndroidIdentity.resolve(app.packageManager, nativeUid, nativeOnly)
        val permanent = GrantLedger.Lifetime.PERMANENT.ordinal
        AuthorizationStore.replaceRemembered(app, nativeIdentity, Capability.DIAGNOSTICS, Capability.DIAGNOSTICS, AuthorizationStore.ADB_UID)
        try {
            main { controller.decide(row(), Capability.AXERON, permanent) }
            await("separate Axeron grant and diagnostics-only control") {
                row().getLong("granted") == Capability.AXERON &&
                    controller.state.value.apps.any { it.getString("package") == nativeOnly && it.getLong("granted") == Capability.DIAGNOSTICS }
            }

            clickText("All ON ($eligible)", AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            await("confirmation identifies the target package", 8000) {
                val visible = nodes()
                val targetCount = if (eligible == 1) "1 app" else "$eligible apps"
                if (visible.none { it.hasLabel("Grant Shizuku to $targetCount?") }) return@await false
                if (visible.any { it.hasLabel(client) && it.isVisibleToUser }) return@await true
                visible.firstOrNull { node ->
                    node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD }
                }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                false
            }
            clickText("Cancel")
            assertEquals("Dismissing confirmation preserves the current grants", Capability.AXERON, row().getLong("granted"))

            clickText("All ON ($eligible)")
            clickText("Grant Shizuku")
            await("offline bulk grant") {
                row().getLong("granted") == both && controller.shizukuBatch.value.result?.action == ShizukuBatchAction.GRANT
            }
            assertEquals("Bulk grant requires the installed Shizuku declaration", Capability.DIAGNOSTICS,
                controller.state.value.apps.first { it.getString("package") == nativeOnly }.getLong("granted"))

            clickText("All OFF ($eligible)")
            clickText("Revoke Shizuku")
            await("offline bulk revoke preserves Axeron") {
                row().getLong("granted") == Capability.AXERON && controller.shizukuBatch.value.result?.action == ShizukuBatchAction.REVOKE
            }
            assertTrue(requireNotNull(controller.shizukuBatch.value.result).failures.isEmpty())

            val stale = Bundle(row()).apply { putString("certificate", "stale-certificate") }
            val nativeRow = controller.state.value.apps.first { it.getString("package") == nativeOnly }
            main { controller.changeAllShizuku(ShizukuBatchAction.GRANT, listOf(stale, nativeRow, row())) }
            await("valid decisions succeed alongside stale and ineligible targets") {
                controller.shizukuBatch.value.result?.failures?.map { it.first } == listOf(client, nativeOnly)
            }
            val rejected = requireNotNull(controller.shizukuBatch.value.result)
            assertEquals(1, rejected.offlineUpdates + rejected.coreUpdates)
            assertEquals(both, row().getLong("granted"))
            if (eligible > 1) {
                await("mixed grant indication") { findText("Mixed · 1 of $eligible apps have access") != null }
            }
            clickText("Review affected apps", AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            clickText("Close")
        } finally {
            AuthorizationStore.replaceRemembered(app, nativeIdentity, Capability.DIAGNOSTICS, 0L, AuthorizationStore.ADB_UID)
            main { controller.decide(row(), both, null) }
            await("probe grants cleared") { row().getLong("granted") == 0L }
        }
    }

    private fun pairThroughNotification() {
        main { app.startForegroundService(Intent(app, AdbPairingService::class.java).setAction(AdbPairingService.BEGIN)) }
        openPairingDialog()
        var code = ""
        await("Android pairing dialog") {
            code = nodes().firstOrNull { it.viewIdResourceName == "com.android.settings:id/pairing_code" }?.text?.toString().orEmpty()
            code.matches(Regex("[0-9]{6}"))
        }
        await("pairing service discovery", 30000) { controller.adb.value.stage == "Waiting for code" }
        val notifications = app.getSystemService(NotificationManager::class.java)
        var action: android.app.Notification.Action? = null
        await("notification reply action") {
            action = notifications.activeNotifications.firstOrNull { it.id == AdbPairingService.ID }?.notification?.actions
                ?.firstOrNull { it.remoteInputs?.any { input -> input.resultKey == AdbPairingService.CODE } == true }
            action != null
        }
        // Keep Settings resumed: leaving it dismisses the system's pairing server.
        val reply = Intent()
        RemoteInput.addResultsToIntent(requireNotNull(action).remoteInputs, reply, Bundle().apply { putCharSequence(AdbPairingService.CODE, code) })
        requireNotNull(action).actionIntent.send(app, 0, reply)
        code = "" // No UI dumps, screenshots, test arguments or logs contain the code.
        await("wireless paired core", 60000) { CoreConnection.service != null }
        assertTrue(controller.adb.value.paired)
        assertConnectedAndStop()
        openManager()
    }

    private fun pairThroughAccessibility() {
        val component = "${app.packageName}/${PairingAccessibilityService::class.java.name}"
        fun enabled() = Settings.Secure.getString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')?.contains(component) == true
        fun shell(command: String) {
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
                .use { it.readBytes() }
        }

        assertFalse("A fresh manager has no pairing key", controller.adb.value.paired)
        assertFalse("Accessibility is disabled before opt-in", enabled())
        clickText("ADB options")
        clickText("Pair automatically", AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        clickText("Open Accessibility")
        await("one requested accessibility pairing session") { AdbPairingService.assistedSession }
        // A disposable test emulator approves the restricted Settings toggle as shell.
        // Kizuku must navigate the actual Settings hierarchy and read the Android code.
        shell("settings put secure enabled_accessibility_services $component")
        shell("settings put secure accessibility_enabled 1")
        try {
            await("wireless core started by automatic pairing", 60000, diagnostics = {
                "stage=${controller.adb.value.stage}; Settings view IDs=" +
                    nodes().filter { it.packageName?.toString() == "com.android.settings" }
                        .mapNotNull { it.viewIdResourceName }.distinct().take(35)
            }) { CoreConnection.service != null }
            assertTrue(controller.adb.value.paired)
            await("accessibility disabled after use") { !enabled() }
            assertConnectedAndStop()
            openManager()
        } finally {
            shell("settings delete secure enabled_accessibility_services")
            shell("settings put secure accessibility_enabled 0")
        }
    }

    private fun openPairingDialog() {
        val settings = wirelessDebuggingSettingsIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        app.startActivity(settings)
        var nextAction = 0L
        var leftSettings = false
        await("enabled Wireless debugging and pairing dialog", 35000, diagnostics = {
            // Exclude node text, since a Settings dialog can contain a pairing code.
            nodes().take(25).joinToString("\n") {
                "${it.packageName} ${it.viewIdResourceName} enabled=${it.isEnabled} checked=${it.isChecked}"
            }
        }) {
            val visible = nodes()
            val now = SystemClock.elapsedRealtime()
            if (now < nextAction) return@await false
            val settingsNodes = visible.filter { it.packageName?.toString() == "com.android.settings" }
            val systemNodes = visible.filter { it.packageName?.toString() == "com.android.systemui" }
            val trustPrompt = systemNodes.any { it.hasLabel("Allow wireless debugging on this network?") }
            if (trustPrompt) {
                systemNodes.firstOrNull { it.isEnabled && it.hasLabel("Allow") }?.let {
                    if (tryClick(it)) nextAction = now + 1800
                }
                return@await false
            }
            val pair = settingsNodes.firstOrNull {
                it.isEnabled && it.isVisibleToUser && it.hasLabel("Pair device with pairing code")
            }
            if (pair != null && tryClick(pair)) return@await true
            val switch = settingsNodes.firstOrNull { it.hasLabel("Use wireless debugging") }
            val developerEntry = settingsNodes.firstOrNull { it.isEnabled && it.hasLabel("Wireless debugging") }
            when {
                switch != null -> {
                    val toggle = settingsNodes.firstOrNull {
                        it.viewIdResourceName == "android:id/switch_widget" && it.isCheckable && it.isEnabled
                    }
                    if (toggle?.isChecked == false) {
                        if (tryClick(toggle)) {
                            // Android can temporarily reset the switch while it opens the trust prompt.
                            nextAction = now + 2500
                        }
                    } else {
                        // The enabled switch is pinned above the scrollable device and pairing list.
                        scroll(settingsNodes, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    }
                }
                developerEntry != null -> {
                    if (tryClick(developerEntry)) nextAction = now + 1000
                }
                settingsNodes.isNotEmpty() -> scroll(settingsNodes, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                !leftSettings && visible.any { it.packageName?.toString() == "com.google.android.apps.nexuslauncher" } -> {
                    leftSettings = true
                    app.startActivity(settings)
                    nextAction = now + 1500
                }
            }
            false
        }
    }
    private fun assertConnectedAndStop() {
        assertEquals(2000, requireNotNull(CoreConnection.service).status().getInt("uid"))
        await("honest connected state") { controller.state.value.phase == "Connected" && !controller.state.value.busy }
        main { controller.stop() }
        await("stopped Binder") { CoreConnection.service == null }
        await("honest stopped state") { controller.state.value.status == null && !controller.state.value.busy }
    }
    private fun clickProbeBinderSwitch(checked: Boolean) {
        val tag = "capability:$client:${Capability.SHIZUKU}"
        await("probe Binder switch (checked=$checked)", diagnostics = {
            nodes().take(80).joinToString("\n") { node ->
                "${node.className}: text=${node.text}, description=${node.contentDescription}, " +
                    "tag=${node.viewIdResourceName}, enabled=${node.isEnabled}, " +
                    "checkable=${node.isCheckable}, checked=${node.isChecked}"
            }
        }) {
            val visible = nodes()
            val node = visible.firstOrNull { it.viewIdResourceName == tag && it.isVisibleToUser }
            if (node == null) {
                scroll(visible, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                return@await false
            }
            if (!node.isEnabled || node.isChecked != checked) return@await false
            click(node)
            true
        }
    }
    private fun row() = controller.state.value.apps.first { it.getString("package") == client }
    private fun openManager() {
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)
    }
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(
        label: String,
        milliseconds: Long = 15000,
        diagnostics: () -> String = { "" },
        condition: () -> Boolean,
    ) {
        val end = SystemClock.elapsedRealtime() + milliseconds
        while (SystemClock.elapsedRealtime() < end) {
            if (condition()) return
            SystemClock.sleep(150)
        }
        fail("Timed out: $label; core=${controller.state.value.phase}; busy=${controller.state.value.busy}; adb=${controller.adb.value.stage}; detail=${controller.state.value.message}\n${diagnostics()}")
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        val root = automation.rootInActiveWindow ?: return emptyList()
        val result = ArrayList<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            result.add(node)
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
        }
        visit(root)
        return result
    }
    private fun AccessibilityNodeInfo.hasLabel(label: String) =
        text?.toString() == label || contentDescription?.toString() == label

    private fun findText(text: String) = nodes().firstOrNull { it.hasLabel(text) }
    private fun click(node: AccessibilityNodeInfo) {
        check(tryClick(node)) { "UI control was not clickable" }
        SystemClock.sleep(300)
    }

    private fun tryClick(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        while (target != null) {
            if (target.isVisibleToUser && target.isEnabled && target.isClickable &&
                target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            target = target.parent
        }
        return false
    }
    private fun scroll(visible: List<AccessibilityNodeInfo>, action: Int) {
        visible.firstOrNull { node ->
            node.isVisibleToUser && node.actionList.any { it.id == action }
        }?.performAction(action)
    }

    private fun clickText(text: String, scrollAction: Int? = null) {
        await("UI control $text", 8000) {
            val visible = nodes()
            val node = visible.firstOrNull { it.hasLabel(text) && it.isEnabled && it.isVisibleToUser }
            if (node == null) {
                scrollAction?.let { scroll(visible, it) }
                return@await false
            }
            tryClick(node)
        }
    }
}
