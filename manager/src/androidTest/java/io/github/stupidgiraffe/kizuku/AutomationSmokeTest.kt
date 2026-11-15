package io.github.stupidgiraffe.kizuku

import android.app.Activity
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutomationSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val controller get() = ActivationController.get(context)

    @Test fun lifecycleCommandsRequireTheCurrentKeyAndAnIntentionalStopDoesNotRecover() {
        val hostUid = InstrumentationRegistry.getArguments().getString("host_uid")?.toInt() ?: 2000
        await("Host UID $hostUid did not attach to the manager") {
            CoreConnection.service?.status()?.getInt("uid") == hostUid
        }
        val recoveryEnabled = controller.recoveryDiagnostics().enabled
        try {
            AutomationCommands.setEnabled(context, true)
            val oldToken = requireNotNull(AutomationCommands.token(context))
            AutomationCommands.setEnabled(context, true)
            val activeToken = requireNotNull(AutomationCommands.token(context))
            assertNotEquals(oldToken, activeToken)
            assertBroadcast(AutomationCommand.STOP, null, Activity.RESULT_CANCELED, "Automation request refused.")
            assertBroadcast(AutomationCommand.STOP, oldToken, Activity.RESULT_CANCELED, "Automation request refused.")
            assertBroadcast(AutomationCommand.STATUS, null, Activity.RESULT_CANCELED, "Automation request refused.")
            assertBroadcast(AutomationCommand.STATUS, activeToken, Activity.RESULT_OK, "Running")
            instrumentation.runOnMainSync { controller.setRecovery(true) }
            assertBroadcast(AutomationCommand.STOP, activeToken, Activity.RESULT_OK, "Queued")
            await("service stops") { CoreConnection.service == null }
            await("automation job finishes") { AutomationCommands.status(context) == "Stop completed." }
            assertBroadcast(AutomationCommand.STATUS, activeToken, Activity.RESULT_OK, "Stopped")
            assertTrue(controller.recoveryDiagnostics().enabled)
            assertFalse(controller.recoveryDiagnostics().wanted)
            assertFalse(controller.recoveryDiagnostics().retryInProgress)
            AutomationCommands.setEnabled(context, false)
            assertNull(AutomationCommands.token(context))
            assertBroadcast(AutomationCommand.START, activeToken, Activity.RESULT_CANCELED, "Automation request refused.")
        } finally {
            AutomationCommands.setEnabled(context, false)
            instrumentation.runOnMainSync { controller.setRecovery(recoveryEnabled) }
        }
    }

    private fun assertBroadcast(command: AutomationCommand, key: String?, result: Int, message: String) {
        val component = "${context.packageName}/.AutomationReceiver"
        val credential = key?.let { " --es token '$it'" }.orEmpty()
        val output = instrumentation.uiAutomation.executeShellCommand(
            "am broadcast -n $component -a ${command.action}$credential"
        ).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        }
        val completion = output.lineSequence().lastOrNull { "Broadcast completed:" in it }
            ?.substringAfter("Broadcast completed: ")?.trim()
        assertEquals("${command.label} broadcast result", "result=$result, data=\"$message\"",
            completion?.substringBefore(", extras:"))
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(75)
        }
        check(condition()) { message }
    }
}
