package io.github.stupidgiraffe.kizuku.compat;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

/** Discovery routing only; no permissions are trusted from broadcast extras. */
public final class BinderRequestReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!"rikka.shizuku.intent.action.REQUEST_BINDER".equals(intent.getAction())) return;
        if (context.getPackageManager().checkSignatures(context.getPackageName(), OpenKizukuActivity.MANAGER) != PackageManager.SIGNATURE_MATCH) return;
        // A broadcast sender may spoof a package extra. The manager validates UID ownership
        // through its provider on direct requests; this receiver merely opens the manager.
        Intent forward = new Intent("io.github.stupidgiraffe.kizuku.REQUEST_LEGACY").setPackage(OpenKizukuActivity.MANAGER);
        if (intent.hasExtra("data")) forward.putExtra("data",intent.getBundleExtra("data"));
        context.sendBroadcast(forward);
    }
}
