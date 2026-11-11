package io.github.stupidgiraffe.kizuku.testclient;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import io.github.stupidgiraffe.kizuku.api.IKizukuService;
import io.github.stupidgiraffe.kizuku.api.KizukuConnection;
import io.github.stupidgiraffe.kizuku.api.Protocol;
import rikka.shizuku.Shizuku;
import rikka.shizuku.SystemServiceHelper;
import rikka.shizuku.ShizukuBinderWrapper;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import moe.shizuku.server.IShizukuService;
import moe.shizuku.server.IShizukuServiceConnection;

@RunWith(AndroidJUnit4.class)
public class ProtocolSmokeTest {
    @Test public void separateUidUsesBothProtocolsAndEnforcesIndependentGrants() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        // Manager instrumentation deliberately restarts its process between stages.
        // Exercise the SDK's bounded discovery rather than assuming immediate publication.
        IKizukuService service = KizukuConnection.await(app, 15000);
        assertEquals(1, service.negotiate(app.getPackageName(),1,1).getInt("protocol"));
        assertThrows(SecurityException.class, () -> service.negotiate(Protocol.MANAGER,1,1));
        assertThrows(IllegalArgumentException.class, () -> service.negotiate(app.getPackageName(),2,2));
        assertThrows(SecurityException.class, service::status);
        assertThrows(SecurityException.class, service::applications);
        CountDownLatch legacy = new CountDownLatch(1);
        Shizuku.addBinderReceivedListenerSticky(legacy::countDown);
        // No Kizuku-specific legacy request: the unchanged SDK must receive automatic delivery.
        assertTrue("Unmodified stock SDK received and attached its Binder", legacy.await(10, TimeUnit.SECONDS));
        String stage = InstrumentationRegistry.getArguments().getString("stage", "denied");
        boolean legacyAllowed = stage.equals("legacy"); boolean nativeAllowed = stage.equals("native") || stage.equals("persisted");
        assertEquals(legacyAllowed ? 0 : -1, Shizuku.checkSelfPermission());
        if (legacyAllowed) {
            assertTrue(Shizuku.getUid() == 0 || Shizuku.getUid() == 2000);
            moe.shizuku.server.IShizukuService legacyService =
                moe.shizuku.server.IShizukuService.Stub.asInterface(Shizuku.getBinder());
            var command = legacyService.newProcess(new String[]{"/system/bin/sh", "-c",
                "read value; printf '%s:' \"$value\"; id -u; printf 'error' >&2; exit 7"}, null, "/");
            try (var input = new ParcelFileDescriptor.AutoCloseOutputStream(command.getOutputStream());
                 var stdout = new ParcelFileDescriptor.AutoCloseInputStream(command.getInputStream());
                 var stderr = new ParcelFileDescriptor.AutoCloseInputStream(command.getErrorStream())) {
                input.write("stock-process\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                input.flush();
                assertTrue(command.waitForTimeout(5, "SECONDS"));
                assertEquals(7, command.exitValue());
                assertEquals("stock-process:" + Shizuku.getUid(), readOutput(stdout));
                assertEquals("error", readOutput(stderr));
            }

            // A real transaction through the stock SDK wrapper to the system package service.
            org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("Landroid/content/pm/IPackageManager", "Landroid/os/ServiceManager;");
            IBinder packageManager=SystemServiceHelper.getSystemService("package");
            assertNotNull(packageManager);
            Parcel parcel=Parcel.obtain(),reply=Parcel.obtain();
            try {
                parcel.writeInterfaceToken("android.content.pm.IPackageManager");
                Integer transaction=SystemServiceHelper.getTransactionCode("android.content.pm.IPackageManager$Stub","getPackagesForUid");
                assertNotNull(transaction);
                parcel.writeInt(android.os.Process.myUid());
                assertTrue(new ShizukuBinderWrapper(packageManager).transact(transaction,parcel,reply,0));
                reply.readException();
                assertTrue(java.util.Arrays.asList(reply.createStringArray()).contains(app.getPackageName()));
            } finally { parcel.recycle();reply.recycle(); }
            Parcel invalid=Parcel.obtain(),invalidReply=Parcel.obtain();
            try { assertThrows(SecurityException.class, () -> { new ShizukuBinderWrapper(new Binder()).transact(1,invalid,invalidReply,0); invalidReply.readException(); }); }
            finally { invalid.recycle();invalidReply.recycle(); }
            assertLegacyUserService(app);
            assertRish(false);
            assertRish(true);
            assertStockRishLoader(app);
        }
        if (nativeAllowed) { int uid=service.diagnostics(app.getPackageName()).getInt("server_uid",-1); assertTrue(uid==0 || uid==2000); }
        else assertThrows(SecurityException.class, () -> service.diagnostics(app.getPackageName()));
        if (!legacyAllowed) {
            var legacyService = moe.shizuku.server.IShizukuService.Stub.asInterface(Shizuku.getBinder());
            assertThrows(SecurityException.class, legacyService::getUid);
            assertThrows(SecurityException.class, () -> legacyService.newProcess(new String[]{"/system/bin/id"}, null, null));
        }
        Bundle inventedToken = new Bundle();
        inventedToken.putString("shizuku:user-service-arg-token", "invented");
        assertThrows(SecurityException.class,
            () -> IShizukuService.Stub.asInterface(Shizuku.getBinder()).attachUserService(new Binder(), inventedToken));
        if (stage.equals("denied")) service.requestCapabilities(app.getPackageName(), io.github.stupidgiraffe.kizuku.core.Capability.DIAGNOSTICS);
        assertThrows(IllegalArgumentException.class, () -> service.requestCapabilities(app.getPackageName(), io.github.stupidgiraffe.kizuku.core.Capability.SHELL));
    }
    private static void assertLegacyUserService(Context app) throws Exception {
        IShizukuService facade = IShizukuService.Stub.asInterface(Shizuku.getBinder());
        Bundle wrongPackage = new Bundle();
        wrongPackage.putParcelable("shizuku:user-service-arg-component",
            new ComponentName(Protocol.MANAGER, LegacyProbeService.class.getName()));
        IShizukuServiceConnection callback = new IShizukuServiceConnection.Stub() {
            @Override public void connected(IBinder binder) {}
            @Override public void died() {}
        };
        assertThrows(SecurityException.class, () -> facade.addUserService(callback, wrongPackage));

        Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(new ComponentName(app, LegacyProbeService.class))
            .processNameSuffix("legacy_probe").tag("smoke-probe").version(17).daemon(false);
        CountDownLatch connected = new CountDownLatch(1);
        CountDownLatch disconnected = new CountDownLatch(1);
        AtomicReference<IBinder> service = new AtomicReference<>();
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName component, IBinder binder) {
                service.set(binder);
                connected.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName component) { disconnected.countDown(); }
        };
        assertEquals(-1, Shizuku.peekUserService(args, connection));
        Shizuku.bindUserService(args, connection);
        try {
            assertTrue("Published SDK bound an app_process UserService", connected.await(20, TimeUnit.SECONDS));
            assertEquals(17, Shizuku.peekUserService(args, connection));
            Parcel input = Parcel.obtain(), reply = Parcel.obtain();
            try {
                input.writeInterfaceToken(LegacyProbeService.DESCRIPTOR);
                assertTrue(service.get().transact(IBinder.FIRST_CALL_TRANSACTION, input, reply, 0));
                reply.readException();
                assertEquals(Shizuku.getUid(), reply.readInt());
                assertNotEquals(android.os.Process.myPid(), reply.readInt());
                assertEquals(app.getPackageName(), reply.readString());
            } finally { input.recycle(); reply.recycle(); }
        } finally {
            Shizuku.unbindUserService(args, connection, true);
        }
        assertTrue("UserService remove disconnected the stock SDK client", disconnected.await(5, TimeUnit.SECONDS));
        long deadline = SystemClock.elapsedRealtime() + 5_000;
        while (service.get().isBinderAlive() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20);
        assertFalse(service.get().isBinderAlive());
    }

    private static void assertStockRishLoader(Context app) throws Exception {
        Context manager = app.createPackageContext(Protocol.MANAGER, 0);
        java.nio.file.Path directory = java.nio.file.Files.createTempDirectory(app.getCacheDir().toPath(), "rish");
        java.nio.file.Path launcher = directory.resolve("rish");
        java.nio.file.Path dex = directory.resolve("rish_shizuku.dex");
        java.lang.Process command = null;
        try {
            try (var input = manager.getAssets().open("rish/rish")) {
                java.nio.file.Files.copy(input, launcher);
            }
            try (var input = manager.getAssets().open("rish/rish_shizuku.dex")) {
                java.nio.file.Files.copy(input, dex);
            }
            assertTrue("Stock loader DEX must be read-only", dex.toFile().setReadOnly());
            ProcessBuilder builder = new ProcessBuilder("/system/bin/sh", launcher.toString(), "-c", "id -u")
                .directory(directory.toFile()).redirectErrorStream(true);
            builder.environment().put("RISH_APPLICATION_ID", app.getPackageName());
            command = builder.start();
            command.getOutputStream().close();
            assertTrue("Published rish loader returned", command.waitFor(25, TimeUnit.SECONDS));
            String output = readOutput(command.getInputStream());
            assertEquals(output, 0, command.exitValue());
            assertEquals(Integer.toString(Shizuku.getUid()), output);
        } finally {
            if (command != null && command.isAlive()) command.destroyForcibly();
            java.nio.file.Files.deleteIfExists(dex);
            java.nio.file.Files.deleteIfExists(launcher);
            java.nio.file.Files.deleteIfExists(directory);
        }
    }

    private static void assertRish(boolean tty) throws Exception {
        ParcelFileDescriptor[] input = ParcelFileDescriptor.createPipe();
        ParcelFileDescriptor[] output = ParcelFileDescriptor.createPipe();
        ParcelFileDescriptor[] error = tty ? null : ParcelFileDescriptor.createPipe();
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        IBinder binder = Shizuku.getBinder();
        try {
            data.writeInterfaceToken("moe.shizuku.server.IShizukuService");
            data.writeByte((byte) (tty ? 7 : 0));
            data.writeFileDescriptor(input[0].getFileDescriptor());
            data.writeFileDescriptor(output[1].getFileDescriptor());
            if (error != null) data.writeFileDescriptor(error[1].getFileDescriptor());
            data.writeStringArray(new String[]{"-c", tty
                ? "test -t 0 && test -t 1 && test -t 2 && printf tty; exit 19"
                : "printf shell; printf error >&2; exit 19"});
            data.writeStringArray(new String[0]);
            data.writeString("/system");
            assertTrue(binder.transact(30000, data, reply, 0));
            reply.readException();
        } finally {
            data.recycle(); reply.recycle();
            input[0].close(); input[1].close(); output[1].close();
            if (error != null) error[1].close();
        }
        if (tty) assertEquals(0, rishQuery(binder, 30001));
        try (var out = new ParcelFileDescriptor.AutoCloseInputStream(output[0])) {
            assertEquals(tty ? "tty" : "shell", readOutput(out));
        }
        if (error != null) {
            try (var err = new ParcelFileDescriptor.AutoCloseInputStream(error[0])) { assertEquals("error", readOutput(err)); }
        }
        int status = Integer.MAX_VALUE;
        for (int attempt = 0; status == Integer.MAX_VALUE && attempt < 20; attempt++) {
            status = rishQuery(binder, 30002);
            if (status == Integer.MAX_VALUE) SystemClock.sleep(25);
        }
        assertEquals(19, status);
    }

    private static int rishQuery(IBinder binder, int code) throws Exception {
        Parcel input = Parcel.obtain(), reply = Parcel.obtain();
        try {
            input.writeInterfaceToken("moe.shizuku.server.IShizukuService");
            if (code == 30001) input.writeLong(0);
            assertTrue(binder.transact(code, input, reply, 0));
            reply.readException();
            return code == 30002 ? reply.readInt() : 0;
        } finally { input.recycle(); reply.recycle(); }
    }

    private static String readOutput(java.io.InputStream stream) throws java.io.IOException {
        var output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[256];
        for (int count; (count = stream.read(buffer)) != -1;) output.write(buffer, 0, count);
        return output.toString("UTF-8").trim();
    }
}
