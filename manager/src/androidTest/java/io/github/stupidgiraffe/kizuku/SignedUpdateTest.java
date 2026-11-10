package io.github.stupidgiraffe.kizuku;

import android.content.Context;
import android.os.Bundle;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.github.stupidgiraffe.kizuku.api.AndroidIdentity;
import io.github.stupidgiraffe.kizuku.core.Capability;
import io.github.stupidgiraffe.kizuku.core.ClientIdentity;
import io.github.stupidgiraffe.kizuku.core.GrantLedger;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

@RunWith(AndroidJUnit4.class)
public class SignedUpdateTest {
    private static final String CLIENT = "io.github.stupidgiraffe.kizuku.testclient";

    @Test public void rememberedGrantSurvivesPackageReplacement() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String stage = InstrumentationRegistry.getArguments().getString("stage", "");
        int uid = app.getPackageManager().getApplicationInfo(CLIENT, 0).uid;
        // These calls are shared by Preview 2 and the candidate. Only Preview 2 writes the grant.
        if (stage.equals("before")) {
            assertEquals("Fixture starts without a grant", 0L, grantedScope(app, uid));
            ClientIdentity identity = AndroidIdentity.resolve(app.getPackageManager(), uid, CLIENT);
            AuthorizationStore.INSTANCE.decide(app, identity, Capability.SHIZUKU,
                    GrantLedger.Lifetime.PERMANENT.ordinal());
        } else if (!stage.equals("after")) {
            fail("Unknown signed update stage: " + stage);
        }
        assertEquals("Remembered grant for the installed probe", Capability.SHIZUKU, grantedScope(app, uid));
    }

    private static long grantedScope(Context app, int uid) {
        for (Bundle row : InstalledClients.INSTANCE.list(app, Collections.emptyList(), false)) {
            if (CLIENT.equals(row.getString("package"))) {
                assertEquals("Remembered grant belongs to the current probe UID", uid, row.getInt("uid"));
                return row.getLong("granted");
            }
        }
        throw new AssertionError("Installed probe is missing from the manager's app list");
    }
}
