package io.github.stupidgiraffe.kizuku

import android.content.ClipData
import android.content.ClipboardManager
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
internal fun AutomationSettings(controller: ActivationController) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(AutomationCommands.token(context) != null) }
    var confirmation by remember { mutableStateOf<String?>(null) }
    var showCommands by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    val clipboard = context.getSystemService(ClipboardManager::class.java)

    fun update(turnOn: Boolean) {
        feedback = try {
            AutomationCommands.setEnabled(context, turnOn)
            enabled = turnOn
            if (turnOn) "Automation is enabled. The current key is ready to copy."
            else "Automation is off. The previous key was revoked."
        } catch (failure: IllegalStateException) {
            failure.message ?: "Could not change automation."
        }
    }

    Text("Automation", style = MaterialTheme.typography.titleMedium)
    Text(
        "Control the service from Tasker, MacroDroid, or ADB with an automation key.",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        controller.automationTransport()?.let { "Saved startup method · ${it.label}" }
            ?: "Start Kizuku once with root or paired Wireless ADB to set a startup method.",
        style = MaterialTheme.typography.bodySmall,
    )
    if (enabled) {
        Text("Automation is on. ${AutomationCommands.status(context)}", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showCommands = true }) { Text("Setup and key") }
            TextButton(onClick = { update(false) }) { Text("Turn off") }
        }
    } else {
        OutlinedButton(onClick = { confirmation = "Enable automation?" }) { Text("Enable automation") }
    }
    feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

    if (showCommands && enabled) AlertDialog(
        onDismissRequest = { showCommands = false },
        title = { Text("Set up automation") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Use Send Intent with the Broadcast target, package ${BuildConfig.APPLICATION_ID}, and class ${AutomationReceiver::class.java.name}. Add a string extra named token with your automation key.")
                Text("A command starts or stops the existing service. Running commands can be checked with the ordered Status broadcast. Start and restart use the saved startup method.")
                AutomationCommand.entries.forEach { command ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(command.label, Modifier.weight(1f))
                        TextButton(onClick = {
                            clipboard.setPrimaryClip(ClipData.newPlainText("Kizuku automation action", command.action))
                        }) { Text("Copy action") }
                    }
                }
                Text("ADB example", style = MaterialTheme.typography.titleSmall)
                Text("adb shell am broadcast -n ${BuildConfig.APPLICATION_ID}/.AutomationReceiver -a ${AutomationCommand.STATUS.action} --es token YOUR_KEY")
                TextButton(onClick = {
                    showCommands = false
                    confirmation = "Replace the automation key?"
                }) { Text("Replace key") }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                AutomationCommands.token(context)?.let { token ->
                    val clip = ClipData.newPlainText("Kizuku automation key", token)
                    clip.description.extras = PersistableBundle().apply {
                        putBoolean("android.content.extra.IS_SENSITIVE", true)
                    }
                    clipboard.setPrimaryClip(clip)
                }
                showCommands = false
            }) { Text("Copy key") }
        },
        dismissButton = { TextButton(onClick = { showCommands = false }) { Text("Close") } },
    )

    confirmation?.let { title ->
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(title) },
            text = {
                Text(if (enabled) "Replace the current key and update any routines that use it."
                else "Anyone with the key can start or stop Kizuku using your saved startup method. Share it only with automations you trust.")
            },
            confirmButton = {
                TextButton(onClick = {
                    update(true)
                    confirmation = null
                    showCommands = enabled
                }) { Text(if (enabled) "Replace key" else "Enable") }
            },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Cancel") } },
        )
    }
}
