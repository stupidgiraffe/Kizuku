package io.github.stupidgiraffe.kizuku.testclient;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import io.github.stupidgiraffe.kizuku.api.IKizukuService;
import io.github.stupidgiraffe.kizuku.api.Protocol;
import rikka.shizuku.Shizuku;
import rikka.shizuku.SystemServiceHelper;
import rikka.shizuku.ShizukuBinderWrapper;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class ProtocolSmokeTest {
    @Test public void separateUidUsesBothProtocolsAndEnforcesIndependentGrants() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Bundle data = app.getContentResolver().call(Uri.parse("content://" + Protocol.AUTHORITY), "connect", null, null);
        assertNotNull(data); assertNotNull(data.getBinder(Protocol.BINDER));
        IKizukuService service = IKizukuService.Stub.asInterface(data.getBinder(Protocol.BINDER));
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
            try { assertThrows(SecurityException.class, () -> new ShizukuBinderWrapper(new Binder()).transact(1,invalid,invalidReply,0)); }
            finally { invalid.recycle();invalidReply.recycle(); }
        }
        if (nativeAllowed) assertTrue(service.diagnostics(app.getPackageName()).getInt("server_uid",-1) <= 2000);
        else assertThrows(SecurityException.class, () -> service.diagnostics(app.getPackageName()));
        if (!legacyAllowed) assertThrows(SecurityException.class, () -> moe.shizuku.server.IShizukuService.Stub.asInterface(Shizuku.getBinder()).getUid());
        if (stage.equals("denied")) service.requestCapabilities(app.getPackageName(), io.github.stupidgiraffe.kizuku.core.Capability.DIAGNOSTICS);
        assertThrows(IllegalArgumentException.class, () -> service.requestCapabilities(app.getPackageName(), io.github.stupidgiraffe.kizuku.core.Capability.SHELL));
    }
}
