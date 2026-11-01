package io.github.stupidgiraffe.kizuku

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable fun AdbControls(controller:ActivationController,onDismiss:()->Unit) {
    val context=LocalContext.current
    val state by controller.state.collectAsStateWithLifecycle()
    val adb by controller.adb.collectAsStateWithLifecycle()
    var host by remember{mutableStateOf("127.0.0.1")}
    var port by remember{mutableStateOf("5555")}
    var code by remember{mutableStateOf("")}
    var tls by remember{mutableStateOf(false)}
    var manualPair by remember{mutableStateOf(false)}
    var pending by remember{mutableStateOf<(() -> Unit)?>(null)}
    fun settings(){runCatching{context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))}.onFailure{context.startActivity(Intent(Settings.ACTION_SETTINGS))}}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){ results ->
        val allowed=results.values.all{it}
        val action=pending;pending=null
        if(allowed)action?.invoke() else controller.notice("Allow Local network access for ADB. Notifications are needed for pairing-code entry while Settings stays open.")
    }
    fun permitted(notifications:Boolean,action:()->Unit) {
        val required=mutableListOf<String>()
        if(Build.VERSION.SDK_INT>=37 && context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK)!=PackageManager.PERMISSION_GRANTED)required+=Manifest.permission.ACCESS_LOCAL_NETWORK
        if(notifications && Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)required+=Manifest.permission.POST_NOTIFICATIONS
        if(required.isEmpty())action() else {pending=action;permission.launch(required.toTypedArray())}
    }
    AlertDialog(onDismissRequest=onDismiss,title={Text("ADB activation")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Wireless debugging · Android 11+")
            Text("Pair once, then reconnect using the current connection port. Pairing and connection ports are different.",style=MaterialTheme.typography.bodySmall)
            Button(enabled=Build.VERSION.SDK_INT>=30 && !state.busy,onClick={permitted(true){
                ContextCompat.startForegroundService(context,Intent(context,AdbPairingService::class.java).setAction(AdbPairingService.BEGIN));settings()
            }},modifier=Modifier.fillMaxWidth()){Text(if(adb.paired)"Pair again with code" else "Pair with code")}
            OutlinedButton(enabled=Build.VERSION.SDK_INT>=30 && !state.busy && state.status==null,onClick={permitted(false){controller.startWireless()}},modifier=Modifier.fillMaxWidth()){Text("Start paired device")}
            TextButton(onClick=::settings){Text("Open debugging settings")}
            Text(adb.message ?: adb.stage,style=MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text("Manual port / TCP",style=MaterialTheme.typography.titleMedium)
            Text("Use the IP and connection port shown in Wireless debugging, or an existing classic TCP port. Classic TCP requires USB debugging and an authorized ‘adb tcpip PORT’ setup.",style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(value=host,onValueChange={host=it.trim()},label={Text("This device's IP / localhost")},singleLine=true)
            OutlinedTextField(value=port,onValueChange={port=it.filter(Char::isDigit).take(5)},label={Text(if(manualPair)"Pairing port" else "Connection / TCP port")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {Checkbox(checked=tls,onCheckedChange={tls=it});Text("Wireless TLS port",modifier=Modifier.padding(top=12.dp))}
            OutlinedButton(enabled=!state.busy && state.status==null,onClick={
                val number=port.toIntOrNull();if(number==null || number !in 1..65535)controller.notice("Enter a port between 1 and 65535")
                else permitted(false){controller.startTcp(host,number,tls)}
            },modifier=Modifier.fillMaxWidth()){Text("Start using this port")}
            TextButton(onClick={manualPair=!manualPair}){Text("Manual pairing (split screen)")}
            if(manualPair) {
                Text("Keep Settings’ pairing-code dialog open in split screen while entering its pairing port and code here.",style=MaterialTheme.typography.bodySmall)
                OutlinedTextField(value=code,onValueChange={code=it.filter{c->c in '0'..'9'}.take(6)},label={Text("Six-digit pairing code")},visualTransformation=PasswordVisualTransformation(),singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword))
                Button(enabled=!state.busy,onClick={
                    val number=port.toIntOrNull()
                    if(number==null || code.length!=6)controller.notice("Enter the pairing port and six-digit code")
                    else {val ephemeral=code;code="";permitted(false){controller.pair(host,number,ephemeral)}}
                }){Text("Pair and start")}
            }
            if(state.busy)OutlinedButton(onClick=controller::cancelActivation){Text("Cancel activation")}
            state.message?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
        }
    },confirmButton={TextButton(onClick=onDismiss){Text("Close")}})
}
