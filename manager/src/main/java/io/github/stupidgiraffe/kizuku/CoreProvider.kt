package io.github.stupidgiraffe.kizuku

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import io.github.stupidgiraffe.kizuku.api.IKizukuService
import io.github.stupidgiraffe.kizuku.api.Protocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

object CoreConnection {
    @Volatile var service: IKizukuService? = null
        private set
    val revision = MutableStateFlow(0)
    val lifetime = Binder()
    fun changed() { revision.update { it + 1 } }
    @Synchronized fun attach(binder: IBinder, senderUid: Int) {
        val candidate = IKizukuService.Stub.asInterface(binder)
        val status = candidate.status()
        require(status.getInt("uid", -1) == senderUid && status.getInt("protocol") == Protocol.VERSION) { "Host UID does not match Android's authenticated caller" }
        check(senderUid != 2000 || service?.status()?.getInt("uid") != 0) { "An ADB host cannot replace a connected root host" }
        binder.linkToDeath({ synchronized(this) { if (service?.asBinder() == binder) { service = null; changed() } } }, 0)
        service = candidate
        changed()
    }
}

class CoreProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val app = requireNotNull(context)
        val callerUid = Binder.getCallingUid()
        if (method == "connect") return Bundle().apply { putBinder(Protocol.BINDER, CoreConnection.service?.asBinder()) }
        if (method == "legacy") {
            require(arg != null && app.packageManager.getPackagesForUid(callerUid)?.contains(arg) == true) { "Package does not own caller UID" }
            val token = Binder.clearCallingIdentity()
            return try { requireNotNull(CoreConnection.service) { "Core is not connected" }.deliverLegacy(arg); Bundle() }
            finally { Binder.restoreCallingIdentity(token) }
        }
        if (callerUid != 0 && callerUid != 2000) throw SecurityException("Only a root or ADB host may access controller storage")
        val token = Binder.clearCallingIdentity()
        return try {
            val prefs = app.getSharedPreferences("authorization", 0)
            when (method) {
                "attach" -> {
                    CoreConnection.attach(requireNotNull(extras?.getBinder(Protocol.BINDER)), callerUid)
                    Bundle().apply { putBinder("manager_lifetime", CoreConnection.lifetime) }
                }
                "load_grants" -> Bundle().apply {
                    putString("grants", AuthorizationStore.load(app)); putBoolean("safe_mode", prefs.getBoolean("safe_mode", false))
                }
                "legacy_bootstrap" -> {
                    val service = requireNotNull(CoreConnection.service) { "Core is not connected" }
                    if (service.status().getInt("uid", -1) != callerUid) throw SecurityException("UserService host UID mismatch")
                    Bundle().apply { putBinder(Protocol.BINDER, service.legacyBinder()) }
                }
                "changed" -> { CoreConnection.changed(); Bundle() }
                else -> throw IllegalArgumentException("Unknown controller method")
            }
        } finally { Binder.restoreCallingIdentity(token) }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
