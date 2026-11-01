package io.github.stupidgiraffe.kizuku

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import io.github.stupidgiraffe.kizuku.api.Protocol
import io.github.stupidgiraffe.kizuku.core.RecoveryPolicy
import io.github.stupidgiraffe.kizuku.adb.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeUnit

data class CoreState(val phase:String="Not connected",val status:Bundle?=null,val apps:List<Bundle> = emptyList(),val message:String?=null,val busy:Boolean=false,val safeMode:Boolean=false)
data class AdbState(val stage:String="Idle",val pairing:AdbEndpoint?=null,val message:String?=null,val paired:Boolean=false)

/** One application-scoped activation owner shared by the UI and pairing notification service. */
class ActivationController private constructor(private val app:Application) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val mutable=MutableStateFlow(CoreState())
    val state=mutable.asStateFlow()
    private val adbMutable=MutableStateFlow(AdbState(paired=app.getSharedPreferences("adb",0).getBoolean("paired",false)))
    val adb=adbMutable.asStateFlow()
    private var wanted=false
    private var recovery=false
    private var attempts=0
    private var operation:Job?=null
    private var operationVersion=0
    private var recoveryJob:Job?=null
    private var cancelIo:(()->Unit)?=null
    private var cancelLateHostUntil=0L
    private var mode="external"
    private var tcp:AdbEndpoint?=null
    private val key by lazy { AdbKey(PreferenceAdbKeyStore(app.getSharedPreferences("adb",0))) }
    init { scope.launch {
        CoreConnection.revision.collect {
            val server=CoreConnection.service
            if(server!=null && SystemClock.elapsedRealtime()<cancelLateHostUntil) {
                cancelLateHostUntil=0;withContext(Dispatchers.IO){runCatching { server.shutdown() }}
            }
            refresh()
            if(wanted && recovery && CoreConnection.service==null && !mutable.value.busy) scheduleRecovery()
        }
    } }
    suspend fun refresh()=withContext(Dispatchers.IO) {
        val server=CoreConnection.service
        var snapshot:Bundle?=null
        var live=emptyList<Bundle>()
        var message=mutable.value.message
        if(server!=null) try {snapshot=server.status();live=server.applications().toList();attempts=0} catch(e:Exception){message="Core disconnected. Start it again."}
        val apps=InstalledClients.list(app,live,snapshot!=null)
        val safe=snapshot?.getBoolean("safe_mode") ?: app.getSharedPreferences("authorization",0).getBoolean("safe_mode",false)
        mutable.value=mutable.value.copy(phase=if(snapshot!=null) "Connected" else if(mutable.value.busy) mutable.value.phase else if(wanted) "Disconnected" else "Not connected",status=snapshot,apps=apps,message=message,safeMode=safe)
    }
    private fun runOperation(label:String,block:suspend CoroutineScope.()->Unit):Job? {
        if(mutable.value.busy)return null
        cancelLateHostUntil=0
        val version=++operationVersion
        return scope.launch {
            mutable.value=mutable.value.copy(phase=if(CoreConnection.service!=null) "Connected" else label,busy=true,message=null)
            try { block();refresh() }
            catch(e:CancellationException){throw e}
            catch(e:Exception) {
                if(version!=operationVersion)return@launch
                val message=when(e) {
                    is AdbPairingRequiredException -> "This ADB key is not paired. Pair the device again in Wireless debugging."
                    is AdbInvalidPairingCodeException -> "The pairing code was rejected. Open a new pairing code in Settings and retry."
                    is java.net.SocketTimeoutException -> "ADB timed out. Check Wireless debugging, the current port and the authorization prompt."
                    else -> e.message?.take(240) ?: "Activation failed. Check debugging settings and try again."
                }
                mutable.value=mutable.value.copy(phase=if(CoreConnection.service!=null) "Connected" else "Failed",status=if(CoreConnection.service!=null)mutable.value.status else null,message=message)
                adbMutable.value=adbMutable.value.copy(stage="Failed",message=message)
            } finally {
                if(version==operationVersion){cancelIo=null;mutable.value=mutable.value.copy(busy=false)
                    if(wanted && recovery && CoreConnection.service==null) scheduleRecovery()}
            }
        }.also { operation=it }
    }
    fun startRoot(recovering:Boolean=false):Job? {
        if(CoreConnection.service!=null || mutable.value.busy)return null
        wanted=true;mode="root";if(!recovering)attempts=0
        return runOperation("Starting root") {
            withContext(Dispatchers.IO) {
                val command=launchCommand(app.applicationInfo.sourceDir)+" >/dev/null 2>&1 </dev/null &"
                val process=ProcessBuilder("su","-c",command).redirectErrorStream(true).start()
                cancelIo={process.destroyForcibly()}
                try {
                    if(!process.waitFor(15000,TimeUnit.MILLISECONDS))error("Root request timed out. Check your root manager.")
                    check(process.exitValue()==0){"Root access was denied or su is unavailable."}
                } finally {process.destroy()}
            }
            awaitHost()
        }
    }
    fun startWireless(recovering:Boolean=false):Job? {
        if(CoreConnection.service!=null || mutable.value.busy)return null
        wanted=true;mode="wireless";if(!recovering)attempts=0
        return runOperation("Finding Wireless ADB") {
            check(Build.VERSION.SDK_INT>=30){"Wireless pairing requires Android 11 or later. Use TCP or computer ADB on this Android version."}
            adbMutable.value=adbMutable.value.copy(stage="Finding connection",message=null)
            connectWireless()
        }
    }
    fun startTcp(host:String,port:Int,tls:Boolean=false):Job? {
        if(CoreConnection.service!=null || mutable.value.busy)return null
        wanted=true;mode=if(tls)"wireless" else "tcp";tcp=AdbEndpoint(host,port);attempts=0
        return runOperation("Connecting ADB") {startAdb(requireNotNull(tcp),tls)}
    }
    fun discoverPairing():Job?=runOperation("Waiting for pairing") {
        check(Build.VERSION.SDK_INT>=30){"Wireless pairing requires Android 11 or later"}
        adbMutable.value=adbMutable.value.copy(stage="Finding pairing",pairing=null,message=null)
        val endpoint=AdbDiscovery(app).find(AdbDiscovery.PAIRING,110000)
        adbMutable.value=adbMutable.value.copy(stage="Waiting for code",pairing=endpoint)
    }
    fun pair(host:String,port:Int,code:String):Job? {
        if(adbMutable.value.stage=="Finding pairing")cancelActivation()
        return runOperation("Pairing") {
            check(Build.VERSION.SDK_INT>=30){"Wireless pairing requires Android 11 or later"}
            AdbDiscovery.checkLocalNetworkPermission(app)
            adbMutable.value=adbMutable.value.copy(stage="Pairing",message=null)
            withContext(Dispatchers.IO) {
                if(Build.VERSION.SDK_INT<30)error("Wireless pairing requires Android 11 or later")
                AdbPairingClient(host,port,code,key).use { client->
                    cancelIo=client::cancel
                    ensureActive();check(client.start()){ "Pairing failed. Open a new code in Settings and retry." }
                }
            }
            check(app.getSharedPreferences("adb",0).edit().putBoolean("paired",true).commit())
            adbMutable.value=adbMutable.value.copy(stage=if(CoreConnection.service!=null)"Paired" else "Finding connection",paired=true,message="Pairing succeeded")
            if(CoreConnection.service==null) {
                wanted=true;mode="wireless";attempts=0
                adbMutable.value=adbMutable.value.copy(stage="Finding connection")
                connectWireless()
            }
        }
    }
    private suspend fun connectWireless() {
        val failed=mutableSetOf<AdbEndpoint>()
        repeat(3) { attempt ->
            val endpoint=AdbDiscovery(app).find(AdbDiscovery.CONNECT,exclude=failed)
            try {startAdb(endpoint,true);return}
            catch(e:AdbTransportUnavailable) {failed+=endpoint;if(attempt==2)throw e}
        }
    }
    private suspend fun startAdb(endpoint:AdbEndpoint,tls:Boolean) {
        AdbDiscovery.checkLocalNetworkPermission(app)
        adbMutable.value=adbMutable.value.copy(stage="Connecting")
        withContext(Dispatchers.IO) {
            AdbClient(endpoint.host,endpoint.port,key,tls).use { client->
                cancelIo=client::close
                ensureActive();try {client.connect()}catch(e:java.io.IOException){throw AdbTransportUnavailable(e)};ensureActive()
                // All activation methods launch the same installed APK entry point.
                client.command("shell:"+launchCommand(app.applicationInfo.sourceDir)+" >/data/local/tmp/kizuku-start.log 2>&1 </dev/null &")
            }
        }
        awaitHost()
        adbMutable.value=adbMutable.value.copy(stage="Connected",message="Kizuku core connected")
    }
    private suspend fun awaitHost() {
        withTimeoutOrNull(15000) {while(CoreConnection.service==null)delay(100)}
            ?: error("ADB connected, but the core did not attach. Check the startup log in diagnostics.")
        check(CoreConnection.service?.asBinder()?.pingBinder()==true){"Core died during startup"}
    }
    fun cancelActivation() {
        wanted=false;recoveryJob?.cancel()
        if(mutable.value.busy)cancelLateHostUntil=SystemClock.elapsedRealtime()+15000
        operationVersion++;cancelIo?.invoke();operation?.cancel();cancelIo=null
        mutable.value=mutable.value.copy(busy=false,phase=if(CoreConnection.service!=null)"Connected" else "Not connected")
        adbMutable.value=adbMutable.value.copy(stage="Cancelled",pairing=null,message="Cancelled")
    }
    fun stop(){wanted=false;recovery=false;recoveryJob?.cancel();cancelActivation();CoreConnection.service?.let {scope.launch(Dispatchers.IO){runCatching{it.shutdown()}}}}
    fun setRecovery(enabled:Boolean){recovery=enabled;attempts=0;if(!enabled)recoveryJob?.cancel()}
    private fun scheduleRecovery() {
        if(recoveryJob?.isActive==true)return
        recoveryJob=scope.launch {
            while(wanted && recovery && CoreConnection.service==null && !mutable.value.busy) {
                val transport=mode=="root" || (mode=="wireless" && Build.VERSION.SDK_INT>=30 && Settings.Global.getInt(app.contentResolver,"adb_wifi_enabled",0)==1)
                val wait=RecoveryPolicy.retryDelay(wanted,false,transport,attempts++)
                if(wait<0)break
                delay(wait)
                if(wanted && recovery && CoreConnection.service==null) {
                    if(mode=="root")startRoot(true)?.join() else if(mode=="wireless")startWireless(true)?.join()
                }
            }
        }
    }
    fun action(block:(io.github.stupidgiraffe.kizuku.api.IKizukuService)->Unit){scope.launch(Dispatchers.IO){try{block(requireNotNull(CoreConnection.service){"Start the core first"});refresh()}catch(e:Exception){notice(e.message?.take(240)?:"Operation failed")}}}
    fun decide(snapshot:Bundle,mask:Long,lifetime:Int?){scope.launch(Dispatchers.IO){try{
        require(mask>0 && mask and Protocol.SUPPORTED.inv()==0L){"Unsupported capability"}
        val identity=AuthorizationStore.verify(app,snapshot)
        val service=CoreConnection.service
        check(service!=null || lifetime==null || lifetime==2){"Start the core before granting once or session access."}
        AuthorizationStore.decide(app,identity,mask,lifetime)
        if(lifetime==null)service?.revoke(identity.packageName(),identity.uid(),mask)
        else service?.grant(identity.packageName(),identity.uid(),mask,lifetime,identity.fingerprint())
        refresh()
    }catch(e:Exception){notice(e.message?.take(240)?:"Authorization failed")}}}
    fun safeMode(enabled:Boolean){scope.launch(Dispatchers.IO){try{AuthorizationStore.safeMode(app,enabled);CoreConnection.service?.setSafeMode(enabled);refresh()}catch(e:Exception){notice(e.message?:"Cannot update safe mode")}}}
    fun notice(message:String){mutable.value=mutable.value.copy(message=message)}
    companion object {
        @Volatile private var instance:ActivationController?=null
        fun get(context:Context):ActivationController=instance ?: synchronized(this){instance ?: ActivationController(context.applicationContext as Application).also{instance=it}}
        private fun quote(value:String)="'"+value.replace("'","'\\''")+"'"
        fun launchCommand(apk:String)="CLASSPATH=${quote(apk)} /system/bin/app_process /system/bin --nice-name=kizuku_server ${Protocol.MANAGER}.server.ServerMain ${quote(apk)}"
        val pcCommand="adb shell 'apk=\$(pm path ${Protocol.MANAGER} | head -n1 | cut -d: -f2-); CLASSPATH=\"\$apk\" app_process /system/bin --nice-name=kizuku_server ${Protocol.MANAGER}.server.ServerMain \"\$apk\" >/dev/null 2>&1 </dev/null &'"
    }
}
