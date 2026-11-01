package io.github.stupidgiraffe.kizuku.adb

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.ArrayDeque

data class AdbEndpoint(val host:String,val port:Int)

/** One bounded local-device search. Stops its registration even if cancellation precedes start. */
class AdbDiscovery(private val context:Context) {
    suspend fun find(type:String,timeoutMillis:Long=15000,exclude:Set<AdbEndpoint> = emptySet()):AdbEndpoint {
        checkLocalNetworkPermission(context)
        return try { withTimeout(timeoutMillis) { withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                val manager=context.getSystemService(NsdManager::class.java)
                val handler=Handler(Looper.getMainLooper())
                var active=true;var registered=false;var resolving=false
                val queue=ArrayDeque<NsdServiceInfo>();val seen=mutableSetOf<String>()
                lateinit var listener:NsdManager.DiscoveryListener
                fun stop() {
                    active=false;queue.clear()
                    if(registered)runCatching { manager.stopServiceDiscovery(listener) }
                }
                fun fail(message:String) { if(continuation.isActive){stop();continuation.resumeWithException(IllegalStateException(message))} }
                lateinit var resolveNext:()->Unit
                resolveNext={
                    if(active && !resolving && queue.isNotEmpty()) {
                        val info=queue.removeFirst();resolving=true
                        try {
                            @Suppress("DEPRECATION")
                            manager.resolveService(info,object:NsdManager.ResolveListener {
                                override fun onResolveFailed(serviceInfo:NsdServiceInfo,errorCode:Int) { handler.post { resolving=false;resolveNext() } }
                                override fun onServiceResolved(serviceInfo:NsdServiceInfo) { handler.post {
                                    resolving=false
                                    @Suppress("DEPRECATION") val host=serviceInfo.host?.hostAddress
                                    if(active && host!=null && AdbEndpoint(host,serviceInfo.port) !in exclude && runCatching { LocalAdb.requireLocal(host,serviceInfo.port) }.isSuccess) {
                                        val endpoint=AdbEndpoint(host,serviceInfo.port)
                                        if(continuation.isActive){stop();continuation.resume(endpoint)}
                                    } else resolveNext()
                                } }
                            })
                        } catch(e:Exception){resolving=false;resolveNext()}
                    }
                }
                listener=object:NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(serviceType:String) { handler.post { registered=true;if(!active)runCatching { manager.stopServiceDiscovery(this) } } }
                    override fun onStartDiscoveryFailed(serviceType:String,errorCode:Int) { handler.post { fail("Wireless discovery unavailable ($errorCode). Enter the connection port manually.") } }
                    override fun onStopDiscoveryFailed(serviceType:String,errorCode:Int) { }
                    override fun onDiscoveryStopped(serviceType:String) { registered=false }
                    override fun onServiceFound(info:NsdServiceInfo) { handler.post {
                        if(active && seen.size<64 && seen.add(info.serviceName)){queue.add(info);resolveNext()}
                    } }
                    override fun onServiceLost(info:NsdServiceInfo) { handler.post { queue.removeAll { it.serviceName==info.serviceName };seen.remove(info.serviceName) } }
                }
                continuation.invokeOnCancellation { handler.post { stop() } }
                try { manager.discoverServices(type,NsdManager.PROTOCOL_DNS_SD,listener) }
                catch(e:Exception){stop();continuation.resumeWithException(e)}
            }
        } } } catch(e:TimeoutCancellationException) { throw IllegalStateException("No local Wireless Debugging port found. Check Wi-Fi and Wireless debugging, or enter its connection port manually.") }
    }
    companion object {
        const val PAIRING="_adb-tls-pairing._tcp"
        const val CONNECT="_adb-tls-connect._tcp"
        fun checkLocalNetworkPermission(context:Context) {
            if(Build.VERSION.SDK_INT>=37 && context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK)!=PackageManager.PERMISSION_GRANTED)
                throw SecurityException("Allow Local network access in Kizuku's permissions before using ADB.")
        }
    }
}
