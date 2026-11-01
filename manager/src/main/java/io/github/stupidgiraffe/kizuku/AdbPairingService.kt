package io.github.stupidgiraffe.kizuku

import android.app.*
import android.content.*
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import kotlinx.coroutines.*

/** Bounded notification entry keeps Android's Settings pairing dialog alive. No code is persisted. */
class AdbPairingService:Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val timeout=Handler(Looper.getMainLooper())
    private lateinit var controller:ActivationController
    private var sessionActive=false
    override fun onCreate() {
        super.onCreate();controller=ActivationController.get(this)
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,"ADB pairing",NotificationManager.IMPORTANCE_HIGH))
        startForeground(ID,notification("Open Wireless debugging → Pair device with pairing code",false))
        scope.launch {controller.adb.collect { state->
            val text=when(state.stage) {
                "Waiting for code" -> "Enter the six-digit code here while keeping Settings open"
                "Finding pairing" -> "Open Wireless debugging → Pair device with pairing code"
                "Pairing" -> "Pairing securely with this device…"
                "Finding connection","Connecting" -> "Paired. Starting the Kizuku core…"
                else -> state.message ?: state.stage
            }
            if(sessionActive) {
                val terminal=state.stage in setOf("Connected","Paired","Failed","Cancelled")
                manager.notify(ID,notification(text,state.stage=="Waiting for code",terminal))
                if(terminal){stopForeground(STOP_FOREGROUND_DETACH);stopSelf()}
            }
        } }
        timeout.postDelayed({controller.cancelActivation();stopSelf()},120000)
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        when(intent?.action) {
            BEGIN -> { sessionActive=true; if(controller.discoverPairing()==null)stopSelf() }
            SEND -> {
                val code=RemoteInput.getResultsFromIntent(intent)?.getCharSequence(CODE)?.toString().orEmpty()
                intent.removeExtra(CODE)
                val endpoint=controller.adb.value.pairing
                if(endpoint==null)controller.notice("Open a new pairing code in Settings first.")
                else controller.pair(endpoint.host,endpoint.port,code)
            }
            CANCEL -> {controller.cancelActivation();stopSelf()}
        }
        return START_NOT_STICKY
    }
    private fun notification(text:String,enterCode:Boolean,terminal:Boolean=false):Notification {
        val flags=PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open=PendingIntent.getActivity(this,ID,Intent(this,MainActivity::class.java),flags)
        val cancel=PendingIntent.getService(this,ID+1,Intent(this,AdbPairingService::class.java).setAction(CANCEL),flags)
        val builder=NotificationCompat.Builder(this,CHANNEL).setSmallIcon(io.github.stupidgiraffe.kizuku.R.drawable.ic_kizuku)
            .setContentTitle("Kizuku · Wireless ADB").setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open).setOngoing(!terminal).setAutoCancel(terminal).setOnlyAlertOnce(true)
        if(!terminal)builder.addAction(0,"Cancel",cancel)
        if(enterCode) {
            val mutable=PendingIntent.FLAG_UPDATE_CURRENT or if(Build.VERSION.SDK_INT>=31)PendingIntent.FLAG_MUTABLE else 0
            val send=PendingIntent.getService(this,ID+2,Intent(this,AdbPairingService::class.java).setAction(SEND),mutable)
            builder.addAction(NotificationCompat.Action.Builder(0,"Enter pairing code",send)
                .addRemoteInput(RemoteInput.Builder(CODE).setLabel("Six-digit code").build()).build())
        }
        return builder.build()
    }
    override fun onBind(intent:Intent?)=null
    override fun onTimeout(startId:Int){controller.cancelActivation();stopSelf()}
    override fun onDestroy(){timeout.removeCallbacksAndMessages(null);scope.cancel();super.onDestroy()}
    companion object {
        const val CHANNEL="kizuku_adb_pairing"
        const val ID=708
        const val BEGIN="io.github.stupidgiraffe.kizuku.PAIR_BEGIN"
        const val SEND="io.github.stupidgiraffe.kizuku.PAIR_CODE"
        const val CANCEL="io.github.stupidgiraffe.kizuku.PAIR_CANCEL"
        const val CODE="pairing_code"
    }
}
