package io.github.stupidgiraffe.kizuku.core;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Map;
import java.util.Set;

public class GrantLedgerTest {
    private ClientIdentity identity(String certificate) {
        return new ClientIdentity(10123, "test.client", Map.of("test.client", Set.of(certificate)));
    }
    @Test public void legacyGrantDoesNotAuthorizeNativeDiagnosticsOrShell() {
        GrantLedger ledger = new GrantLedger();
        ClientIdentity client = identity("certificate-a");
        ledger.grant(client, Capability.SHIZUKU, GrantLedger.Lifetime.SESSION);
        assertTrue(ledger.allows(client, Capability.SHIZUKU));
        assertFalse(ledger.allows(client, Capability.DIAGNOSTICS));
        assertFalse(ledger.allows(client, Capability.SHELL));
    }
    @Test public void certificateReplacementAndUidReuseDoNotInheritAccess() {
        GrantLedger ledger = new GrantLedger();
        ledger.grant(identity("original"), Capability.SHIZUKU, GrantLedger.Lifetime.PERMANENT);
        assertFalse(ledger.allows(identity("replacement"), Capability.SHIZUKU));
    }
    @Test public void sharedUidMembershipChangeInvalidatesGrant() {
        GrantLedger ledger = new GrantLedger();
        ledger.grant(identity("a"), Capability.SHIZUKU, GrantLedger.Lifetime.SESSION);
        ClientIdentity shared = new ClientIdentity(10123, "test.client",
            Map.of("test.client", Set.of("a"), "other.client", Set.of("a")));
        assertFalse(ledger.allows(shared, Capability.SHIZUKU));
    }
    @Test public void revocationTakesEffectImmediatelyAndIndependently() {
        GrantLedger ledger = new GrantLedger();
        ClientIdentity client = identity("a");
        ledger.grant(client, Capability.SHIZUKU | Capability.DIAGNOSTICS, GrantLedger.Lifetime.PERMANENT);
        ledger.revoke(client, Capability.SHIZUKU);
        assertFalse(ledger.allows(client, Capability.SHIZUKU));
        assertTrue(ledger.allows(client, Capability.DIAGNOSTICS));
    }
    @Test public void oneTimeGrantIsConsumedOnlyByTheOperation() {
        GrantLedger ledger = new GrantLedger();
        ClientIdentity client = identity("a");
        ledger.grant(client, Capability.DIAGNOSTICS, GrantLedger.Lifetime.ONCE);
        assertTrue(ledger.allows(client, Capability.DIAGNOSTICS));
        assertTrue(ledger.consume(client, Capability.DIAGNOSTICS));
        assertFalse(ledger.consume(client, Capability.DIAGNOSTICS));
    }
    @Test public void restorationIncludesOnlyPermanentGrants() {
        GrantLedger ledger = new GrantLedger();
        ClientIdentity client = identity("a");
        ledger.grant(client, Capability.SHIZUKU, GrantLedger.Lifetime.PERMANENT);
        ledger.grant(client, Capability.DIAGNOSTICS, GrantLedger.Lifetime.SESSION);
        GrantLedger restored = new GrantLedger();
        ledger.permanentGrants().forEach(g -> restored.grant(g.identity(), g.mask(), GrantLedger.Lifetime.PERMANENT));
        assertTrue(restored.allows(client, Capability.SHIZUKU));
        assertFalse(restored.allows(client, Capability.DIAGNOSTICS));
    }
    @Test public void unknownCapabilityAndUnownedPackageFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> new ClientIdentity(10123, "spoof", Map.of("test.client", Set.of("a"))));
        assertThrows(IllegalArgumentException.class, () -> new GrantLedger().grant(identity("a"), Long.MIN_VALUE, GrantLedger.Lifetime.PERMANENT));
    }
}
