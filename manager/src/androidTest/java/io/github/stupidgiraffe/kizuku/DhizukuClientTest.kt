package io.github.stupidgiraffe.kizuku

import android.app.admin.DevicePolicyManager
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the released Amarok APK using its own Dhizuku 2.4 client. */
@RunWith(AndroidJUnit4::class)
class DhizukuClientTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private val automation = instrumentation.uiAutomation

    @Test fun legacyClientObtainsAndLosesRealAndroidPackageAccess() {
        val serviceInfo = automation.serviceInfo
        serviceInfo.flags = serviceInfo.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        automation.serviceInfo = serviceInfo
        val dpm = requireNotNull(app.getSystemService(DevicePolicyManager::class.java))
        val owner = DeviceOwnerPolicies(app)
        assertEquals(DeviceOwnerPolicies.Role.DEVICE, owner.role)
        assertNull("Dhizuku is exercised without a shell or root host", CoreConnection.service)
        assertFalse("Amarok starts without Android package delegation", hasPackageAccess(dpm, owner))
        val launch = ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand("am start -W -n $AMAROK/deltazero.amarok.ui.MainActivity")
        ).bufferedReader().use { it.readText() }
        assertTrue(launch, launch.contains("Status: ok"))
        click("Set Hidden Apps")
        click("Switch app-hiding mode")
        click("Dhizuku mode", viewId = "$AMAROK:id/switch_apphider_radio_dhizuku")
        click("Allow package access")
        await("Android delegated package access to the external client") { hasPackageAccess(dpm, owner) }
        assertTrue(AMAROK in DhizukuAuthorization.approvals(app))
        DhizukuAuthorization.revoke(app, AMAROK)
        assertFalse(hasPackageAccess(dpm, owner))
        assertFalse(AMAROK in DhizukuAuthorization.approvals(app))
    }

    private fun hasPackageAccess(dpm: DevicePolicyManager, owner: DeviceOwnerPolicies) =
        DevicePolicyManager.DELEGATION_PACKAGE_ACCESS in dpm.getDelegatedScopes(owner.admin, AMAROK)

    private fun click(label: String, viewId: String? = null) {
        await("UI control $label") {
            val nodes = nodes()
            fun find(text: String) = nodes.firstOrNull {
                it.isVisibleToUser && (it.text?.toString()?.equals(text, true) == true ||
                    it.contentDescription?.toString()?.equals(text, true) == true)
            }
            // Handle only known setup UI from the disposable emulator and the test APK.
            when {
                find("Pixel Launcher isn't responding") != null -> find("Close app")?.let(::tryClick)
                find("Welcome") != null -> find("OK")?.let(::tryClick)
                find("Storage permission required") != null -> find("Cancel")?.let(::tryClick)
                else -> {
                    val match = if (viewId == null) find(label) else nodes.firstOrNull {
                        it.isVisibleToUser && it.viewIdResourceName == viewId
                    }
                    if (match != null) return@await tryClick(match)
                    nodes.firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                }
            }
            false
        }
    }

    private fun tryClick(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable && current.isEnabled &&
                current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            current = current.parent
        }
        return false
    }

    private fun nodes(): List<AccessibilityNodeInfo> = buildList {
        fun visit(node: AccessibilityNodeInfo) {
            add(node)
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
        }
        automation.rootInActiveWindow?.let(::visit)
    }

    private fun await(label: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 10000
        while (SystemClock.elapsedRealtime() < end) {
            if (condition()) return
            SystemClock.sleep(150)
        }
        throw AssertionError("$label. Visible labels: " + nodes().mapNotNull { it.text }.take(25))
    }

    private companion object {
        const val AMAROK = "deltazero.amarok.foss"
    }
}
