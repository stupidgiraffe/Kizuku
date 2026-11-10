package io.github.stupidgiraffe.kizuku.server;

import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.UserHandle;
import android.util.Log;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import io.github.stupidgiraffe.kizuku.api.Protocol;
import moe.shizuku.server.IShizukuService;

/** App-supplied UserService code runs in this separate app_process. */
public final class LegacyUserServiceMain {
    private static IBinder hostLifetime;
    private static IBinder.DeathRecipient hostDeath;
    private static IBinder service;

    public static void main(String[] args) {
        try {
            if (Process.myUid() != 0 && Process.myUid() != 2000) throw new SecurityException("Unexpected UserService UID");
            String packageName = args[0];
            int clientUid = Integer.parseInt(args[2]);
            Looper.prepareMainLooper();
            DataOutputStream pid = new DataOutputStream(System.out);
            pid.writeInt(Process.myPid());
            pid.flush();
            String token = new DataInputStream(System.in).readUTF();

            PlatformBridge platform = new PlatformBridge();
            Bundle host = platform.managerCall("legacy_bootstrap", null);
            if (host == null || host.getBinder(Protocol.BINDER) == null)
                throw new IllegalStateException("Legacy host is unavailable");
            hostLifetime = host.getBinder(Protocol.BINDER);
            hostDeath = () -> System.exit(0);
            hostLifetime.linkToDeath(hostDeath, 0);

            int user = clientUid / 100000;
            PackageInfo info = platform.packageInfo(packageName, user);
            if (info.applicationInfo == null || !args[4].equals(info.applicationInfo.sourceDir)
                || !args[3].equals(platform.identity(clientUid, packageName).fingerprint()))
                throw new SecurityException("UserService installation changed before launch");
            service = createService(packageName, args[1], user, args[4]);

            Bundle options = new Bundle();
            options.putString(LegacyUserServices.TOKEN, token);
            options.putString(LegacyUserServices.DESCRIPTOR, service.getInterfaceDescriptor());
            IShizukuService.Stub.asInterface(hostLifetime).attachUserService(service, options);
            Looper.loop();
        } catch (Throwable failure) {
            Log.e("KizukuUserService", "UserService bootstrap failed", failure);
            System.exit(1);
        }
    }

    private static IBinder createService(String pkg, String service, int user, String apk) throws Exception {
        Class<?> thread = Class.forName("android.app.ActivityThread");
        Object activityThread = thread.getMethod("systemMain").invoke(null);
        Context system = (Context) thread.getMethod("getSystemContext").invoke(activityThread);
        Context context = (Context) Context.class.getMethod("createPackageContextAsUser",
            String.class, int.class, UserHandle.class).invoke(system, pkg,
                Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY, UserHandle.getUserHandleForUid(user * 100000));
        if (!apk.equals(context.getApplicationInfo().sourceDir))
            throw new SecurityException("UserService APK changed during package context creation");

        Field packageInfo = context.getClass().getDeclaredField("mPackageInfo");
        packageInfo.setAccessible(true);
        Object loadedApk = packageInfo.get(context);
        Method makeApplication;
        try { makeApplication = loadedApk.getClass().getDeclaredMethod("makeApplication", boolean.class, Instrumentation.class); }
        catch (NoSuchMethodException newer) {
            makeApplication = loadedApk.getClass().getDeclaredMethod("makeApplicationInner", boolean.class, Instrumentation.class);
        }
        makeApplication.setAccessible(true);
        Application application = (Application) makeApplication.invoke(loadedApk, true, null);
        Field initialApplication = thread.getDeclaredField("mInitialApplication");
        initialApplication.setAccessible(true);
        initialApplication.set(activityThread, application);
        ClassLoader loader = application.getClassLoader();
        Class<? extends IBinder> type = loader.loadClass(service).asSubclass(IBinder.class);
        if (type.getClassLoader() != loader) throw new SecurityException("UserService class is outside the client APK");
        Constructor<? extends IBinder> constructor;
        try { constructor = type.getConstructor(Context.class); }
        catch (NoSuchMethodException absent) { return type.getConstructor().newInstance(); }
        return constructor.newInstance(application);
    }
}
