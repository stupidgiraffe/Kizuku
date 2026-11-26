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
import org.json.JSONArray;
import java.security.MessageDigest;
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
        byte[] publicKey = new AdbKey(new PreferenceAdbKeyStore(adb), "Kizuku").getAdbPublicKey();
        String identityDigest = Base64.encodeToString(
                MessageDigest.getInstance("SHA-256").digest(publicKey), Base64.NO_WRAP);
        // The previous signed release creates these through the same production stores.
        if (stage.equals("before")) {
            assertEquals("Fixture starts without a grant", 0L, grantedScope(app, uid));
            ClientIdentity identity = AndroidIdentity.resolve(app.getPackageManager(), uid, CLIENT);
            String legacyGrants = new JSONArray()
                    .put(AndroidIdentity.encode(identity).put("mask", SCOPES)).toString();
            assertTrue(app.getSharedPreferences("authorization", Context.MODE_PRIVATE)
                    .edit().putString("grants", legacyGrants).commit());
            AuthorizationStore.INSTANCE.replaceRemembered(app, identity, SCOPES, Capability.SHIZUKU, 0);
            assertTrue("Saved the pairing preference", adb.edit().putBoolean("paired", true).commit());
            long deadline = android.os.SystemClock.elapsedRealtime() + 15_000;
            while (CoreConnection.INSTANCE.getService() == null && android.os.SystemClock.elapsedRealtime() < deadline)
                android.os.SystemClock.sleep(100);
            assertTrue("The previous signed version has a real running host", CoreConnection.INSTANCE.getService() != null);
            assertEquals("The previous host runs as ADB shell", 2000, CoreConnection.INSTANCE.getService().status().getInt("uid"));
        } else if (stage.equals("after")) {
            assertTrue("Pairing preference survives the update", adb.getBoolean("paired", false));
            JSONArray root = new JSONArray(AuthorizationStore.INSTANCE.load(app, 0));
            assertEquals("The explicitly approved root identity survives", 1, root.length());
            assertEquals("Shell Axeron approval does not become a root approval", Capability.SHIZUKU,
                    root.getJSONObject(0).getLong("mask"));
            assertEquals(AndroidIdentity.resolve(app.getPackageManager(), uid, CLIENT), AndroidIdentity.decode(root.getJSONObject(0)));
        } else {
            fail("Unknown signed update stage: " + stage);
        }
        assertEquals("Both remembered grants for the installed probe", SCOPES, grantedScope(app, uid));
        // The runner compares both digests outside the app being upgraded.
        Bundle status = new Bundle();
        status.putString("kizuku_adb_identity", identityDigest);
        InstrumentationRegistry.getInstrumentation().sendStatus(0, status);
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
