package io.github.stupidgiraffe.kizuku;

import android.os.Bundle;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.github.stupidgiraffe.kizuku.api.AndroidIdentity;
import io.github.stupidgiraffe.kizuku.api.IKizukuService;
import io.github.stupidgiraffe.kizuku.core.Capability;
import io.github.stupidgiraffe.kizuku.core.GrantLedger;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

@RunWith(AndroidJUnit4.class)
public class ThirdPartyClientTest {
    private static final String HAIL = "com.aistra.hail";

    @Test public void authorizeActualHailRequest() throws Exception {
        IKizukuService service = CoreConnection.INSTANCE.getService();
        long deadline = SystemClock.elapsedRealtime() + 12000;
        while (service == null && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(100);
            service = CoreConnection.INSTANCE.getService();
        }
        assertNotNull("Host is connected", service);
        assertEquals(Integer.parseInt(InstrumentationRegistry.getArguments().getString("host_uid", "2000")),
                service.status().getInt("uid", -1));

        long requestDeadline = SystemClock.elapsedRealtime() + 10000;
        Bundle request = null;
        while (request == null && SystemClock.elapsedRealtime() < requestDeadline) {
            for (Bundle row : service.applications()) {
                if (row.getBoolean("verified") && HAIL.equals(row.getString("package"))
                        && row.getLong("requested") == Capability.SHIZUKU) {
                    request = row;
                    break;
                }
            }
            if (request == null) SystemClock.sleep(100);
        }
        assertNotNull("The official Hail client discovered Kizuku and requested Shizuku access", request);
        assertEquals("Hail starts without grants", 0L, request.getLong("assigned"));
        service.grant(HAIL, request.getInt("uid"), Capability.SHIZUKU, GrantLedger.Lifetime.SESSION.ordinal(),
                AndroidIdentity.decode(new JSONObject(request.getString("identity"))).fingerprint());

        long assigned = 0;
        for (Bundle row : service.applications()) {
            if (HAIL.equals(row.getString("package"))) assigned = row.getLong("assigned");
        }
        assertEquals("A Hail Shizuku approval does not authorize Axeron", Capability.SHIZUKU, assigned);
    }
}
