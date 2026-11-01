package io.github.stupidgiraffe.kizuku

import android.content.Context
import android.os.Bundle
import io.github.stupidgiraffe.kizuku.api.AndroidIdentity
import io.github.stupidgiraffe.kizuku.core.ClientIdentity
import io.github.stupidgiraffe.kizuku.core.GrantLedger
import org.json.JSONArray
import org.json.JSONObject

/** Only manager code writes approval decisions. Exported provider calls can only read/prune. */
object AuthorizationStore {
    private fun prefs(context: Context) = context.getSharedPreferences("authorization", 0)
    @Synchronized fun load(context: Context): String {
        val current = prefs(context).getString("grants", "[]") ?: "[]"
        val saved = JSONArray(current)
        val valid = JSONArray()
        for (i in 0 until saved.length()) {
            val entry = saved.getJSONObject(i)
            val identity = runCatching { AndroidIdentity.decode(entry) }.getOrNull() ?: continue
            if (runCatching { AndroidIdentity.resolve(context.packageManager, identity.uid(), identity.packageName()) == identity }.getOrDefault(false)) valid.put(entry)
        }
        val result = valid.toString()
        if (result != current) check(prefs(context).edit().putString("grants", result).commit()) { "Cannot invalidate obsolete authorization" }
        return result
    }
    fun verify(context: Context, snapshot: Bundle): ClientIdentity {
        val expected = AndroidIdentity.decode(JSONObject(requireNotNull(snapshot.getString("identity"))))
        val actual = AndroidIdentity.resolve(context.packageManager, expected.uid(), expected.packageName())
        check(expected == actual) { "App identity changed. Review a fresh permission request." }
        return expected
    }
    @Synchronized fun decide(context: Context, identity: ClientIdentity, mask: Long, lifetime: Int?) {
        val entries = JSONArray(load(context)); val ledger = GrantLedger()
        for (i in 0 until entries.length()) { val entry=entries.getJSONObject(i); ledger.grant(AndroidIdentity.decode(entry),entry.getLong("mask"),GrantLedger.Lifetime.PERMANENT) }
        ledger.revokePrincipal(identity.uid(), identity.packageName(), mask)
        if (lifetime == GrantLedger.Lifetime.PERMANENT.ordinal) ledger.grant(identity,mask,GrantLedger.Lifetime.PERMANENT)
        val result = JSONArray()
        ledger.permanentGrants().forEach { grant -> result.put(AndroidIdentity.encode(grant.identity()).put("mask",grant.mask())) }
        check(prefs(context).edit().putString("grants",result.toString()).commit()) { "Cannot save authorization decision" }
    }
    fun safeMode(context: Context, enabled: Boolean) { check(prefs(context).edit().putBoolean("safe_mode",enabled).commit()) }
}
