package io.github.stupidgiraffe.kizuku

import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Requires a disposable English-language emulator with installation from Kizuku allowed. */
@RunWith(AndroidJUnit4::class)
class CompanionLifecycleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private val packages = app.packageManager
    private val companion = "moe.shizuku.privileged.api"

    @Test fun installAndRemoveWithAndroidConfirmation() {
        assertTrue("Allow installation from Kizuku on the emulator", packages.canRequestPackageInstalls())
        assertFalse("Start with the companion package absent", companionInstalled())
        val activity = instrumentation.startActivitySync(
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        clickManager("Settings")
        awaitManagerText("Companion is not installed")

        clickManager("Install")
        clickInstaller("Cancel")
        awaitManagerText("Installation canceled.")
        assertFalse(companionInstalled())

        clickManager("Install")
        clickInstaller("Install")
        awaitManagerText("Companion installed.")
        assertEquals(PackageManager.SIGNATURE_MATCH, packages.checkSignatures(app.packageName, companion))
        assertNotNull("Companion has a launcher entry", packages.getLaunchIntentForPackage(companion))

        clickManager("Remove")
        await("Android uninstall confirmation") { installerControl("Cancel") != null }
        instrumentation.runOnMainSync { activity.recreate() }
        await("manager recreation while removal is pending") { activity.isDestroyed }
        clickInstaller("Cancel")
        awaitManagerText("Removal was canceled or blocked by Android.")
        assertTrue(companionInstalled())

        clickManager("Remove")
        clickInstaller("Uninstall", "OK")
        awaitManagerText("Companion removed.")
        awaitManagerText("Companion is not installed")
        assertFalse(companionInstalled())
    }

    @Suppress("DEPRECATION")
    private fun companionInstalled(): Boolean = try {
        packages.getPackageInfo(companion, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun awaitManagerText(text: String) = await(text) { managerControl(text) != null }

    private fun managerControl(text: String) = nodes().firstOrNull {
        it.packageName?.toString() == app.packageName &&
            (it.text?.toString() == text || it.contentDescription?.toString() == text)
    }

    private fun clickManager(text: String) = await(text) { click(managerControl(text)) }

    private fun clickInstaller(vararg labels: String) = await("Android installer ${labels.joinToString("/")}") {
        click(installerControl(*labels))
    }

    private fun installerControl(vararg labels: String): AccessibilityNodeInfo? {
        val window = nodes().filter { it.packageName?.toString() != app.packageName }
        if (window.none { it.text?.contains("Kizuku Companion") == true }) return null
        return window.firstOrNull { node -> labels.any { it.equals(node.text?.toString(), ignoreCase = true) } }
    }

    private fun click(node: AccessibilityNodeInfo?): Boolean {
        var target = node
        while (target != null && !target.isClickable) target = target.parent
        return target?.isEnabled == true && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun nodes(): List<AccessibilityNodeInfo> = buildList {
        fun visit(node: AccessibilityNodeInfo) {
            add(node)
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
        }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(150)
        }
        fail("Timed out waiting for $description. Visible text: ${nodes().mapNotNull { it.text }.joinToString(" | ")}")
    }
}
