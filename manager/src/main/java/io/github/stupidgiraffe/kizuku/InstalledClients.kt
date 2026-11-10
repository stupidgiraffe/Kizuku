package io.github.stupidgiraffe.kizuku

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Bundle
import io.github.stupidgiraffe.kizuku.api.AndroidIdentity
import io.github.stupidgiraffe.kizuku.api.Protocol
import io.github.stupidgiraffe.kizuku.core.Capability
import io.github.stupidgiraffe.kizuku.core.ClientIdentity
import java.text.Collator

/** Installed clients and the evidence for each independently managed permission. */
object InstalledClients {
    private const val SHIZUKU_PERMISSION = "moe.shizuku.manager.permission.API_V23"
    private const val AXERON_PROVIDER_PERMISSION = "android.permission.INTERACT_ACROSS_USERS_FULL"
    private const val NATIVE_METADATA = "io.github.stupidgiraffe.kizuku.API"
    private val packageFlags = PackageManager.GET_PERMISSIONS or PackageManager.GET_META_DATA or PackageManager.GET_PROVIDERS

    @Suppress("DEPRECATION")
    fun list(context: Context, live: List<Bundle>, connected: Boolean): List<Bundle> {
        val pm = context.packageManager
        val attached = live.filter { it.getBoolean("verified") }.associateBy { it.getString("package") to it.getInt("uid") }
        val remembered = AuthorizationStore.rememberedGrants(context).associateBy { it.first.packageName() }
        val collator = Collator.getInstance()
        return pm.getInstalledPackages(packageFlags).mapNotNull { info ->
            val pkg = info.packageName
            if (isManager(pkg)) return@mapNotNull null
            val application = info.applicationInfo ?: return@mapNotNull null
            val declared = manifestScopes(info)
            val connectedClient = attached[pkg to application.uid]
            val savedGrant = remembered[pkg]
            if (declared == 0L && connectedClient == null && savedGrant == null) return@mapNotNull null

            val identity = try {
                AndroidIdentity.resolve(pm, application.uid, pkg)
            } catch (_: Exception) {
                return@mapNotNull null
            }
            val identityJson = AndroidIdentity.encode(identity).toString()
            val currentClient = connectedClient?.takeIf { it.getString("identity") == identityJson }
            val saved = savedGrant?.takeIf { it.first == identity }?.second ?: 0L
            val assigned = currentClient?.let { it.getLong("assigned", it.getLong("granted")) } ?: 0L
            val requested = currentClient?.getLong("requested") ?: 0L
            val available = declared or assigned or requested or saved
            if (available == 0L) return@mapNotNull null
            val label = runCatching { pm.getApplicationLabel(application).toString() }.getOrDefault(pkg)
                .filterNot { Character.getType(it) == Character.FORMAT.toInt() || it.isISOControl() }
                .take(80).ifBlank { pkg }

            Bundle().apply {
                putString("package", pkg)
                putString("label", label)
                putLong("package_update", info.lastUpdateTime)
                putInt("uid", identity.uid())
                putString("identity", identityJson)
                putString("certificate", identity.members().getValue(pkg).first())
                putStringArray("shared_uid", identity.members().keys.toTypedArray())
                putBoolean("verified", true)
                putLong("manifest_scopes", declared)
                putLong("available_scopes", available)
                putLong("granted", if (connected) currentClient?.getLong("granted") ?: 0L else saved)
                putLong("assigned", assigned)
                putLong("remembered", saved)
                putLong("requested", requested)
                putBoolean("saved_only", !connected && saved != 0L)
            }
        }.sortedWith { first, second ->
            collator.compare(first.getString("label").orEmpty(), second.getString("label").orEmpty())
                .takeIf { it != 0 } ?: first.getString("package").orEmpty().compareTo(second.getString("package").orEmpty())
        }
    }

    @Suppress("DEPRECATION")
    internal fun declaresShizuku(context: Context, identity: ClientIdentity): Boolean {
        if (isManager(identity.packageName())) return false
        val info = context.packageManager.getPackageInfo(identity.packageName(), PackageManager.GET_PERMISSIONS)
        return info.applicationInfo?.uid == identity.uid() &&
            info.requestedPermissions?.contains(SHIZUKU_PERMISSION) == true
    }

    private fun manifestScopes(info: PackageInfo): Long {
        var scopes = 0L
        if (info.requestedPermissions?.contains(SHIZUKU_PERMISSION) == true) scopes = scopes or Capability.SHIZUKU
        if (info.applicationInfo?.metaData?.containsKey(NATIVE_METADATA) == true) scopes = scopes or Capability.DIAGNOSTICS
        if (info.providers.orEmpty().any { provider ->
                provider.authority == "${info.packageName}.axeron" && provider.exported && provider.enabled &&
                    provider.readPermission == AXERON_PROVIDER_PERMISSION && provider.writePermission == AXERON_PROVIDER_PERMISSION
            }) scopes = scopes or Capability.AXERON
        return scopes
    }

    private fun isManager(packageName: String) = packageName == Protocol.MANAGER || packageName == CompanionInstaller.PACKAGE
}
