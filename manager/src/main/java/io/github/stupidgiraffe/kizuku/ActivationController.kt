package io.github.stupidgiraffe.kizuku

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import io.github.stupidgiraffe.kizuku.api.Protocol
import io.github.stupidgiraffe.kizuku.core.Capability
import io.github.stupidgiraffe.kizuku.core.GrantLedger
import io.github.stupidgiraffe.kizuku.core.RecoveryPolicy
import io.github.stupidgiraffe.kizuku.adb.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

data class CoreState(val phase:String="Not connected",val status:Bundle?=null,val apps:List<Bundle> = emptyList(),val message:String?=null,val busy:Boolean=false,val safeMode:Boolean=false,val appsLoaded:Boolean=false,val appsError:String?=null)
data class AdbState(val stage:String="Idle",val pairing:AdbEndpoint?=null,val message:String?=null,val paired:Boolean=false)
internal data class RecoveryDiagnostics(
    val enabled: Boolean,
    val wanted: Boolean,
    val retryInProgress: Boolean,
    val transportEligible: Boolean,
)

internal enum class ShizukuBatchAction { GRANT, REVOKE }
internal data class ShizukuBatchResult(
    val action: ShizukuBatchAction,
    val coreUpdates: Int,
    val offlineUpdates: Int,
    val failures: List<Pair<String, String>>,
)
internal data class ShizukuBatchState(val running: Boolean = false, val result: ShizukuBatchResult? = null)

/** One application-scoped activation owner shared by the UI and pairing notification service. */
class ActivationController private constructor(private val app:Application) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val mutable=MutableStateFlow(CoreState())
    val state=mutable.asStateFlow()
    private val refreshMutex = Mutex()
    private val permissionMutex = Mutex()
    private val batchMutable = MutableStateFlow(ShizukuBatchState())
    internal val shizukuBatch = batchMutable.asStateFlow()
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
    suspend fun refresh() = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
            val server = CoreConnection.service
            var snapshot: Bundle? = null
            var live = emptyList<Bundle>()
            var connectionError: String? = null
            if (server != null) {
                try {
                    live = server.applications().toList()
                    snapshot = server.status()
                    attempts = 0
                } catch (_: Exception) {
                    live = emptyList()
                    connectionError = "Cannot read the core's current status. Check the connection."
                }
            }
            var appsError = connectionError
            val apps = try {
                InstalledClients.list(app, live, snapshot != null)
            } catch (e: Exception) {
                appsError = "Cannot load installed apps: ${e.message ?: "package or authorization lookup failed"}"
                emptyList()
            }
            val safe = snapshot?.getBoolean("safe_mode") ?: app.getSharedPreferences("authorization", 0).getBoolean("safe_mode", false)
            mutable.update { current ->
                val phase = when {
                    snapshot != null -> "Connected"
                    current.busy -> current.phase
                    wanted -> "Disconnected"
                    else -> "Not connected"
                }
                current.copy(phase = phase, status = snapshot, apps = apps, message = connectionError ?: current.message,
                    safeMode = safe, appsLoaded = appsError == null, appsError = appsError)
            }
        }
    }
    private fun runOperation(label:String,refreshAfter:Boolean=true,block:suspend CoroutineScope.()->Unit):Job? {
        if(mutable.value.busy)return null
        cancelLateHostUntil=0
        val version=++operationVersion
        return scope.launch {
            mutable.value=mutable.value.copy(phase=if(CoreConnection.service!=null) "Connected" else label,busy=true,message=null)
            try { block();if(refreshAfter)refresh() }
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
    // Discovery does not change the host. Finish on main without suspending for a
    // host refresh after making the notification's reply action available.
    fun discoverPairing():Job?=runOperation("Waiting for pairing",refreshAfter=false) {
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
                // Legacy ADB shell allocates a PTY even for commands. Ignore its hangup
                // before forking so the host survives closure of the startup stream.
                client.command("shell:trap '' HUP; "+launchCommand(app.applicationInfo.sourceDir)+" >/data/local/tmp/kizuku-start.log 2>&1 </dev/null &")
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
        if(CoreConnection.service==null) {
            wanted=false;recoveryJob?.cancel()
            if(mutable.value.busy)cancelLateHostUntil=SystemClock.elapsedRealtime()+15000
        }
        operationVersion++;cancelIo?.invoke();operation?.cancel();cancelIo=null
        mutable.value=mutable.value.copy(busy=false,phase=if(CoreConnection.service!=null)"Connected" else "Not connected")
        adbMutable.value=adbMutable.value.copy(stage="Cancelled",pairing=null,message="Cancelled")
    }
    fun stop(){wanted=false;recovery=false;recoveryJob?.cancel();cancelActivation();CoreConnection.service?.let {scope.launch(Dispatchers.IO){runCatching{it.shutdown()}}}}
    fun setRecovery(enabled:Boolean){recovery=enabled;attempts=0;if(!enabled)recoveryJob?.cancel()}
    internal fun recoveryDiagnostics() = RecoveryDiagnostics(
        enabled = recovery,
        wanted = wanted,
        retryInProgress = recoveryJob?.isActive == true,
        transportEligible = recoveryTransportEligible(),
    )
    private fun recoveryTransportEligible() = mode == "root" || (mode == "wireless" &&
        Build.VERSION.SDK_INT >= 30 && Settings.Global.getInt(app.contentResolver, "adb_wifi_enabled", 0) == 1)
    private fun scheduleRecovery() {
        if(recoveryJob?.isActive==true)return
        recoveryJob=scope.launch {
            while(wanted && recovery && CoreConnection.service==null && !mutable.value.busy) {
                val transport=recoveryTransportEligible()
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
    fun decide(snapshot: Bundle, mask: Long, lifetime: Int?) {
        scope.launch(Dispatchers.IO) {
            permissionMutex.withLock {
                try {
                    applyPermission(snapshot, mask, lifetime)
                } catch (e: Exception) {
                    notice(e.message?.take(240) ?: "Authorization failed")
                }
                refresh()
            }
        }
    }

    internal fun changeAllShizuku(action: ShizukuBatchAction, snapshots: List<Bundle>) {
        val previousBatch = batchMutable.value
        if (snapshots.isEmpty() || previousBatch.running ||
            !batchMutable.compareAndSet(previousBatch, ShizukuBatchState(running = true))) return
        scope.launch(Dispatchers.IO) {
            permissionMutex.withLock {
                var coreUpdates = 0
                var offlineUpdates = 0
                val failures = mutableListOf<Pair<String, String>>()
                val granting = action == ShizukuBatchAction.GRANT
                val lifetime = GrantLedger.Lifetime.PERMANENT.ordinal.takeIf { granting }
                snapshots.forEach { snapshot ->
                    try {
                        if (applyPermission(snapshot, Capability.SHIZUKU, lifetime, requireShizukuManifest = granting)) coreUpdates++
                        else offlineUpdates++
                    } catch (e: Exception) {
                        failures += snapshot.getString("package").orEmpty() to (e.message?.take(240) ?: "Authorization failed")
                    }
                }
                refresh()
                batchMutable.value = ShizukuBatchState(result = ShizukuBatchResult(action, coreUpdates, offlineUpdates, failures))
            }
        }
    }

    internal suspend fun approveAxeronRequest(snapshot: Bundle) = withContext(Dispatchers.IO) {
        permissionMutex.withLock {
            check(applyPermission(snapshot, Capability.AXERON, GrantLedger.Lifetime.PERMANENT.ordinal, requireLive = true)) {
                "Core disconnected before the Axeron grant was saved."
            }
            refresh()
        }
    }

    /** Applies an identity-verified decision and reports whether the current core acknowledged it. */
    private fun applyPermission(
        snapshot: Bundle,
        mask: Long,
        lifetime: Int?,
        requireShizukuManifest: Boolean = false,
        requireLive: Boolean = false,
    ): Boolean {
        require(mask > 0 && mask and Protocol.SUPPORTED.inv() == 0L) { "Unsupported capability" }
        require(lifetime == null || lifetime in 0..GrantLedger.Lifetime.PERMANENT.ordinal) { "Unsupported grant duration" }
        val identity = AuthorizationStore.verify(app, snapshot)
        if (requireShizukuManifest) {
            check(InstalledClients.declaresShizuku(app, identity)) { "App no longer declares Shizuku compatibility." }
        }
        val permanent = lifetime == GrantLedger.Lifetime.PERMANENT.ordinal
        val canSaveOffline = !requireLive && (lifetime == null || permanent)
        check(CoreConnection.service != null || canSaveOffline) { "Start the core before granting this access." }
        val replacement = if (permanent) mask else 0L
        val previous = AuthorizationStore.replaceRemembered(app, identity, mask, replacement)
        val service = CoreConnection.service
        if (service == null && canSaveOffline) return false

        try {
            val active = checkNotNull(service) { "Core disconnected before receiving the decision." }
            if (lifetime == null) active.revoke(identity.packageName(), identity.uid(), mask)
            else active.grant(identity.packageName(), identity.uid(), mask, lifetime, identity.fingerprint())
        } catch (e: Exception) {
            val reason = e.message?.take(140) ?: "Core did not acknowledge the change."
            if (lifetime == null) {
                throw IllegalStateException("Remembered access cleared. Live revocation unconfirmed. $reason", e)
            }
            if (replacement != previous) {
                try {
                    AuthorizationStore.replaceRemembered(app, identity, mask, previous)
                } catch (rollback: Exception) {
                    throw IllegalStateException("Grant unconfirmed and saved access could not be restored. Review the app's permission.", rollback)
                }
            }
            throw IllegalStateException("Grant unconfirmed. $reason", e)
        }
        return true
    }
    fun safeMode(enabled:Boolean){scope.launch(Dispatchers.IO){try{AuthorizationStore.safeMode(app,enabled);CoreConnection.service?.setSafeMode(enabled);refresh()}catch(e:Exception){notice(e.message?:"Cannot update safe mode")}}}
    fun notice(message:String){mutable.update { it.copy(message=message) }}
    companion object {
        @Volatile private var instance:ActivationController?=null
        fun get(context:Context):ActivationController=instance ?: synchronized(this){instance ?: ActivationController(context.applicationContext as Application).also{instance=it}}
        private fun quote(value:String)="'"+value.replace("'","'\\''")+"'"
        fun launchCommand(apk:String)="CLASSPATH=${quote(apk)} /system/bin/app_process /system/bin --nice-name=kizuku_server ${Protocol.MANAGER}.server.ServerMain ${quote(apk)}"
        val pcCommand="adb shell 'apk=\$(pm path ${Protocol.MANAGER} | head -n1 | cut -d: -f2-); CLASSPATH=\"\$apk\" app_process /system/bin --nice-name=kizuku_server ${Protocol.MANAGER}.server.ServerMain \"\$apk\" >/dev/null 2>&1 </dev/null &'"
    }
}
