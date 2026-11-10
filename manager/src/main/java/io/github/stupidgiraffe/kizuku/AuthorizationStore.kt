package io.github.stupidgiraffe.kizuku

import android.content.Context
import android.os.Bundle
import io.github.stupidgiraffe.kizuku.api.AndroidIdentity
import io.github.stupidgiraffe.kizuku.api.Protocol
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
        expected.verifyDisplay(snapshot.getInt("uid"), snapshot.getString("package"), snapshot.getString("certificate"), snapshot.getStringArray("shared_uid").orEmpty().toSet())
        val actual = AndroidIdentity.resolve(context.packageManager, expected.uid(), expected.packageName())
        check(expected == actual) { "App identity changed. Review a fresh permission request." }
        return expected
    }
    internal fun rememberedGrants(context: Context): List<Pair<ClientIdentity, Long>> {
        val entries = JSONArray(load(context))
        return List(entries.length()) { index ->
            val entry = entries.getJSONObject(index)
            AndroidIdentity.decode(entry) to entry.getLong("mask")
        }
    }
    fun decide(context: Context, identity: ClientIdentity, mask: Long, lifetime: Int?) {
        replaceRemembered(context, identity, mask, if (lifetime == GrantLedger.Lifetime.PERMANENT.ordinal) mask else 0L)
    }
    @Synchronized internal fun replaceRemembered(context: Context, identity: ClientIdentity, mask: Long, replacement: Long): Long {
        val ledger = GrantLedger()
        val saved = rememberedGrants(context)
        saved.forEach { (client, scopes) -> ledger.grant(client, scopes, GrantLedger.Lifetime.PERMANENT) }
        val previous = ledger.capabilities(identity) and mask
        if (previous == replacement) return previous
        ledger.revokePrincipal(identity.uid(), identity.packageName(), mask)
        if (replacement != 0L) ledger.grant(identity, replacement, GrantLedger.Lifetime.PERMANENT)
        val grants = ledger.permanentGrants()
        check(grants.size <= Protocol.MAX_CLIENTS || grants.size <= saved.size) {
            "Remembered app limit reached. Revoke an unused grant and retry."
        }
        val result = JSONArray()
        grants.forEach { grant -> result.put(AndroidIdentity.encode(grant.identity()).put("mask", grant.mask())) }
        check(prefs(context).edit().putString("grants", result.toString()).commit()) { "Cannot save authorization decision" }
        return previous
    }
    fun safeMode(context: Context, enabled: Boolean) { check(prefs(context).edit().putBoolean("safe_mode",enabled).commit()) }
}
