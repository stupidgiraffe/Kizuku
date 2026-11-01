package io.github.stupidgiraffe.kizuku.core;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class IdentityRegressionTest {
    private ClientIdentity identity(long installed) { return new ClientIdentity(10123,"test.client",Map.of("test.client",Set.of("same-cert")),Map.of("test.client",installed)); }
    @Test public void reinstallWithSameUidAndCertificateRequiresNewAuthorization() {
        GrantLedger ledger=new GrantLedger();ledger.grant(identity(10),Capability.SHIZUKU,GrantLedger.Lifetime.PERMANENT);
        assertFalse(ledger.allows(identity(20),Capability.SHIZUKU));
        assertNotEquals(identity(10).fingerprint(),identity(20).fingerprint());
    }
    @Test public void invalidationAndRevocationDoNotResurrectPreviousIdentities() {
        GrantLedger ledger=new GrantLedger();ledger.grant(identity(10),Capability.SHIZUKU,GrantLedger.Lifetime.PERMANENT);
        ledger.revokePrincipal(10123,"test.client",Capability.SHIZUKU);
        assertFalse(ledger.allows(identity(10),Capability.SHIZUKU));
    }
    @Test public void reviewedFingerprintIncludesEverySharedUidMember() {
        ClientIdentity shared=new ClientIdentity(10123,"test.client",Map.of("test.client",Set.of("same-cert"),"other",Set.of("same-cert")),Map.of("test.client",10L,"other",11L));
        assertNotEquals(identity(10).fingerprint(),shared.fingerprint());
    }
    @Test public void snapshotOwnsItsData() {
        Map<String,Long> epochs=new HashMap<>();epochs.put("test.client",10L);
        ClientIdentity identity=new ClientIdentity(10123,"test.client",Map.of("test.client",Set.of("same-cert")),epochs);
        epochs.put("test.client",99L);assertEquals(Long.valueOf(10),identity.installations().get("test.client"));
    }
}
