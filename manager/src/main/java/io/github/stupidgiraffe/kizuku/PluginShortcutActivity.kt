package io.github.stupidgiraffe.kizuku

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Launcher entry only asks the user to open a known installed plugin under the current host. */
class PluginShortcutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra("plugin").orEmpty()
        if (!id.matches(Regex("[A-Za-z][A-Za-z0-9._-]{1,79}"))) { finish(); return }
        lifecycleScope.launch {
            var message: String? = null
            val plugin = try {
                withContext(Dispatchers.IO) {
                    checkNotNull(CoreConnection.service) { "Start Kizuku and open this shortcut again." }
                        .runtimePlugins().firstOrNull { it.getString("id") == id && it.getBoolean("enabled") && it.getBoolean("webui") }
                }
            } catch (failure: Exception) { message = failure.message; null }
            setContent {
                MaterialTheme {
                    var shell by remember { mutableStateOf(false) }
                    AlertDialog(onDismissRequest = { finish() },
                        title = { Text(plugin?.getString("name") ?: "Plugin unavailable") },
                        text = {
                            Column {
                                Text(message ?: if (plugin == null) "Enable this plugin in Runtime first."
                                    else "Open this plugin using Kizuku’s current host.")
                                if (plugin != null && (plugin.getString("format") != "shevery" || plugin.getBoolean("declares_shell"))) {
                                    Checkbox(checked = shell, onCheckedChange = { shell = it })
                                    Text("Allow commands for this session")
                                }
                            }
                        },
                        confirmButton = {
                            if (plugin != null) TextButton(onClick = {
                                startActivity(Intent(this@PluginShortcutActivity, PluginWebActivity::class.java)
                                    .putExtra("plugin", id).putExtra("shell", shell))
                                finish()
                            }) { Text("Open WebUI") }
                            else TextButton(onClick = { finish() }) { Text("Close") }
                        },
                        dismissButton = {
                            if (plugin != null) TextButton(onClick = { finish() }) { Text("Cancel") }
                        })
                }
            }
        }
    }
}

internal fun pinPluginShortcut(context: Context, plugin: Bundle) {
    val manager = context.getSystemService(ShortcutManager::class.java)
    check(manager.isRequestPinShortcutSupported) { "This launcher does not support pinned shortcuts." }
    val id = checkNotNull(plugin.getString("id"))
    val intent = Intent(context, PluginShortcutActivity::class.java)
        .setAction(Intent.ACTION_VIEW).putExtra("plugin", id)
    val shortcut = ShortcutInfo.Builder(context, "plugin.$id")
        .setShortLabel(plugin.getString("name", id).take(40))
        .setIcon(Icon.createWithResource(context, R.mipmap.ic_kizuku))
        .setIntent(intent)
        .build()
    check(manager.requestPinShortcut(shortcut, null)) { "The launcher could not request this shortcut." }
}
