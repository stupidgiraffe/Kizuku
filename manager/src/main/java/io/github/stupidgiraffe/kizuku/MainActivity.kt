package io.github.stupidgiraffe.kizuku

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.BidiFormatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.stupidgiraffe.kizuku.core.Capability

private val Ink = Color(0xFF040613)
private val Panel = Color(0xFF10172D)
private val Cyan = Color(0xFF81E3FF)
private val Muted = Color(0xFFADB9D4)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.decorView.filterTouchesWhenObscured = true
        if (Build.VERSION.SDK_INT >= 31) window.setHideOverlayWindows(true)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Cyan, onPrimary = Color(0xFF002C40), secondary = Color(0xFFC6B5FF),
                background = Ink, surface = Panel, onBackground = Color(0xFFEAF0FF), onSurface = Color(0xFFEAF0FF),
                surfaceVariant = Color(0xFF1B2543), onSurfaceVariant = Muted, outline = Color(0xFF465476),
            ), shapes = Shapes(medium = RoundedCornerShape(22.dp), large = RoundedCornerShape(28.dp))) {
                CommandCenter()
            }
        }
    }
}

private enum class Destination(val title: String, val icon: ImageVector) {
    Core("Core", Icons.Outlined.Shield), Apps("Apps", Icons.Outlined.Apps),
    Modules("Modules", Icons.Outlined.Extension), Labs("Labs", Icons.Outlined.Terminal),
    Settings("Settings", Icons.Outlined.Tune)
}

@Composable private fun CommandCenter(controller: ActivationController = viewModel()) {
    val state by controller.state.collectAsStateWithLifecycle()
    val revision by CoreConnection.revision.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Destination.Core) }
    LaunchedEffect(revision) { controller.refresh() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { CoreConnection.changed() }
    Scaffold(containerColor = Ink, bottomBar = {
        NavigationBar(containerColor = Panel, modifier = Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Destination.entries.forEach { item ->
                NavigationBarItem(selected = tab == item, onClick = { tab = item }, icon = { Icon(item.icon, contentDescription = null) },
                    label = { Text(item.title, fontSize = 11.sp) }, colors = NavigationBarItemDefaults.colors(indicatorColor = Color(0xFF20366A), selectedIconColor = Cyan))
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF101F48), Ink), endY = 850f)).padding(padding)
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFF1D3472), border = BorderStroke(1.dp, Cyan.copy(alpha = .3f))) {
                    Icon(Icons.Outlined.Terminal, null, tint = Cyan, modifier = Modifier.padding(12.dp).size(24.dp))
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("Kizuku", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                    Text("PRIVILEGE PLATFORM", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp)
                }
                Text("Android ${Build.VERSION.RELEASE}", style = MaterialTheme.typography.labelMedium, color = Muted)
            }
            Text(when (tab) { Destination.Core -> "Privilege core"; Destination.Apps -> "Authorized apps"; Destination.Modules -> "Extensions"; Destination.Labs -> "Power labs"; Destination.Settings -> "Settings" },
                style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
            AnimatedContent(tab, label = "navigation") { destination ->
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    when (destination) {
                        Destination.Core -> CoreScreen(state, controller)
                        Destination.Apps -> AppsScreen(state, controller)
                        Destination.Settings -> SettingsScreen(state, controller)
                        Destination.Modules -> EmptySurface(Icons.Outlined.Extension, "A stable core comes first", "Extensions will share Kizuku’s capability grants. No module engine is active in this build.")
                        Destination.Labs -> EmptySurface(Icons.Outlined.Science, "Labs are isolated", "ShizuPosed, ART tools and experimental bridges will be optional. The core runs independently.")
                    }
                }
            }
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Text("0.1 · DEVELOPMENT FOUNDATION", color = Muted, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp)
        }
    }
}

@Composable private fun Glass(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(28.dp), color = Panel.copy(alpha = .92f), border = BorderStroke(1.dp, Color(0xFF2B3B60))) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

@Composable private fun CoreScreen(state: CoreState, controller: ActivationController) {
    val clipboard = LocalClipboardManager.current
    var showAdb by remember { mutableStateOf(false) }
    Glass {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (state.status != null) Icons.Outlined.VerifiedUser else Icons.Outlined.PowerSettingsNew, null, tint = Cyan, modifier = Modifier.size(28.dp))
            Text(state.phase, color = Cyan, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 10.dp))
        }
        Text(if (state.status != null) "Your privileged host is connected." else "Your device.\nYour authority.", fontSize = 27.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold)
        Text("Existing Shizuku clients and native Kizuku capabilities share one controller, with independent permissions.", color = Muted, style = MaterialTheme.typography.bodyMedium)
        state.status?.let { status ->
            HorizontalDivider(color = Color(0xFF2B3B60))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Metric("PRIVILEGE", status.getString("transport") ?: "Unknown")
                Metric("UID", status.getInt("uid").toString())
                Metric("NATIVE API", "v${status.getInt("protocol")}")
            }
            OutlinedButton(onClick = controller::stop, modifier = Modifier.fillMaxWidth()) { Text("Stop core") }
        }
    }
    Glass {
        Text("Activate Kizuku", style = MaterialTheme.typography.titleMedium)
        Button(onClick = { controller.startRoot() }, enabled = !state.busy && state.status == null, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else { Icon(Icons.Outlined.Bolt, null); Spacer(Modifier.width(8.dp)); Text("Start with root") }
        }
        OutlinedButton(onClick = { showAdb = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Start from a computer") }
        Text("Wireless ADB pairing is the next activation milestone. This build supports root and a computer ADB command.", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
    Glass {
        Text("Core diagnostics", style = MaterialTheme.typography.titleMedium)
        Text("${state.apps.count { it.getLong("granted") != 0L }} authorized · ${state.apps.count { it.getLong("requested") != 0L }} requesting access", color = Muted)
        Text("Legacy Binder forwarding: foundation subset\nAxeron runtime: not installed", style = MaterialTheme.typography.bodySmall, color = Muted)
    }
    if (showAdb) AlertDialog(onDismissRequest = { showAdb = false }, title = { Text("Computer ADB activation") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Connect a computer with USB debugging enabled, then run:")
            Text(ActivationController.pcCommand, style = MaterialTheme.typography.bodySmall)
            Text("The core runs as Android shell (UID 2000).", color = Muted)
        }
    }, confirmButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(ActivationController.pcCommand)); showAdb = false }) { Text("Copy command") } })
}

@Composable private fun Metric(label: String, value: String) { Column { Text(label, color = Muted, fontSize = 10.sp); Text(value, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) } }

@Composable private fun AppsScreen(state: CoreState, controller: ActivationController) {
    val context = LocalContext.current
    var approval by remember { mutableStateOf<Pair<Bundle, Long>?>(null) }
    if (state.apps.isEmpty()) EmptySurface(Icons.Outlined.Apps, "No app has requested access", "Start the core, then connect a compatible client. Requests appear here with its signing identity and separate capabilities.")
    state.apps.forEach { app ->
        val pkg = app.getString("package").orEmpty()
        val label = remember(pkg) {
            val raw = runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            BidiFormatter.getInstance().unicodeWrap(raw.filterNot { Character.getType(it) == Character.FORMAT.toInt() || it.isISOControl() }.take(80))
        }
        Glass {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(pkg, style = MaterialTheme.typography.bodySmall, color = Muted)
            Text("UID ${app.getInt("uid")} · SHA-256 ${app.getString("certificate")?.take(16)}…", style = MaterialTheme.typography.bodySmall, color = Muted)
            if (app.getStringArray("shared_uid").orEmpty().size > 1) Text("Shared UID: ${app.getStringArray("shared_uid")!!.joinToString()}", color = MaterialTheme.colorScheme.error)
            listOf("Shizuku Binder" to Capability.SHIZUKU, "Native diagnostics" to Capability.DIAGNOSTICS).forEach { (title, scope) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f))
                    Switch(checked = app.getLong("granted") and scope != 0L, enabled = app.getBoolean("verified"), onCheckedChange = { on ->
                        if (on) approval = app to scope else controller.decide(app, scope, null)
                    })
                }
            }
            if (app.getLong("requested") != 0L) Text("Access requested — review a capability above.", color = Cyan, style = MaterialTheme.typography.bodySmall)
        }
    }
    approval?.let { (app, scope) ->
        AlertDialog(onDismissRequest = { approval = null }, title = { Text("Authorize ${if (scope == Capability.SHIZUKU) "Shizuku Binder" else "native diagnostics"}?") }, text = {
            ProtectApprovalWindow()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(app.getString("package").orEmpty())
                Text("UID ${app.getInt("uid")}\nSHA-256\n${app.getString("certificate")}", style = MaterialTheme.typography.bodySmall)
                Text(if (scope == Capability.SHIZUKU) "This grants broad Android system Binder access. It does not authorize Kizuku’s native shell or Axeron runtime." else "Allows reading the host UID, PID, Android API and uptime.")
                app.getStringArray("shared_uid")?.takeIf { it.size > 1 }?.let { Text("The following packages share this Linux UID: ${it.joinToString()}") }
            }
        }, confirmButton = {
            Column {
                listOf("Once" to 0, "This service session" to 1, "Remember this certificate" to 2).forEach { (label, lifetime) ->
                    TextButton(onClick = { controller.decide(app, scope, lifetime); approval = null }) { Text(label) }
                }
            }
        }, dismissButton = { TextButton(onClick = { controller.decide(app, scope, null); approval = null }) { Text("Deny") } })
    }
}

@Composable private fun ProtectApprovalWindow() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.filterTouchesWhenObscured = true
        val window=(view.parent as? DialogWindowProvider)?.window
        if (Build.VERSION.SDK_INT >= 31) window?.setHideOverlayWindows(true)
        onDispose { }
    }
}

@Composable private fun SettingsScreen(state: CoreState, controller: ActivationController) {
    val context = LocalContext.current
    var recover by remember { mutableStateOf(false) }
    Glass {
        Text("Compatibility", style = MaterialTheme.typography.titleMedium)
        Text("Legacy Shizuku Stub", fontWeight = FontWeight.SemiBold)
        Text(CompanionInstaller.state(context), color = Muted, style = MaterialTheme.typography.bodySmall)
        Text("Bundled with Kizuku. It owns the legacy identity and conflicts with stock Shizuku or another fork using that identity.", color = Muted, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { runCatching { CompanionInstaller.install(context) }.onFailure { controller.notice(it.message ?: "Install failed") } }) { Text("Install") }
            OutlinedButton(onClick = { runCatching { CompanionInstaller.remove(context) }.onFailure { controller.notice(it.message ?: "Remove failed") } }) { Text("Remove") }
        }
    }
    Glass {
        Text("Protection & recovery", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Safe mode"); Text("Suspend all client capabilities", color = Muted, style = MaterialTheme.typography.bodySmall) }
            Switch(checked = state.status?.getBoolean("safe_mode") ?: false, enabled = state.status != null, onCheckedChange = controller::safeMode)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Root recovery"); Text("Up to five retries while the manager runs", color = Muted, style = MaterialTheme.typography.bodySmall) }
            Switch(checked = recover, onCheckedChange = { recover = it; controller.setRecovery(it) })
        }
        Text("No telemetry. Authorization stays on your device. Development builds have no permanent release signing identity.", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
    TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/stupidgiraffe/Kizuku"))) }) { Text("Source & licenses") }
}

@Composable private fun EmptySurface(icon: ImageVector, title: String, detail: String) {
    Glass { Icon(icon, null, tint = Cyan, modifier = Modifier.size(32.dp)); Text(title, style = MaterialTheme.typography.titleLarge); Text(detail, color = Muted, style = MaterialTheme.typography.bodyMedium) }
}
