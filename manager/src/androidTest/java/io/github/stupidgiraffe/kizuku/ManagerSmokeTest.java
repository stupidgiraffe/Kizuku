package io.github.stupidgiraffe.kizuku;

import android.content.Context;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import io.github.stupidgiraffe.kizuku.api.IKizukuService;
import io.github.stupidgiraffe.kizuku.core.Capability;
import io.github.stupidgiraffe.kizuku.api.AndroidIdentity;
import io.github.stupidgiraffe.kizuku.core.ClientIdentity;

@RunWith(AndroidJUnit4.class)
public class ManagerSmokeTest {
    @Test public void controlRealPrivilegedHost() throws Exception {
        String action = InstrumentationRegistry.getArguments().getString("action", "connected");
        if (action.equals("dead")) {
            long end = SystemClock.elapsedRealtime()+10000;
            while (CoreConnection.INSTANCE.getService()!=null && SystemClock.elapsedRealtime()<end) SystemClock.sleep(100);
            assertNull("Death clears the manager Binder", CoreConnection.INSTANCE.getService()); return;
        }
        long end = SystemClock.elapsedRealtime()+15000;
        while (CoreConnection.INSTANCE.getService()==null && SystemClock.elapsedRealtime()<end) SystemClock.sleep(100);
        IKizukuService service = CoreConnection.INSTANCE.getService(); assertNotNull("Real server handshake", service);
        assertEquals(Integer.parseInt(InstrumentationRegistry.getArguments().getString("host_uid","2000")), service.status().getInt("uid",-1));
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String target="io.github.stupidgiraffe.kizuku.testclient"; int uid=app.getPackageManager().getApplicationInfo(target,0).uid;
        ClientIdentity identity=AndroidIdentity.resolve(app.getPackageManager(),uid,target);
        switch (action) {
            case "legacy": AuthorizationStore.INSTANCE.decide(app,identity,Capability.SHIZUKU,2); service.grant(target,uid,Capability.SHIZUKU,2,identity.fingerprint()); break;
            case "native": AuthorizationStore.INSTANCE.decide(app,identity,Capability.SHIZUKU | Capability.DIAGNOSTICS,null); service.grant(target,uid,Capability.DIAGNOSTICS,1,identity.fingerprint()); service.revoke(target,uid,Capability.SHIZUKU); break;
            case "revoke": AuthorizationStore.INSTANCE.decide(app,identity,Capability.SHIZUKU | Capability.DIAGNOSTICS,null); service.revoke(target,uid,Capability.DIAGNOSTICS | Capability.SHIZUKU); break;
            case "persist": AuthorizationStore.INSTANCE.decide(app,identity,Capability.DIAGNOSTICS,2); service.grant(target,uid,Capability.DIAGNOSTICS,2,identity.fingerprint()); break;
            case "axeron_allow":
                AuthorizationStore.INSTANCE.decide(app, identity, Capability.AXERON, 2);
                service.grant(target, uid, Capability.AXERON, 2, identity.fingerprint());
                break;
            case "axeron_revoke":
                AuthorizationStore.INSTANCE.decide(app, identity, Capability.AXERON, null);
                service.revoke(target, uid, Capability.AXERON);
                break;
            case "stop":
                assertNotNull(CoreConnection.INSTANCE.getService());
                service.shutdown();
                long deadline=SystemClock.elapsedRealtime()+10000;
                while(CoreConnection.INSTANCE.getService()!=null && SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(100);
                assertNull("Connected host death was observed in this same manager process", CoreConnection.INSTANCE.getService()); break;
            case "stale": assertThrows(SecurityException.class, () -> service.grant(target,uid,Capability.DIAGNOSTICS,1,"outdated-fingerprint")); break;
            default: assertEquals(1,service.status().getInt("protocol"));
        }
    }
}
