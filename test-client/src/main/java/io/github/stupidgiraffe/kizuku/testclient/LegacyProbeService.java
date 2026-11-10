package io.github.stupidgiraffe.kizuku.testclient;

import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;

/** Test fixture loaded from the installed client APK via the stock UserService API. */
public final class LegacyProbeService extends Binder {
    public static final String DESCRIPTOR = "io.github.stupidgiraffe.kizuku.testclient.LegacyProbeService";
    private final String packageName;

    public LegacyProbeService(Context context) {
        packageName = context.getPackageName();
        attachInterface(null, DESCRIPTOR);
    }

    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == 16777115) {
            data.enforceInterface(DESCRIPTOR);
            System.exit(0);
        }
        if (code != IBinder.FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags);
        data.enforceInterface(DESCRIPTOR);
        reply.writeNoException();
        reply.writeInt(Process.myUid());
        reply.writeInt(Process.myPid());
        reply.writeString(packageName);
        return true;
    }
}
