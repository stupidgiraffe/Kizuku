package io.github.stupidgiraffe.kizuku.testclient;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Test-client implementation of the public Axeron 3.0 provider handoff. */
public final class AxeronProbeProvider extends ContentProvider {
    record Handoff(int uid, IBinder binder) {}

    private static final CountDownLatch RECEIVED = new CountDownLatch(1);
    private static volatile Handoff handoff;

    static Handoff await(long timeoutMillis) throws InterruptedException {
        return RECEIVED.await(timeoutMillis, TimeUnit.MILLISECONDS) ? handoff : null;
    }

    @Override public Bundle call(String method, String arg, Bundle data) {
        int uid = Binder.getCallingUid();
        if (uid != 0 && uid != 2000) throw new SecurityException("Host UID required");
        if (!"sendCapability".equals(method) || data == null) return null;
        IBinder binder = data.getBinder("axeron:capability");
        if (binder == null) return null;
        handoff = new Handoff(uid, binder);
        RECEIVED.countDown();
        return new Bundle();
    }

    @Override public boolean onCreate() { return true; }
    @Override public Cursor query(Uri uri, String[] columns, String selection, String[] args, String order) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
