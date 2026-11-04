package io.github.stupidgiraffe.kizuku

import android.app.NotificationManager
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stupidgiraffe.kizuku.core.Capability
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Runs in the manager UID. Host ADB never pairs or supplies the manager's private key. */
@RunWith(AndroidJUnit4::class)
class AdbActivationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private val controller get() = ActivationController.get(app)
    private val client = "io.github.stupidgiraffe.kizuku.testclient"

    @Test fun exerciseAppActivation() {
        val scenario = InstrumentationRegistry.getArguments().getString("scenario")
        val accessibility = instrumentation.uiAutomation.serviceInfo
        accessibility.flags = accessibility.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        instrumentation.uiAutomation.serviceInfo = accessibility
        openManager()
        when (scenario) {
            "apps" -> {
                await("installed compatible client") { controller.state.value.apps.any { it.getString("package") == client } }
                assertNull(CoreConnection.service)
                clickText("Apps")
                clickProbeBinderSwitch(checked = false)
                clickText("Remember this certificate")
                await("remembered Binder grant") { row().getLong("granted") and Capability.SHIZUKU != 0L }
                assertEquals("Binder access never grants native diagnostics", 0L, row().getLong("granted") and Capability.DIAGNOSTICS)
                clickProbeBinderSwitch(checked = true)
                await("revoked offline grant") { row().getLong("granted") == 0L }
            }
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
            "reconnect" -> {
                assertTrue("pairing survived a manager process restart", controller.adb.value.paired)
                main { controller.startWireless() }
                await("paired wireless reconnect", 45000) { CoreConnection.service != null }
                assertConnectedAndStop()
            }
            else -> error("Unknown ADB acceptance scenario")
        }
    }
    private fun pairThroughNotification() {
        main { app.startForegroundService(Intent(app, AdbPairingService::class.java).setAction(AdbPairingService.BEGIN)) }
        app.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        clickText("Wireless debugging", scroll = true)
        // Some images require an explicit trusted-network confirmation on first use.
        findText("Allow")?.let(::click)
        if (findText("Pair device with pairing code") == null) {
            nodes().firstOrNull { it.isCheckable && !it.isChecked && it.isEnabled }?.let(::click)
            findText("Allow")?.let(::click)
        }
        clickText("Pair device with pairing code", scroll = true)
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
    private fun assertConnectedAndStop() {
        assertEquals(2000, requireNotNull(CoreConnection.service).status().getInt("uid"))
        await("honest connected state") { controller.state.value.phase == "Connected" && !controller.state.value.busy }
        main { controller.stop() }
        await("stopped Binder") { CoreConnection.service == null }
        await("honest stopped state") { controller.state.value.status == null && !controller.state.value.busy }
    }
    private fun clickProbeBinderSwitch(checked: Boolean) {
        val label = "Shizuku Binder for Kizuku Probe"
        await("$label switch (checked=$checked)") {
            val node = findText(label) ?: return@await false
            if (!node.isCheckable || !node.isEnabled || node.isChecked != checked) return@await false
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
    private fun await(label: String, milliseconds: Long = 15000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + milliseconds
        while (SystemClock.elapsedRealtime() < end) {
            if (condition()) return
            SystemClock.sleep(150)
        }
        fail("Timed out: $label; core=${controller.state.value.phase}; adb=${controller.adb.value.stage}; detail=${controller.state.value.message}")
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return emptyList()
        val result = ArrayList<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            result.add(node)
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
        }
        visit(root)
        return result
    }
    private fun findText(text: String) = nodes().firstOrNull { it.text?.toString() == text || it.contentDescription?.toString() == text }
    private fun click(node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        check(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { "UI control was not clickable" }
        SystemClock.sleep(300)
    }
    private fun clickText(text: String, scroll: Boolean = false) {
        repeat(if (scroll) 15 else 30) {
            findText(text)?.let { node -> click(node); return }
            if (text != "Allow") findText("Allow")?.let(::click)
            if (scroll) nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(200)
        }
        fail("UI control not found: $text")
    }
}
