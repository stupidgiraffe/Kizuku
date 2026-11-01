package io.github.stupidgiraffe.kizuku.api;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import java.util.concurrent.TimeoutException;

/** Native discovery can briefly be unavailable while the manager reconnects to its host. */
public final class KizukuConnection {
    private KizukuConnection() {}

    /** Call from a worker thread. This negotiates a connection, never a capability grant. */
    public static IKizukuService await(Context context, long timeoutMillis)
            throws InterruptedException, TimeoutException, RemoteException {
        if (Looper.myLooper() == Looper.getMainLooper())
            throw new IllegalStateException("Do not wait for Kizuku on the main thread");
        if (timeoutMillis < 1 || timeoutMillis > 15000)
            throw new IllegalArgumentException("Connection timeout must be 1–15000 ms");
        long deadline = SystemClock.elapsedRealtime() + timeoutMillis;
        do {
            try {
                Bundle reply = context.getContentResolver().call(
                    Uri.parse("content://" + Protocol.AUTHORITY), "connect", null, null);
                IBinder binder = reply == null ? null : reply.getBinder(Protocol.BINDER);
                if (binder != null && binder.pingBinder()) {
                    IKizukuService service = IKizukuService.Stub.asInterface(binder);
                    service.negotiate(context.getPackageName(), Protocol.VERSION, Protocol.VERSION);
                    return service;
                }
            } catch (DeadObjectException restarting) { /* Retry discovery, not the failed operation. */ }
            long remaining = deadline - SystemClock.elapsedRealtime();
            if (remaining <= 0) break;
            Thread.sleep(Math.min(100, remaining));
        } while (!Thread.currentThread().isInterrupted());
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        throw new TimeoutException("Kizuku did not connect. Start the core and try again.");
    }
}
