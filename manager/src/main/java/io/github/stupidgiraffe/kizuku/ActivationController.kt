package io.github.stupidgiraffe.kizuku

import android.app.Application
import android.os.Bundle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.stupidgiraffe.kizuku.api.Protocol
import io.github.stupidgiraffe.kizuku.core.RecoveryPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

data class CoreState(
    val phase: String = "Stopped", val status: Bundle? = null,
    val apps: List<Bundle> = emptyList(), val message: String? = null, val busy: Boolean = false,
)

/** The sole owner of activation intent, connection truth and bounded root recovery. */
class ActivationController(app: Application) : AndroidViewModel(app) {
    private val mutable = MutableStateFlow(CoreState())
    val state = mutable.asStateFlow()
    private var wanted = false
    private var recovery = false
    private var attempts = 0
    private var recoveryJob: Job? = null
    init {
        viewModelScope.launch {
            CoreConnection.revision.collect {
                refresh()
                if (wanted && recovery && CoreConnection.service == null && !mutable.value.busy) {
                    scheduleRecovery()
                }
            }
        }
    }
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val service = CoreConnection.service
        if (service == null) { mutable.value = mutable.value.copy(phase = if (wanted) "Disconnected" else "Stopped", status = null, apps = emptyList()); return@withContext }
        try {
            val status = service.status()
            mutable.value = mutable.value.copy(phase = "Connected", status = status, apps = service.applications().toList(), busy = false, message = null)
            attempts = 0
        } catch (failure: Exception) { mutable.value = mutable.value.copy(phase = "Disconnected", status = null, message = failure.message?.take(240)) }
    }
    fun action(block: (io.github.stupidgiraffe.kizuku.api.IKizukuService) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try { block(requireNotNull(CoreConnection.service) { "Start the core first" }); refresh() }
            catch (failure: Exception) { mutable.value = mutable.value.copy(message = failure.message?.take(240) ?: "Operation failed") }
        }
    }
    fun startRoot(recovering: Boolean = false): Job? {
        if (mutable.value.busy || CoreConnection.service != null) return null
        wanted = true
        if (!recovering) attempts = 0
        return viewModelScope.launch {
            mutable.value = mutable.value.copy(phase = "Starting", busy = true, message = null)
            try {
                withContext(Dispatchers.IO) {
                    val apk = getApplication<Application>().applicationInfo.sourceDir
                    val command = launchCommand(apk) + " >/dev/null 2>&1 </dev/null &"
                    val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
                    if (!process.waitFor(15, TimeUnit.SECONDS)) { process.destroyForcibly(); error("Root request timed out. Check your root manager.") }
                    check(process.exitValue() == 0) { "Root access was denied or su is unavailable." }
                }
                // Bounded handshake wait, not an optimistic success flag.
                var waited = 0
                while (CoreConnection.service == null && waited++ < 60) delay(200)
                check(CoreConnection.service != null) { "Host did not connect. Check logcat -s KizukuServer." }
                refresh()
            } catch (failure: Exception) {
                mutable.value = mutable.value.copy(phase = "Failed", status = null, busy = false, message = failure.message?.take(240))
            } finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }
    private fun scheduleRecovery() {
        if (recoveryJob?.isActive == true) return
        recoveryJob = viewModelScope.launch {
            while (wanted && recovery && CoreConnection.service == null) {
                val wait = RecoveryPolicy.retryDelay(wanted, false, true, attempts++)
                if (wait < 0) break
                delay(wait)
                if (wanted && recovery && CoreConnection.service == null) startRoot(recovering = true)?.join()
            }
        }
    }
    fun stop() { wanted = false; recovery = false; recoveryJob?.cancel(); action { it.shutdown() } }
    fun setRecovery(enabled: Boolean) { recovery = enabled; attempts = 0; if (!enabled) recoveryJob?.cancel() }
    fun decide(snapshot: Bundle, mask: Long, lifetime: Int?) {
        action { service ->
            val app = getApplication<Application>()
            val identity = AuthorizationStore.verify(app, snapshot)
            // Persist the exact reviewed identity before changing live authority.
            AuthorizationStore.decide(app, identity, mask, lifetime)
            if (lifetime == null) service.revoke(identity.packageName(), identity.uid(), mask)
            else service.grant(identity.packageName(), identity.uid(), mask, lifetime, identity.fingerprint())
        }
    }
    fun safeMode(enabled: Boolean) { action { AuthorizationStore.safeMode(getApplication(), enabled); it.setSafeMode(enabled) } }
    fun notice(message: String) { mutable.value = mutable.value.copy(message = message) }
    companion object {
        private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
        fun launchCommand(apk: String) = "CLASSPATH=${quote(apk)} /system/bin/app_process /system/bin --nice-name=kizuku_server ${Protocol.MANAGER}.server.ServerMain ${quote(apk)}"
        val pcCommand = "adb shell 'apk=\$(pm path ${Protocol.MANAGER} | head -n1 | cut -d: -f2-); CLASSPATH=\"\$apk\" app_process /system/bin --nice-name=kizuku_server ${Protocol.MANAGER}.server.ServerMain \"\$apk\" >/dev/null 2>&1 </dev/null &'"
    }
}
