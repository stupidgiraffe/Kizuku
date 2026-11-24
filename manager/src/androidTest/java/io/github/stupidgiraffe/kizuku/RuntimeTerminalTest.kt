package io.github.stupidgiraffe.kizuku

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.termux.terminal.TerminalSession
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeTerminalTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun interactiveActivityRendersAnsiResizesAndReportsExit() {
        val activity = instrumentation.startActivitySync(
            Intent(context, TerminalActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        var terminal: TerminalView? = null
        var session: TerminalSession? = null
        fun find(view: View): TerminalView? {
            if (view is TerminalView) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        try {
            await("terminal connected to the privileged PTY") {
                instrumentation.runOnMainSync {
                    terminal = find(activity.window.decorView)
                    session = terminal?.currentSession
                }
                session?.emulator != null
            }
            val current = checkNotNull(session)
            instrumentation.runOnMainSync {
                current.write("printf '\\033[2J\\033[H\\033[31mANSI_READY\\033[0m\\n'; id -u\n")
            }
            await("ANSI output and truthful host UID") {
                var found = false
                instrumentation.runOnMainSync {
                    val text = current.emulator.screen.transcriptText
                    val uid = InstrumentationRegistry.getArguments().getString("host_uid")
                    found = text.contains("ANSI_READY") && text.lines().any { it.trim() == uid }
                }
                found
            }
            instrumentation.runOnMainSync {
                assertEquals("Red SGR survives rendering", 1, TextStyle.decodeForeColor(current.emulator.screen.getStyleAt(0, 0)))
                current.updateSize(90, 31, 8, 16)
                current.write("stty size; printf RESIZED_OK\n")
            }
            await("PTY resize is applied") {
                var found = false
                instrumentation.runOnMainSync { found = current.emulator.screen.transcriptText.contains("31 90") }
                found
            }
            instrumentation.runOnMainSync { current.write("exit 23\n") }
            await("real shell exit status") { !current.isRunning }
            assertEquals(23, current.exitStatus)
            assertNotNull(terminal)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Timed out waiting for $description")
    }
}
