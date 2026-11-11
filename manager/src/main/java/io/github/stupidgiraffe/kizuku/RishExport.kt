package io.github.stupidgiraffe.kizuku

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@Composable
internal fun RishExport() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            busy = true
            message = null
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val output = checkNotNull(context.contentResolver.openOutputStream(uri)) { "Cannot open the selected file." }
                        ZipOutputStream(output).use { zip ->
                            for (name in listOf("rish", "rish_shizuku.dex", "NOTICE", "LICENSE")) {
                                zip.putNextEntry(ZipEntry(name))
                                val asset = if (name == "LICENSE") name else "rish/$name"
                                context.assets.open(asset).use { it.copyTo(zip) }
                                zip.closeEntry()
                            }
                        }
                    }
                    message = "rish ZIP exported."
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    message = failure.message ?: "Could not export rish."
                } finally { busy = false }
            }
        }
    }

    Text("rish terminal access", style = MaterialTheme.typography.titleMedium)
    Text("Install the companion and start Kizuku. Extract the ZIP into your terminal app’s private home directory.")
    Text("Termux command", style = MaterialTheme.typography.labelLarge)
    Text("RISH_APPLICATION_ID=com.termux sh ./rish", style = MaterialTheme.typography.bodySmall)
    Text("Approve the terminal app’s Shizuku request in Kizuku.")
    OutlinedButton(enabled = !busy, onClick = { export.launch("kizuku-rish.zip") }) { Text("Export rish ZIP") }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}
