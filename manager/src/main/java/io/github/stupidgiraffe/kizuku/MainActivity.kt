package io.github.stupidgiraffe.kizuku

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

private val Ink: Color @Composable get() = MaterialTheme.colorScheme.background
private val Panel: Color @Composable get() = MaterialTheme.colorScheme.surfaceContainer
private val Cyan: Color @Composable get() = MaterialTheme.colorScheme.primary
private val Muted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT))
        window.decorView.filterTouchesWhenObscured = true
        if (Build.VERSION.SDK_INT >= 31) window.setHideOverlayWindows(true)
        setContent {
            val scheme = if (isSystemInDarkTheme()) darkColorScheme(
                primary = Color(0xFFAEC5FF), primaryContainer = Color(0xFF233D77),
                background = Color(0xFF11131B), surface = Color(0xFF11131B),
                surfaceContainer = Color(0xFF1E2230),
            ) else lightColorScheme(
                primary = Color(0xFF31569E), primaryContainer = Color(0xFFDCE6FF),
                background = Color(0xFFF7F8FF), surface = Color(0xFFF7F8FF),
                surfaceContainer = Color(0xFFEAEDF8),
            )
            MaterialExpressiveTheme(colorScheme = scheme, motionScheme = MotionScheme.expressive(),
                shapes = Shapes(medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp))) {
                CommandCenter()
            }
        }
    }
}

private enum class Destination(val title: String, val heading: String, val icon: ImageVector) {
    Core("Home", "Kizuku", Icons.Outlined.Shield),
    Apps("Apps", "Application manager", Icons.Outlined.Apps),
    Runtime("Runtime", "Axeron runtime", Icons.Outlined.Terminal),
    Labs("Labs", "Labs", Icons.Outlined.Science),
    Settings("Settings", "Settings", Icons.Outlined.Tune),
}

@Composable private fun CommandCenter(controller: ActivationController = ActivationController.get(LocalContext.current)) {
    val state by controller.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Destination.Core) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { CoreConnection.changed() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 760.dp
        Scaffold(containerColor = Ink, bottomBar = {
            if (!wide) NavigationBar(containerColor = Panel) {
                Destination.entries.forEach { item ->
                    NavigationBarItem(selected = tab == item, onClick = { tab = item },
                        icon = { Icon(item.icon, null) }, label = { Text(item.title) })
                }
            }
        }) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if (wide) NavigationRail(containerColor = Panel) {
                    Destination.entries.forEach { item ->
                        NavigationRailItem(selected = tab == item, onClick = { tab = item },
                            icon = { Icon(item.icon, null) }, label = { Text(item.title) })
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                    val scroll = rememberScrollState()
                    LaunchedEffect(tab) { scroll.scrollTo(0) }
                    Column(
                        Modifier.widthIn(max = 780.dp).fillMaxWidth().then(
                            if (tab == Destination.Apps) Modifier.fillMaxHeight() else Modifier.verticalScroll(scroll)
                        ).padding(horizontal = if (wide) 28.dp else 18.dp).padding(vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(tab.heading, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                        state.message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                        when (tab) {
                            Destination.Core -> CoreScreen(state, controller) { tab = Destination.Settings }
                            Destination.Apps -> Box(Modifier.weight(1f)) {
                                AppsManagementScreen(state, controller) { InstalledAppControls(state.status?.getBoolean("axeron_runtime") == true) }
                            }
                            Destination.Runtime -> RuntimeScreen(state)
                            Destination.Labs -> LabsScreen(controller) {
                                if (state.status?.getBoolean("axeron_runtime") == true) Glass { SystemSettingsTool() }
                                NativeDiagnosticsPermissions(state, controller)
                            }
                            Destination.Settings -> SettingsScreen(state, controller)
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun Glass(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(28.dp), color = Panel, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

@Composable private fun CoreScreen(state: CoreState, controller: ActivationController, openSettings: () -> Unit) {
    val context = LocalContext.current
    val companionChanges by CompanionInstaller.changes.collectAsStateWithLifecycle()
    val companion = remember(companionChanges, state.appsLoaded) { CompanionInstaller.packageState(context) }
    val clipboard = LocalClipboardManager.current
    var showAdb by remember { mutableStateOf(false) }
    var showAdbControls by remember { mutableStateOf(false) }
    val status = state.status
    Glass {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (status != null) Icons.Outlined.VerifiedUser else Icons.Outlined.PowerSettingsNew, null, tint = Cyan, modifier = Modifier.size(28.dp))
            Text(
                status?.getString("transport")?.let { "${state.phase} · $it" } ?: state.phase,
                color = Cyan, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
        HorizontalDivider()
        HomeStatus("Shizuku compatibility", when {
            state.safeMode -> "Client access paused in Safe Mode."
            status != null -> "Ready for authorized apps."
            else -> "Offline."
        })
        HomeStatus("Axeron runtime", when {
            state.safeMode -> "Suspended in Safe Mode."
            status?.getBoolean("axeron_runtime") == true -> "Ready for commands and modules."
            status != null -> status.getString("runtime_error") ?: "Unavailable on this host."
            else -> "Offline."
        })
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HomeStatus("Compatibility companion", companion.text, Modifier.weight(1f))
            TextButton(onClick = openSettings) { Text("Manage") }
        }
        if (status != null) OutlinedButton(onClick = controller::stop, modifier = Modifier.fillMaxWidth()) { Text("Stop service") }
    }
    Glass {
        Text("Activate Kizuku", style = MaterialTheme.typography.titleMedium)
        Button(onClick = { showAdbControls = true }, enabled = !state.busy && state.status == null, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Wireless ADB / TCP") }
        Button(onClick = { controller.startRoot() }, enabled = !state.busy && state.status == null, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else { Icon(Icons.Outlined.Bolt, null); Spacer(Modifier.width(8.dp)); Text("Start with root") }
        }
        OutlinedButton(onClick = { showAdb = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Start from a computer") }
        Text("Pair using the notification while Settings stays open. Previously paired devices can start over Wi-Fi; custom TCP ports are supported.", color = Muted, style = MaterialTheme.typography.bodySmall)
        if (state.busy) OutlinedButton(onClick = controller::cancelActivation) { Text("Cancel activation") }
    }
    if (showAdbControls) AdbControls(controller) { showAdbControls = false }
    if (showAdb) AlertDialog(onDismissRequest = { showAdb = false }, title = { Text("Computer ADB activation") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Connect a computer with USB debugging enabled, then run:")
            Text(ActivationController.pcCommand, style = MaterialTheme.typography.bodySmall)
            Text("The core runs as Android shell (UID 2000).", color = Muted)
        }
    }, confirmButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(ActivationController.pcCommand)); showAdb = false }) { Text("Copy command") } })
}

@Composable private fun HomeStatus(title: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(value, color = Muted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable private fun SettingsScreen(state: CoreState, controller: ActivationController) {
    val context = LocalContext.current
    var recover by remember { mutableStateOf(controller.recoveryDiagnostics().enabled) }
    val changes by CompanionInstaller.changes.collectAsStateWithLifecycle()
    var resumed by remember { mutableIntStateOf(0) }
    var removing by rememberSaveable { mutableStateOf(false) }
    var removalNotice by rememberSaveable { mutableStateOf<String?>(null) }
    val remove = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        removing = false
        removalNotice = CompanionInstaller.removalResult(context, result.resultCode, result.data)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        resumed++
    }
    val companion = remember(changes, resumed) { CompanionInstaller.packageState(context) }
    val installing = remember(changes, resumed) { CompanionInstaller.installing(context) }
    Glass {
        Text("Compatibility companion", style = MaterialTheme.typography.titleMedium)
        Text(companion.text, color = Muted, style = MaterialTheme.typography.bodySmall)
        Text("The companion provides the package identity that Shizuku apps look for.", color = Muted, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !installing && !removing && !companion.conflict, onClick = {
                removalNotice = null
                runCatching { CompanionInstaller.install(context) }.onFailure { controller.notice(it.message ?: "Install failed") }
            }) { Text(if (companion.installed) "Reinstall" else "Install") }
            OutlinedButton(enabled = !installing && !removing && companion.installed, onClick = {
                runCatching {
                    val intent = CompanionInstaller.removalIntent(context)
                    removing = true
                    removalNotice = null
                    remove.launch(intent)
                }.onFailure { removing = false; removalNotice = it.message ?: "Cannot open Android’s uninstaller." }
            }) { Text("Remove") }
        }
        if (installing) {
            Text("Waiting for Android’s installation confirmation.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { CompanionInstaller.reset(context) }) { Text("Clear install session") }
        }
        (removalNotice ?: CompanionInstaller.notice(context))?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }
    Glass { RishExport() }
    Glass {
        Text("Protection & recovery", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Safe mode"); Text("Suspend all client capabilities", color = Muted, style = MaterialTheme.typography.bodySmall) }
            Switch(checked = state.safeMode, onCheckedChange = controller::safeMode)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Core recovery"); Text("Bounded retries for root or available Wireless ADB", color = Muted, style = MaterialTheme.typography.bodySmall) }
            Switch(checked = recover, onCheckedChange = { recover = it; controller.setRecovery(it) })
        }
        Text("No telemetry. Authorization stays on your device. Release builds use Kizuku’s permanent signing identity.", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
    TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/stupidgiraffe/Kizuku"))) }) { Text("Source & licenses") }
}
