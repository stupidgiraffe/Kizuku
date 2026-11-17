package io.github.stupidgiraffe.kizuku

import android.app.admin.DevicePolicyManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import io.github.stupidgiraffe.kizuku.api.AndroidIdentity
import io.github.stupidgiraffe.kizuku.core.ClientIdentity
import org.json.JSONObject

/**
 * Dhizuku 2.4+ discovery and package-access delegation. Policy calls run as the actual
 * Android owner via public framework APIs. There is no raw Binder forwarding.
 */
class DhizukuProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val app = requireNotNull(context).applicationContext
        if (extras?.getBinder("client") == null) return null
        val caller = callingPackage ?: return null
        val uid = Binder.getCallingUid()
        val identity = when (method) {
            "client" -> DhizukuAuthorization.identity(app, uid, caller)
            "legacy-client" -> {
                if (caller != DhizukuAuthorization.COMPANION ||
                    app.packageManager.checkSignatures(app.packageName, caller) != PackageManager.SIGNATURE_MATCH
                ) throw SecurityException("Wrong Dhizuku companion identity")
                DhizukuAuthorization.identity(app, extras.getInt("uid", -1), extras.getString("package") ?: return null)
            }
            else -> return null
        }
        val token = Binder.clearCallingIdentity()
        return try {
            val owner = DeviceOwnerPolicies(app)
            if (!owner.enrolled) null else Bundle().apply {
                putBinder("dhizuku_binder", DhizukuEndpoint(app, owner, identity))
            }
        } finally { Binder.restoreCallingIdentity(token) }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
}

private class DhizukuEndpoint(
    private val app: Context,
    private val owner: DeviceOwnerPolicies,
    private val identity: ClientIdentity,
) : Binder() {
    init { attachInterface(null, DESCRIPTOR) }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == INTERFACE_TRANSACTION) return super.onTransact(code, data, reply, flags)
        if (reply == null || flags and IBinder.FLAG_ONEWAY != 0 || data.dataSize() > 8192 || data.hasFileDescriptors()) {
            throw SecurityException("Invalid Dhizuku request")
        }
        if (Binder.getCallingUid() != identity.uid() || !DhizukuAuthorization.verified(app, identity)) {
            throw SecurityException("Dhizuku client identity changed")
        }
        data.enforceInterface(DESCRIPTOR)
        val caller = clearCallingIdentity()
        try {
            check(owner.enrolled) { "Android owner role is inactive" }
            when (code - FIRST_CALL_TRANSACTION) {
                0 -> { requireEmpty(data); reply.writeNoException(); reply.writeInt(5) }
                1 -> { requireEmpty(data); reply.writeNoException(); reply.writeString("5.0-kizuku") }
                2 -> {
                    requireEmpty(data)
                    val granted = DhizukuAuthorization.approved(app, identity)
                    if (!granted) DhizukuAuthorization.request(identity)
                    reply.writeNoException()
                    reply.writeInt(if (granted) 1 else 0)
                }
                15, 16 -> {
                    if (data.readString() != identity.packageName()) throw SecurityException("Scope requests apply to the calling package")
                    val scopes = if (code - FIRST_CALL_TRANSACTION == 16) data.createStringArray() else null
                    requireEmpty(data)
                    val result = DhizukuAuthorization.accessScopes(app, identity, scopes, set = code - FIRST_CALL_TRANSACTION == 16)
                    reply.writeNoException()
                    if (result != null) reply.writeStringArray(result)
                }
                else -> throw UnsupportedOperationException("This Dhizuku operation is unavailable")
            }
            return true
        } finally { restoreCallingIdentity(caller) }
    }

    private fun requireEmpty(data: Parcel) {
        require(data.dataAvail() == 0) { "Unexpected Dhizuku request data" }
    }

    private companion object {
        const val DESCRIPTOR = "com.rosan.dhizuku.aidl.IDhizuku"
    }
}

internal object DhizukuAuthorization {
    const val COMPANION = "com.rosan.dhizuku"
    private const val SCOPE = DevicePolicyManager.DELEGATION_PACKAGE_ACCESS
    private const val TIMEOUT_MS = 30_000L
    private val requests = LinkedHashMap<Int, Pair<ClientIdentity, Long>>()

    fun identity(context: Context, uid: Int, name: String): ClientIdentity {
        val installed = AndroidIdentity.resolve(context.packageManager, uid, name)
        if (installed.members().size != 1 || name == context.packageName || name == COMPANION ||
            name == CompanionInstaller.PACKAGE
        ) throw SecurityException("The Dhizuku client needs an independent package UID")
        return installed
    }

    fun verified(context: Context, client: ClientIdentity) = runCatching {
        identity(context, client.uid(), client.packageName()) == client
    }.getOrDefault(false)

    @Synchronized fun request(identity: ClientIdentity) {
        val now = SystemClock.elapsedRealtime()
        requests.entries.removeAll { now >= it.value.second }
        if (requests.size < 64 || requests.containsKey(identity.uid())) {
            requests[identity.uid()] = identity to now + TIMEOUT_MS
        }
    }

    @Synchronized fun pending(context: Context, uid: Int): ClientIdentity? {
        val request = requests[uid] ?: return null
        return request.first.takeIf {
            SystemClock.elapsedRealtime() < request.second && verified(context, it)
        }
    }

    @Synchronized fun approve(context: Context, client: ClientIdentity, role: DeviceOwnerPolicies.Role) {
        check(pending(context, client.uid()) == client) { "The client request expired. Request access again." }
        check(DeviceOwnerPolicies(context).role == role) { "Android owner role changed. Request access again." }
        val encoded = AndroidIdentity.encode(client).put("owner_role", role.name).toString()
        check(preferences(context).edit().putString(client.packageName(), encoded).commit()) {
            "Could not record Dhizuku approval."
        }
        requests.remove(client.uid())
    }

    fun approved(context: Context, client: ClientIdentity): Boolean {
        val encoded = preferences(context).getString(client.packageName(), null) ?: return false
        return runCatching {
            val record = JSONObject(encoded)
            record.optString("owner_role") == DeviceOwnerPolicies(context).role?.name &&
                AndroidIdentity.decode(record) == client && verified(context, client)
        }.getOrDefault(false)
    }

    @Synchronized fun accessScopes(context: Context, client: ClientIdentity, requested: Array<String>?, set: Boolean): Array<String>? {
        if (!approved(context, client)) throw SecurityException("Approve Dhizuku package access in Kizuku first")
        if (set) require(requested != null && requested.size <= 1 && requested.all { it == SCOPE }) {
            "Dhizuku package access is the available delegation"
        }
        val owner = DeviceOwnerPolicies(context)
        check(owner.enrolled)
        val dpm = checkNotNull(context.getSystemService(DevicePolicyManager::class.java))
        val scopes = dpm.getDelegatedScopes(owner.admin, client.packageName())
        if (!set) return scopes.filter { it == SCOPE }.toTypedArray()
        val new = scopes.filterNot { it == SCOPE } + requireNotNull(requested).toList()
        if (scopes != new) dpm.setDelegatedScopes(owner.admin, client.packageName(), new)
        return null
    }

    @Synchronized fun revoke(context: Context, name: String) {
        if (!preferences(context).contains(name)) return
        val owner = DeviceOwnerPolicies(context)
        if (owner.enrolled) {
            val dpm = checkNotNull(context.getSystemService(DevicePolicyManager::class.java))
            val scopes = dpm.getDelegatedScopes(owner.admin, name)
            if (SCOPE in scopes) dpm.setDelegatedScopes(owner.admin, name, scopes.filterNot { it == SCOPE })
        }
        check(preferences(context).edit().remove(name).commit()) { "Could not remove Dhizuku approval." }
    }

    fun approvals(context: Context): List<String> = preferences(context).all.keys.sorted()

    @Synchronized fun revokeAll(context: Context) {
        approvals(context).forEach { revoke(context, it) }
    }

    @Synchronized fun ownerDisabled(context: Context) {
        requests.clear()
        preferences(context).edit().clear().commit()
    }

    private fun preferences(context: Context) = context.getSharedPreferences("dhizuku_authorization", Context.MODE_PRIVATE)
}
