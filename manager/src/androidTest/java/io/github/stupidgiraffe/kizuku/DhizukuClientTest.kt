package io.github.stupidgiraffe.kizuku

import android.app.admin.DevicePolicyManager
import android.content.Intent
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
        val dpm = requireNotNull(app.getSystemService(DevicePolicyManager::class.java))
        val owner = DeviceOwnerPolicies(app)
        assertEquals(DeviceOwnerPolicies.Role.DEVICE, owner.role)
        assertNull("Dhizuku is exercised without a shell or root host", CoreConnection.service)
        assertFalse("Amarok starts without Android package delegation", hasPackageAccess(dpm, owner))
        val launch = requireNotNull(app.packageManager.getLaunchIntentForPackage(AMAROK))
        app.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        // Dismiss optional storage access. It is unrelated to app hiding.
        click("OK")
        click("Cancel")
        click("Set Hidden Apps")
        click("Switch app-hiding mode")
        click("Dhizuku")
        click("Allow package access")
        await("Android delegated package access to the external client") { hasPackageAccess(dpm, owner) }
        assertTrue(AMAROK in DhizukuAuthorization.approvals(app))
        DhizukuAuthorization.revoke(app, AMAROK)
        assertFalse(hasPackageAccess(dpm, owner))
        assertFalse(AMAROK in DhizukuAuthorization.approvals(app))
    }

    private fun hasPackageAccess(dpm: DevicePolicyManager, owner: DeviceOwnerPolicies) =
        DevicePolicyManager.DELEGATION_PACKAGE_ACCESS in dpm.getDelegatedScopes(owner.admin, AMAROK)

    private fun click(label: String) {
        await("UI control $label") {
            val nodes = nodes()
            val match = nodes.firstOrNull {
                it.isVisibleToUser && (it.text?.toString()?.equals(label, true) == true ||
                    it.contentDescription?.toString()?.equals(label, true) == true)
            }
            if (match == null) {
                nodes.firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                return@await false
            }
            var current: AccessibilityNodeInfo? = match
            while (current != null) {
                if (current.isClickable && current.isEnabled &&
                    current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return@await true
                current = current.parent
            }
            false
        }
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
