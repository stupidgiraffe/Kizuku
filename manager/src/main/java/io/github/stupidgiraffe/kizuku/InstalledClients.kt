package io.github.stupidgiraffe.kizuku

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import io.github.stupidgiraffe.kizuku.api.AndroidIdentity
import io.github.stupidgiraffe.kizuku.api.Protocol
import org.json.JSONArray

/** Installed-compatible apps plus remembered/native clients; does not require a first request. */
object InstalledClients {
    fun list(context:Context,live:List<Bundle>,connected:Boolean):List<Bundle> {
        val pm=context.packageManager
        val rows=live.associateBy { it.getString("package") }.toMutableMap()
        val saved=JSONArray(AuthorizationStore.load(context))
        val remembered=mutableMapOf<String,Long>()
        for(i in 0 until saved.length()) {
            val entry=saved.getJSONObject(i)
            runCatching { AndroidIdentity.decode(entry) }.getOrNull()?.let { identity -> remembered[identity.packageName()]=entry.getLong("mask") }
        }
        @Suppress("DEPRECATION") val installed=pm.getInstalledPackages(PackageManager.GET_PERMISSIONS or PackageManager.GET_META_DATA)
        installed.asSequence().filter { it.packageName!=Protocol.MANAGER && (
            it.requestedPermissions?.contains("moe.shizuku.manager.permission.API_V23")==true ||
                it.applicationInfo?.metaData?.containsKey("io.github.stupidgiraffe.kizuku.API")==true || remembered.containsKey(it.packageName))
        }.take(Protocol.MAX_CLIENTS).forEach { info ->
            if(!rows.containsKey(info.packageName)) runCatching {
                val identity=AndroidIdentity.resolve(pm,requireNotNull(info.applicationInfo).uid,info.packageName)
                rows[info.packageName]=Bundle().apply {
                    putString("package",identity.packageName());putInt("uid",identity.uid())
                    putString("identity",AndroidIdentity.encode(identity).toString());putBoolean("verified",true)
                    putString("certificate",identity.members()[identity.packageName()]!!.first())
                    putStringArray("shared_uid",identity.members().keys.toTypedArray())
                    putLong("granted",if(connected) 0 else remembered[info.packageName()] ?: 0)
                    putLong("requested",0);putBoolean("saved_only",!connected)
                }
            }
        }
        return rows.values.sortedBy { it.getString("package") }
    }
}
