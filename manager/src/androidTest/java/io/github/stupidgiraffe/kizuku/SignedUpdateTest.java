package io.github.stupidgiraffe.kizuku;

import android.content.Context;
import android.os.Bundle;
import android.content.SharedPreferences;
import android.util.Base64;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.github.stupidgiraffe.kizuku.api.AndroidIdentity;
import io.github.stupidgiraffe.kizuku.adb.AdbKey;
import io.github.stupidgiraffe.kizuku.adb.PreferenceAdbKeyStore;
import io.github.stupidgiraffe.kizuku.core.Capability;
import io.github.stupidgiraffe.kizuku.core.ClientIdentity;
import io.github.stupidgiraffe.kizuku.core.GrantLedger;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(AndroidJUnit4.class)
public class SignedUpdateTest {
    private static final String CLIENT = "io.github.stupidgiraffe.kizuku.testclient";
    private static final long SCOPES = Capability.SHIZUKU | Capability.AXERON;

    @Test public void grantsAndAdbIdentitySurvivePackageReplacement() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String stage = InstrumentationRegistry.getArguments().getString("stage", "");
        int uid = app.getPackageManager().getApplicationInfo(CLIENT, 0).uid;
        SharedPreferences adb = app.getSharedPreferences("adb", Context.MODE_PRIVATE);
        SharedPreferences evidence = InstrumentationRegistry.getInstrumentation().getContext()
                .getSharedPreferences("signed_update", Context.MODE_PRIVATE);
        String adbIdentity = Base64.encodeToString(
                new AdbKey(new PreferenceAdbKeyStore(adb), "Kizuku").getAdbPublicKey(), Base64.NO_WRAP);
        // The previous signed release creates these through the same production stores.
        if (stage.equals("before")) {
            assertEquals("Fixture starts without a grant", 0L, grantedScope(app, uid));
            ClientIdentity identity = AndroidIdentity.resolve(app.getPackageManager(), uid, CLIENT);
            AuthorizationStore.INSTANCE.decide(app, identity, SCOPES,
                    GrantLedger.Lifetime.PERMANENT.ordinal());
            assertTrue("Saved the generated public ADB identity", evidence.edit().putString("adb", adbIdentity).commit());
            assertTrue("Saved the pairing preference", adb.edit().putBoolean("paired", true).commit());
        } else if (stage.equals("after")) {
            assertEquals("Keystore-backed ADB key survives the update", evidence.getString("adb", null), adbIdentity);
            assertTrue("Pairing preference survives the update", adb.getBoolean("paired", false));
        } else {
            fail("Unknown signed update stage: " + stage);
        }
        assertEquals("Both remembered grants for the installed probe", SCOPES, grantedScope(app, uid));
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
