package io.github.stupidgiraffe.kizuku.testclient;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.github.stupidgiraffe.kizuku.api.Protocol;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import moe.shizuku.server.IShizukuApplication;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Independent parcel client. Deliberately does not link against Kizuku's Axeron AIDL. */
@RunWith(AndroidJUnit4.class)
public final class AxeronWireSmokeTest {
    private static final String DESCRIPTOR = "frb.axeron.server.IAxeronService";
    private static final String PROCESS_DESCRIPTOR = "frb.axeron.server.IAxshProcess";
    private static final int FILE_SERVICE = 2;
    private static final int SERVER_INFO = 4;
    private static final int SHIZUKU_SERVICE = 10;
    private static final int ENVIRONMENT = 12;
    private static final int REQUEST_PERMISSION = 19;
    private static final int CHECK_PERMISSION = 20;
    private static final int ATTACH = 22;
    private static final int NEW_PROCESS = 112;
    private static final int OPEN_CHANNEL = 113;
    private static final int SOCKET_NAME = 116;
    private static final String STREAM_COMMAND =
        "IFS= read -r message; printf '%s:%s\\n' \"$(id -u)\" \"$message\"; printf 'stderr\\n' >&2; exit 23";

    @Test public void axeron30DiscoveryPermissionAndProcessWire() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String stage = InstrumentationRegistry.getArguments().getString("stage", "denied");
        ResolveInfo discovery = app.getPackageManager().resolveActivity(new Intent("AxRuntime.REQUEST_ACCESS"),
            PackageManager.MATCH_DEFAULT_ONLY);
        assertNotNull("The unmodified Axeron SDK discovery action resolves", discovery);
        assertEquals(Protocol.MANAGER, discovery.activityInfo.packageName);

        if (stage.equals("denied") || stage.equals("legacy") || stage.equals("revoked")) {
            assertNull("Unapproved app must not receive any Axeron capability", AxeronProbeProvider.await(2400));
            return;
        }

        AxeronProbeProvider.Handoff handoff = AxeronProbeProvider.await(10000);
        assertNotNull("Axeron 3.0 provider received its own capability automatically", handoff);
        IBinder binder = handoff.binder();
        assertEquals(DESCRIPTOR, binder.getInterfaceDescriptor());
        int hostUid = Integer.parseInt(InstrumentationRegistry.getArguments().getString("host_uid"));
        assertEquals(hostUid, handoff.uid());

        CountDownLatch attached = new CountDownLatch(1);
        CountDownLatch permissionAnswered = new CountDownLatch(1);
        int[] callbackIdentity = {-1, -1};
        int[] permissionReply = {-1, -1, -1};
        IShizukuApplication application = new IShizukuApplication.Stub() {
            @Override public void bindApplication(Bundle data) {
                callbackIdentity[0] = Binder.getCallingUid();
                callbackIdentity[1] = data.getInt("axeron:bind-application-client-uid", -1);
                attached.countDown();
            }
            @Override public void dispatchRequestPermissionResult(int requestCode, Bundle data) {
                permissionReply[0] = Binder.getCallingUid();
                permissionReply[1] = requestCode;
                permissionReply[2] = data.getBoolean("shizuku:request-permission-reply-allowed") ? 1 : 0;
                permissionAnswered.countDown();
            }
            @Override public void showPermissionConfirmation(int uid, int pid, String name, int code) {}
        };
        assertThrows(SecurityException.class, () -> attach(binder, Protocol.MANAGER, application));
        attach(binder, app.getPackageName(), application);
        assertTrue("AxBroker handshake callback received", attached.await(3, TimeUnit.SECONDS));
        assertArrayEquals(new int[] {hostUid, Process.myUid()}, callbackIdentity);
        assertTrue(call(binder, CHECK_PERMISSION, null, p -> p.readInt() != 0));
        call(binder, REQUEST_PERMISSION, p -> p.writeInt(0x4158), null);
        assertTrue("AxBroker permission callback received", permissionAnswered.await(3, TimeUnit.SECONDS));
        assertArrayEquals(new int[] {hostUid, 0x4158, 1}, permissionReply);

        assertEquals(hostUid, (int) call(binder, SERVER_INFO, null, p -> {
            assertEquals(1, p.readInt()); // Parcelable presence.
            assertEquals("3.0.0", p.readString());
            assertEquals(30000L, p.readLong());
            return p.readInt();
        }));
        assertNull(call(binder, SOCKET_NAME, null, Parcel::readString));
        // The unmodified Axsh SDK reads TYPE_ENV before every default-environment launch.
        assertEquals(0, (int) call(binder, ENVIRONMENT, p -> p.writeInt(0), p -> {
            assertEquals(1, p.readInt());
            return p.readInt();
        }));
        assertThrows(UnsupportedOperationException.class, () -> call(binder, SHIZUKU_SERVICE, null, null));
        assertThrows(UnsupportedOperationException.class, () -> call(binder, FILE_SERVICE, null, null));
        assertThrows(UnsupportedOperationException.class, () -> call(binder, OPEN_CHANNEL, null, null));
        assertStreams(binder, hostUid);
        assertRefusedAndClosed(binder, IllegalArgumentException.class, true);

        if (stage.equals("held")) {
            assertRevokedProcessDies(binder);
            assertRefusedAndClosed(binder, SecurityException.class, false);
        }
    }

    private static void attach(IBinder binder, String name, IShizukuApplication application) throws Exception {
        call(binder, ATTACH, p -> {
            p.writeStrongBinder(application.asBinder());
            Bundle args = new Bundle();
            args.putString("shizuku:attach-package-name", name);
            args.putInt("shizuku:attach-api-version", -1);
            p.writeTypedObject(args, 0);
        }, null);
    }

    private static void assertStreams(IBinder binder, int hostUid) throws Exception {
        ParcelFileDescriptor[] stdin = ParcelFileDescriptor.createPipe();
        ParcelFileDescriptor[] stdout = ParcelFileDescriptor.createPipe();
        ParcelFileDescriptor[] stderr = ParcelFileDescriptor.createPipe();
        ParcelFileDescriptor[] status = ParcelFileDescriptor.createPipe();
        try {
            try (var input = new ParcelFileDescriptor.AutoCloseOutputStream(stdin[1])) {
                input.write("payload\n".getBytes(StandardCharsets.UTF_8));
            }
            IBinder child = launch(binder, STREAM_COMMAND, stdin[0], stdout[1], stderr[1], status[1], false);
            assertNotNull(child);
            assertEquals(PROCESS_DESCRIPTOR, child.getInterfaceDescriptor());
            stdin[0].close();
            stdout[1].close();
            stderr[1].close();
            status[1].close();

            byte[] exit = readPipe(status[0]);
            assertEquals(4, exit.length);
            assertEquals(23, ByteBuffer.wrap(exit).order(ByteOrder.LITTLE_ENDIAN).getInt());
            assertEquals(hostUid + ":payload\n", new String(readPipe(stdout[0]), StandardCharsets.UTF_8));
            assertEquals("stderr\n", new String(readPipe(stderr[0]), StandardCharsets.UTF_8));
        } finally {
            closePipes(stdin, stdout, stderr, status);
        }
    }

    private static void assertRevokedProcessDies(IBinder binder) throws Exception {
        ParcelFileDescriptor[] stdin = ParcelFileDescriptor.createPipe();
        ParcelFileDescriptor[] stdout = ParcelFileDescriptor.createPipe();
        ParcelFileDescriptor[] stderr = ParcelFileDescriptor.createPipe();
        ParcelFileDescriptor[] status = ParcelFileDescriptor.createPipe();
        try {
            assertNotNull(launch(binder, "printf x; exec /system/bin/sleep 60",
                stdin[0], stdout[1], stderr[1], status[1], false));
            stdin[0].close();
            stdin[1].close();
            stdout[1].close();
            stderr[1].close();
            status[1].close();
            try (InputStream output = new ParcelFileDescriptor.AutoCloseInputStream(stdout[0])) {
                awaitReadable(poll(stdout[0]));
                assertEquals('x', output.read());
                Log.i("KizukuAxeronSmoke", "Waiting for manager revoke");
                Bundle progress = new Bundle();
                progress.putString("stream", "\nWaiting for manager revoke\n");
                InstrumentationRegistry.getInstrumentation().sendStatus(2, progress);
                long deadline = SystemClock.elapsedRealtime() + 20000;
                while (call(binder, CHECK_PERMISSION, null, p -> p.readInt() != 0)) {
                    assertTrue("Manager revoked the held capability", SystemClock.elapsedRealtime() < deadline);
                    SystemClock.sleep(100);
                }
                byte[] exit = readPipe(status[0]);
                assertEquals(4, exit.length);
                assertNotEquals(0, ByteBuffer.wrap(exit).order(ByteOrder.LITTLE_ENDIAN).getInt());
            }
        } finally {
            closePipes(stdin, stdout, stderr, status);
        }
    }

    private static void assertRefusedAndClosed(IBinder binder, Class<? extends Throwable> exception,
                                               boolean trailingData) throws Exception {
        ParcelFileDescriptor[] stdin = ParcelFileDescriptor.createPipe();
        ParcelFileDescriptor[] stdout = ParcelFileDescriptor.createPipe();
        ParcelFileDescriptor[] stderr = ParcelFileDescriptor.createPipe();
        ParcelFileDescriptor[] status = ParcelFileDescriptor.createPipe();
        try {
            assertThrows(exception, () ->
                launch(binder, STREAM_COMMAND, stdin[0], stdout[1], stderr[1], status[1], trailingData));
            stdout[1].close();
            stderr[1].close();
            status[1].close();
            assertEquals(0, readPipe(stdout[0]).length);
            assertEquals(0, readPipe(stderr[0]).length);
            assertEquals(0, readPipe(status[0]).length);
        } finally {
            closePipes(stdin, stdout, stderr, status);
        }
    }

    private static IBinder launch(IBinder binder, String command, ParcelFileDescriptor stdin, ParcelFileDescriptor stdout,
                                  ParcelFileDescriptor stderr, ParcelFileDescriptor status, boolean trailingData)
            throws Exception {
        return call(binder, NEW_PROCESS, p -> {
            p.writeStringArray(new String[] {"/system/bin/sh", "-c", command});
            p.writeStringArray(null);
            p.writeString(null);
            p.writeTypedObject(stdin, 0);
            p.writeTypedObject(stdout, 0);
            p.writeTypedObject(stderr, 0);
            p.writeInt(0); // Non-PTY.
            p.writeLong(0);
            p.writeTypedObject(status, 0);
            if (trailingData) p.writeInt(1234);
        }, Parcel::readStrongBinder);
    }

    private static void closePipes(ParcelFileDescriptor[]... pipes) throws Exception {
        for (ParcelFileDescriptor[] pair : pipes) {
            for (ParcelFileDescriptor fd : pair) fd.close();
        }
    }

    private static byte[] readPipe(ParcelFileDescriptor fd) throws Exception {
        StructPollfd[] polls = poll(fd);
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(fd);
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[128];
            while (true) {
                awaitReadable(polls);
                int length = input.read(buffer);
                if (length == -1) return bytes.toByteArray();
                bytes.write(buffer, 0, length);
                assertTrue("Only a small test payload was expected", bytes.size() < 512);
            }
        }
    }

    private static StructPollfd[] poll(ParcelFileDescriptor fd) {
        StructPollfd poll = new StructPollfd();
        poll.fd = fd.getFileDescriptor();
        poll.events = (short) (OsConstants.POLLIN | OsConstants.POLLHUP);
        return new StructPollfd[] {poll};
    }

    private static void awaitReadable(StructPollfd[] polls) throws Exception {
        assertEquals("Axeron stream was ready before timeout", 1, Os.poll(polls, 5000));
    }

    private interface ParcelWriter { void write(Parcel data); }
    private interface ParcelReader<T> { T read(Parcel data); }

    private static <T> T call(IBinder binder, int id, ParcelWriter writer, ParcelReader<T> reader) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (writer != null) writer.write(data);
            assertTrue("Axeron wire transaction " + id + " is implemented",
                binder.transact(IBinder.FIRST_CALL_TRANSACTION + id, data, reply, 0));
            reply.readException();
            return reader == null ? null : reader.read(reply);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }
}
