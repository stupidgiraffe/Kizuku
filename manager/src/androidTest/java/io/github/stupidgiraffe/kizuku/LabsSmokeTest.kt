package io.github.stupidgiraffe.kizuku

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LabsSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext

    @Test fun serviceDoctorReportsTheCurrentHostAndAndroidSettings() {
        awaitHost()
        val diagnostics = runBlocking(Dispatchers.Main) { runServiceDoctor(app, ActivationController.get(app)) }
        val report = diagnostics.associate { it.label to it.evidence }
        val host = checkNotNull(CoreConnection.service).status()
        assertEquals("Responded", report["Host Binder"])
        assertTrue(report.getValue("Host identity").contains("UID ${host.getInt("uid")}"))
        assertTrue(report.getValue("Host identity").contains("PID ${host.getInt("pid")}"))
        assertTrue(report.containsKey("Automatic recovery"))
        assertTrue(report.containsKey("Pairing notifications"))
        assertTrue(report.containsKey("Battery optimization"))
        assertTrue(report.containsKey("Axeron runtime"))
        assertTrue(formatServiceDoctorReport(diagnostics).contains("Compatibility companion"))
    }

    @Test fun accessibilityInventoryMatchesTheInstalledAndConfiguredServices() {
        fun configured() = Settings.Secure.getString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').mapNotNull(ComponentName::unflattenFromString).toSet()
        val before = configured()
        val installed = app.getSystemService(AccessibilityManager::class.java).installedAccessibilityServiceList
            .map { it.resolveInfo.serviceInfo }
            .map { ComponentName(it.packageName, it.name) }
            .toSet()
        val entries = readAccessibilityInventory(app)
        assertEquals(installed, entries.mapNotNull { ComponentName.unflattenFromString(it.component) }.toSet())
        assertEquals(before intersect installed, entries.filter { it.enabled }.mapNotNull { ComponentName.unflattenFromString(it.component) }.toSet())
        val fixture = ComponentName(instrumentation.context.packageName, FixtureAccessibilityService::class.java.name)
        assertTrue("The test APK declares a real accessibility service", fixture in installed)
        assertFalse(fixture in before)
        awaitHost()
        try {
            runBlocking { setAccessibilityServiceEnabled(app, fixture.flattenToShortString(), true) }
            assertEquals(before + fixture, configured())
            assertTrue(readAccessibilityInventory(app).single { it.component == fixture.flattenToShortString() }.enabled)
        } finally {
            if (fixture in configured()) {
                runBlocking { setAccessibilityServiceEnabled(app, fixture.flattenToShortString(), false) }
            }
        }
        assertEquals(before, configured())
    }

    @Test fun enabledProtectionKeepsToolsHiddenUntilAuthentication() {
        val prefs = app.getSharedPreferences("labs", Context.MODE_PRIVATE)
        val previous = prefs.getBoolean("protect_labs", false)
        assertTrue(prefs.edit().putBoolean("protect_labs", true).commit())
        var activity: Activity? = null
        try {
            activity = instrumentation.startActivitySync(
                Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
            var control = awaitText("Labs")
            while (!control.isClickable) control = checkNotNull(control.parent)
            assertTrue(control.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            awaitText("Unlock Labs tools")
            assertNull(findText("Service Doctor"))
            assertNull(findText("Inspect services"))
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            assertTrue(prefs.edit().putBoolean("protect_labs", previous).commit())
        }
    }

    private fun awaitHost() {
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (CoreConnection.service == null && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(75)
        }
        checkNotNull(CoreConnection.service) { "The running Kizuku host did not reattach" }
    }

    private fun awaitText(text: String): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            findText(text)?.let { return it }
            SystemClock.sleep(100)
        }
        error("Expected Labs UI was unavailable: $text")
    }

    private fun findText(text: String): AccessibilityNodeInfo? {
        fun search(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.text?.toString() == text || node.contentDescription?.toString() == text) return node
            for (index in 0 until node.childCount) {
                val result = node.getChild(index)?.let(::search)
                if (result != null) return result
            }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::search)
    }
}

class FixtureAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
}
