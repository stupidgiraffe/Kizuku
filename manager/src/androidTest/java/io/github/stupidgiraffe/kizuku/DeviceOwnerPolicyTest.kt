package io.github.stupidgiraffe.kizuku

import android.app.admin.DevicePolicyManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceOwnerPolicyTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val policies = DeviceOwnerPolicies(context)

    @Test
    fun exerciseDeviceOwner() {
        val dpm = requireNotNull(context.getSystemService(DevicePolicyManager::class.java))
        assertTrue(policies.supported)
        assertNull("Device policy tests run without a privilege host", CoreConnection.service)
        when (InstrumentationRegistry.getArguments().getString("scenario")) {
            "unprovisioned" -> {
                assertFalse(policies.enrolled)
                val denied = runCatching { policies.apps() }.exceptionOrNull()
                assertTrue("App policy controls require actual Android enrollment", denied is IllegalStateException)
            }
            "enrolled" -> {
                assertTrue(policies.enrolled)
                val app = policies.apps().single { it.packageName == "io.github.stupidgiraffe.kizuku.testclient" }
                val target = policies.select(app)
                val suspension = DeviceOwnerPolicies.Policy.SUSPEND
                val protect = DeviceOwnerPolicies.Policy.BLOCK_UNINSTALL
                val initial = DeviceOwnerPolicies.Status(suspended = false, uninstallBlocked = false)
                try {
                    assertEquals(initial, policies.status(target))
                    val wrongIdentity = target.copy(app = app.copy(uid = app.uid + 1))
                    val denied = runCatching { policies.apply(wrongIdentity, protect, true) }.exceptionOrNull()
                    assertNotNull("Mismatched package identity must be rejected", denied)
                    assertEquals(initial, policies.status(target))
                    assertEquals(initial.copy(suspended = true), policies.apply(target, suspension, true))
                    assertEquals(initial, policies.apply(target, suspension, false))
                    assertEquals(initial.copy(uninstallBlocked = true), policies.apply(target, protect, true))
                    assertEquals(initial, policies.apply(target, protect, false))

                    policies.apply(target, suspension, true)
                    policies.apply(target, protect, true)
                } finally {
                    policies.removeOwner()
                }
                assertFalse(policies.enrolled)
                if (Build.VERSION.SDK_INT >= 29) {
                    assertFalse("Android released the app suspension", context.packageManager.isPackageSuspended(app.packageName))
                }
                assertFalse("Android released the uninstall block", dpm.isUninstallBlocked(null, app.packageName))
            }
            else -> error("Unknown Device Owner test scenario")
        }
    }
}
