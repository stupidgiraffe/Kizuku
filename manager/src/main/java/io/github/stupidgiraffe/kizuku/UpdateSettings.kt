package io.github.stupidgiraffe.kizuku

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun UpdateSettings() {
    val context = LocalContext.current
    val app = context.applicationContext
    val state by AppUpdates.state.collectAsStateWithLifecycle()
    var automatic by remember { mutableStateOf(AppUpdates.automatic(app)) }
    var autoDownload by remember { mutableStateOf(AppUpdates.autoDownload(app)) }
    var notice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(app) { AppUpdates.restore(app) }

    Text("Updates", style = MaterialTheme.typography.titleMedium)
    Text("Kizuku ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
    if (BuildConfig.DEBUG) {
        Text("In-app updates are available in signed releases.", style = MaterialTheme.typography.bodySmall)
        return
    }

    UpdateSwitch("Check automatically", automatic) { enabled ->
        notice = null
        runCatching { AppUpdates.setAutomatic(app, enabled) }.onSuccess {
            automatic = enabled
            if (enabled) AppUpdates.requestCheck(app)
        }.onFailure { notice = it.message }
    }
    if (automatic) {
        UpdateSwitch("Download updates automatically", autoDownload) { enabled ->
            AppUpdates.setAutoDownload(app, enabled)
            autoDownload = enabled
            if (enabled) AppUpdates.requestCheck(app)
        }
    }

    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    state.downloaded?.let { Text("Kizuku $it is ready to install.", style = MaterialTheme.typography.bodyMedium) }
    val available = state.release?.takeIf { it.version != state.downloaded }
    available?.let { Text("Kizuku ${it.version} is available.", style = MaterialTheme.typography.bodyMedium) }
    (notice ?: state.message)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = !state.busy, onClick = {
            notice = null
            AppUpdates.requestCheck(app)
        }) { Text("Check now") }
        if (available != null) {
            Button(enabled = !state.busy, onClick = {
                notice = null
                AppUpdates.requestDownload(app, available)
            }) { Text("Download") }
        }
        if (state.downloaded != null) {
            Button(enabled = !state.busy, onClick = {
                notice = null
                runCatching {
                    val intent = AppUpdates.installIntent(context)
                    context.startActivity(intent)
                }.onFailure { notice = it.message ?: "Could not open Android’s installer." }
            }) { Text("Install update") }
        }
    }
}

@Composable
private fun UpdateSwitch(label: String, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = enabled, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProjectLinks() {
    val context = LocalContext.current
    var notice by remember { mutableStateOf<String?>(null) }
    val links = listOf("GitHub" to AppUpdates.PROJECT_URL, "Buy me a coffee" to AppUpdates.SUPPORT_URL)
    Text("About Kizuku", style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        links.forEach { (label, url) ->
            TextButton(onClick = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }.onFailure { notice = "Could not open $url" }
            }) { Text(label) }
        }
    }
    notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}
