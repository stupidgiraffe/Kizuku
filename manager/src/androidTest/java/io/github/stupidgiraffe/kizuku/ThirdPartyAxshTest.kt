package io.github.stupidgiraffe.kizuku

import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stupidgiraffe.kizuku.api.AndroidIdentity
import io.github.stupidgiraffe.kizuku.core.Capability
import io.github.stupidgiraffe.kizuku.core.GrantLedger
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the unchanged signed DemoAxsh release shipped by the original AxManager 3.0. */
@RunWith(AndroidJUnit4::class)
class ThirdPartyAxshTest {
    @Test fun originalNativeAxshClientRunsWithTheRealHostUid() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val demo = "dev.frb.demo_axsh"
        val host = awaitRuntimeTestHost()
        val uid = host.status().getInt("uid")
        val info = app.packageManager.getApplicationInfo(demo, 0)
        val identity = AndroidIdentity.resolve(app.packageManager, info.uid, demo)
        host.grant(demo, info.uid, Capability.AXERON, GrantLedger.Lifetime.SESSION.ordinal, identity.fingerprint())
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .use { it.readBytes() }
        try {
            shell("am force-stop $demo")
            shell("am start -W -n $demo/.MainActivity")
            await("original demo custom command") {
                val visible = nodes()
                val scrolled = visible.firstOrNull { it.isVisibleToUser && it.isScrollable }
                    ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true
                !scrolled && visible.any { it.text?.toString() == "Custom command" && it.isVisibleToUser }
            }
            val input = nodes().last { it.className?.toString() == "android.widget.EditText" }
            assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    "printf 'NATIVE_REFERENCE:'; id -u; printf 'NATIVE_STDERR' >&2; exit 17")
            }))
            var run: AccessibilityNodeInfo? = null
            await("the custom command's own Run button") {
                val visible = nodes()
                val currentInput = visible.lastOrNull { it.className?.toString() == "android.widget.EditText" }
                val bounds = android.graphics.Rect()
                currentInput?.getBoundsInScreen(bounds)
                run = visible.firstOrNull {
                    currentInput != null && it.text?.toString() == "Run" && it.isVisibleToUser && it.isEnabled &&
                        android.graphics.Rect().also(it::getBoundsInScreen).top >= bounds.bottom
                }
                if (run != null) true else {
                    visible.firstOrNull { it.isVisibleToUser && it.isScrollable }
                        ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    false
                }
            }
            var control: AccessibilityNodeInfo? = run
            while (control != null && !control.isClickable) control = control.parent
            assertTrue(control?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
            await("original native client's streams, host UID and exit code") {
                val text = nodes().mapNotNull { it.text?.toString() }.joinToString("\n")
                text.contains("NATIVE_REFERENCE:$uid") && text.contains("NATIVE_STDERR") && text.contains("exit=17")
            }
        } finally {
            shell("am force-stop $demo")
            host.revoke(demo, info.uid, Capability.AXERON)
        }
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        if (Build.VERSION.SDK_INT >= 34) automation.clearCache()
        return buildList {
            fun visit(node: AccessibilityNodeInfo) {
                add(node)
                for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
            }
            automation.rootInActiveWindow?.let(::visit)
        }
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(250)
        }
        throw AssertionError("Timed out waiting for $label. " +
            nodes().mapNotNull { it.text }.joinToString(" | ").take(2000))
    }
}
