package io.github.stupidgiraffe.kizuku.testclient;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import io.github.stupidgiraffe.kizuku.api.IKizukuService;
import io.github.stupidgiraffe.kizuku.api.KizukuConnection;
import io.github.stupidgiraffe.kizuku.api.Protocol;
import io.github.stupidgiraffe.kizuku.core.Capability;
import rikka.shizuku.Shizuku;

/** Deliberately separate application UID; uses the published, unmodified Shizuku SDK. */
public final class ProbeActivity extends Activity {
    private TextView result;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout column = new LinearLayout(this); column.setOrientation(LinearLayout.VERTICAL); column.setPadding(32,64,32,32);
        result = new TextView(this); result.setText("Kizuku Probe\nIndependent native / stock Shizuku SDK client"); column.addView(result);
        add(column, "Connect both interfaces", () -> { nativeService().negotiate(getPackageName(),1,1); return "Native connected; stock Shizuku SDK: " + (Shizuku.pingBinder() ? "connected" : "waiting for discovery"); });
        add(column, "Request native diagnostics", () -> { nativeService().requestCapabilities(getPackageName(), Capability.DIAGNOSTICS); return "Review the request in Kizuku → Apps"; });
        add(column, "Request Shizuku Binder", () -> { Shizuku.requestPermission(1); return "Review the request in Kizuku → Apps"; });
        add(column, "Read native diagnostics", () -> nativeService().diagnostics(getPackageName()).toString());
        add(column, "Read Shizuku server UID", () -> "Legacy UID: " + Shizuku.getUid());
        setContentView(column);
    }
    private IKizukuService nativeService() throws Exception {
        return KizukuConnection.await(getApplicationContext(), 10000);
    }
    private interface Operation { String run() throws Exception; }
    private void add(LinearLayout column, String label, Operation operation) {
        Button button = new Button(this); button.setText(label); column.addView(button);
        button.setOnClickListener(v -> new Thread(() -> {
            String text; try { text = operation.run(); } catch (Exception e) { text = e.getClass().getSimpleName() + ": " + e.getMessage(); }
            String output = text; runOnUiThread(() -> result.setText(output));
        }, "probe").start());
    }
}
