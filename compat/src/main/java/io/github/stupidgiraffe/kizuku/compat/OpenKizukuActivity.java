package io.github.stupidgiraffe.kizuku.compat;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.content.pm.PackageManager;

public final class OpenKizukuActivity extends Activity {
    static final String MANAGER = "io.github.stupidgiraffe.kizuku";
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (getPackageManager().checkSignatures(getPackageName(), MANAGER) == PackageManager.SIGNATURE_MATCH) {
            Intent manager = getPackageManager().getLaunchIntentForPackage(MANAGER);
            if (manager != null) startActivity(manager);
        }
        finish();
    }
}
