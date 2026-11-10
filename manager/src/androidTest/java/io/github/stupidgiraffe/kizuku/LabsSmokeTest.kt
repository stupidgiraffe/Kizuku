package io.github.stupidgiraffe.kizuku

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
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
        val report = runBlocking(Dispatchers.Main) {
            runServiceDoctor(app, ActivationController.get(app)).associate { it.label to it.evidence }
        }
        val host = CoreConnection.service?.status()
        assertEquals(if (host == null) "No host attached" else "Responded", report["Host Binder"])
        if (host != null) {
            assertTrue(report.getValue("Host identity").contains("UID ${host.getInt("uid")}"))
            assertTrue(report.getValue("Host identity").contains("PID ${host.getInt("pid")}"))
        } else assertFalse(report.containsKey("Host identity"))
        assertTrue(report.containsKey("Automatic recovery"))
        assertTrue(report.containsKey("Pairing notifications"))
        assertTrue(report.containsKey("Battery optimization"))
    }

    @Test fun accessibilityInventoryMatchesTheInstalledAndConfiguredServices() {
        val configured = Settings.Secure.getString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').mapNotNull(ComponentName::unflattenFromString).toSet()
        val installed = app.getSystemService(AccessibilityManager::class.java).installedAccessibilityServiceList
            .map { it.resolveInfo.serviceInfo }
            .map { ComponentName(it.packageName, it.name) }
            .toSet()
        val entries = readAccessibilityInventory(app)
        assertEquals(installed, entries.mapNotNull { ComponentName.unflattenFromString(it.component) }.toSet())
        assertEquals(configured intersect installed, entries.filter { it.enabled }.mapNotNull { ComponentName.unflattenFromString(it.component) }.toSet())
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
