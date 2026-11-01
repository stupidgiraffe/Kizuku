package io.github.stupidgiraffe.kizuku.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The single scope ledger used by both protocol endpoints. All access is synchronized. */
public final class GrantLedger {
    public enum Lifetime { ONCE, SESSION, PERMANENT }
    public record Grant(ClientIdentity identity, long mask) {}
    private final Map<ClientIdentity, long[]> grants = new HashMap<>();
    public synchronized void grant(ClientIdentity client, long mask, Lifetime lifetime) {
        Capability.validate(mask);
        long[] scopes = grants.computeIfAbsent(client, ignored -> new long[3]);
        // A new decision replaces the lifetime of just the selected scopes.
        for (int i = 0; i < scopes.length; i++) scopes[i] &= ~mask;
        scopes[lifetime.ordinal()] |= mask;
    }
    public synchronized long capabilities(ClientIdentity client) {
        long[] scopes = grants.get(client);
        return scopes == null ? 0 : scopes[0] | scopes[1] | scopes[2];
    }
    public synchronized boolean allows(ClientIdentity client, long mask) {
        Capability.validate(mask);
        return (capabilities(client) & mask) == mask;
    }
    public synchronized boolean consume(ClientIdentity client, long mask) {
        if (!allows(client, mask)) return false;
        grants.get(client)[Lifetime.ONCE.ordinal()] &= ~mask;
        return true;
    }
    public synchronized void revoke(ClientIdentity client, long mask) {
        Capability.validate(mask);
        long[] scopes = grants.get(client);
        if (scopes == null) return;
        for (int i = 0; i < scopes.length; i++) scopes[i] &= ~mask;
        if ((scopes[0] | scopes[1] | scopes[2]) == 0) grants.remove(client);
    }
    public synchronized void revokePrincipal(int uid, String packageName, long mask) {
        for (ClientIdentity identity : new ArrayList<>(grants.keySet())) {
            if (identity.uid() == uid && identity.packageName().equals(packageName)) revoke(identity, mask);
        }
    }
    public synchronized List<Grant> permanentGrants() {
        List<Grant> result = new ArrayList<>();
        grants.forEach((identity, scopes) -> {
            if (scopes[2] != 0) result.add(new Grant(identity, scopes[2]));
        });
        return result;
    }
    public synchronized GrantLedger copy() {
        GrantLedger copy = new GrantLedger();
        grants.forEach((identity, scopes) -> copy.grants.put(identity, scopes.clone()));
        return copy;
    }
}
