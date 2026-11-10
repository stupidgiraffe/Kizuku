package moe.shizuku.manager.shell;

import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.system.Os;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import moe.shizuku.server.IShizukuApplication;
import moe.shizuku.server.IShizukuService;

/** Entry point loaded from the manager APK by the unmodified stock rish loader. */
public final class Shell {
    private static final String DESCRIPTOR = "moe.shizuku.server.IShizukuService";
    private static IShizukuApplication application;

    public static void main(String[] args, String packageName, IBinder binder, Handler handler) {
        handler.removeCallbacksAndMessages(null); // Stock loader discovery timeout has completed.
        new Thread(() -> {
            int exit = 1;
            try {
                System.loadLibrary("rish");
                try {
                    authorize(IShizukuService.Stub.asInterface(binder), packageName);
                    exit = run(binder, args);
                } finally { restoreTerminal(); }
            } catch (Exception | LinkageError failure) {
                System.err.println("rish: " + failure.getMessage());
                System.err.flush();
            }
            System.exit(exit);
        }, "rish-terminal").start();
    }

    private static void authorize(IShizukuService service, String packageName) throws Exception {
        CountDownLatch attached = new CountDownLatch(1);
        CountDownLatch approved = new CountDownLatch(1);
        application = new IShizukuApplication.Stub() {
            @Override public void bindApplication(Bundle data) { attached.countDown(); }
            @Override public void dispatchRequestPermissionResult(int requestCode, Bundle data) { approved.countDown(); }
            @Override public void showPermissionConfirmation(int uid, int pid, String pkg, int code) {}
        };
        Bundle args = new Bundle();
        args.putString("shizuku:attach-package-name", packageName);
        args.putInt("shizuku:attach-api-version", 13);
        service.attachApplication(application, args);
        if (!attached.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Legacy host did not attach");
        if (!service.checkSelfPermission()) {
            System.err.println("Approve the Shizuku request in Kizuku to continue.");
            service.requestPermission(0);
            if (!approved.await(60, TimeUnit.SECONDS) || !service.checkSelfPermission())
                throw new SecurityException("Shizuku permission denied");
        }
    }

    private static int run(IBinder binder, String[] args) throws Exception {
        byte tty = terminalMode();
        FileDescriptor[] input = Os.pipe();
        FileDescriptor[] output = Os.pipe();
        FileDescriptor[] error = (tty & 4) == 0 ? Os.pipe() : null;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeByte(tty);
            data.writeFileDescriptor(input[0]);
            data.writeFileDescriptor(output[1]);
            if (error != null) data.writeFileDescriptor(error[1]);
            data.writeStringArray(args);
            data.writeStringArray(System.getenv().entrySet().stream()
                .map(item -> item.getKey() + "=" + item.getValue()).toArray(String[]::new));
            data.writeString(new File("").getAbsolutePath());
            if (!binder.transact(30000, data, reply, 0)) throw new IOException("The server does not implement rish");
            reply.readException();
        } finally {
            data.recycle();
            reply.recycle();
            Os.close(input[0]);
            Os.close(output[1]);
            if (error != null) Os.close(error[1]);
        }
        if (tty != 0) {
            setRawTerminal(tty);
            Thread window = new Thread(() -> trackWindow(binder, tty), "rish-window");
            window.setDaemon(true);
            window.start();
        }
        Thread in = new Thread(() -> copy(System.in, new FileOutputStream(input[1]), true), "rish-stdin");
        in.setDaemon(true);
        in.start();
        Thread err = null;
        if (error != null) {
            err = new Thread(() -> copy(new FileInputStream(error[0]), System.err, false), "rish-stderr");
            err.start();
        }
        copy(new FileInputStream(output[0]), System.out, false);
        if (err != null) err.join();
        for (int attempt = 0; attempt < 20; attempt++) {
            int status = query(binder, 30002, 0);
            if (status != Integer.MAX_VALUE) return status;
            Thread.sleep(25);
        }
        return 1;
    }

    private static void copy(InputStream input, OutputStream output, boolean closeOutput) {
        try {
            byte[] bytes = new byte[8192];
            int count;
            while ((count = input.read(bytes)) != -1) {
                output.write(bytes, 0, count);
                output.flush();
            }
        } catch (IOException closed) {
            // A closed terminal or remote pipe ends the transfer.
        } finally {
            try { if (closeOutput) output.close(); else input.close(); } catch (IOException ignored) {}
        }
    }

    private static void trackWindow(IBinder binder, byte tty) {
        try {
            long previous = Long.MIN_VALUE;
            while (true) {
                long size = windowSize(tty);
                if (size != previous) query(binder, 30001, size);
                previous = size;
                Thread.sleep(100);
            }
        } catch (Exception closed) {
            // Foreground stream transfer owns the process lifetime.
        }
    }

    private static int query(IBinder binder, int code, long size) throws Exception {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (code == 30001) data.writeLong(size);
            if (!binder.transact(code, data, reply, 0)) throw new IOException("Shell session unavailable");
            reply.readException();
            return code == 30002 ? reply.readInt() : 0;
        } finally { data.recycle(); reply.recycle(); }
    }

    private static native byte terminalMode();
    private static native void setRawTerminal(byte tty);
    private static native void restoreTerminal();
    private static native long windowSize(byte tty);
}
