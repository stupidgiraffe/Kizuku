package io.github.stupidgiraffe.kizuku.server;

import android.content.IContentProvider;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.os.Build;
import android.content.AttributionSource;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import io.github.stupidgiraffe.kizuku.api.Protocol;
import io.github.stupidgiraffe.kizuku.api.AndroidIdentity;
import io.github.stupidgiraffe.kizuku.core.ClientIdentity;
import rikka.hidden.compat.ActivityManagerApis;

/** Narrow hidden-API seam. No installed-package list return-type assumptions. */
final class PlatformBridge {
    private final Object packageManager;
    PlatformBridge() throws Exception {
        packageManager = Class.forName("android.content.pm.IPackageManager$Stub")
            .getMethod("asInterface", IBinder.class).invoke(null, systemService("package"));
    }
    static IBinder systemService(String name) throws Exception {
        return (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, name);
    }
    PackageInfo packageInfo(String name, int user) throws Exception {
        return packageInfo(name, user, 0);
    }
    PackageInfo packageInfo(String name, int user, long extraFlags) throws Exception {
        long flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        flags |= extraFlags;
        Object value;
        try {
            Method m = packageManager.getClass().getMethod("getPackageInfo", String.class, long.class, int.class);
            value = m.invoke(packageManager, name, flags, user);
        } catch (NoSuchMethodException older) {
            value = packageManager.getClass().getMethod("getPackageInfo", String.class, int.class, int.class)
                .invoke(packageManager, name, (int) flags, user);
        }
        if (!(value instanceof PackageInfo)) throw new SecurityException("Package is not installed");
        return (PackageInfo) value;
    }
    static Set<String> signers(PackageInfo info) throws Exception {
        Signature[] signatures = Build.VERSION.SDK_INT >= 28 && info.signingInfo != null
            ? info.signingInfo.getApkContentsSigners() : info.signatures;
        if (signatures == null || signatures.length == 0) throw new SecurityException("No signing certificate");
        Set<String> result = new TreeSet<>();
        for (Signature s : signatures) {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(s.toByteArray());
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            result.add(hex.toString());
        }
        return result;
    }
    ClientIdentity identity(int uid, String claimedPackage) {
        try {
            if (claimedPackage == null || claimedPackage.length() > 255) throw new SecurityException("Invalid package");
            String[] names = (String[]) packageManager.getClass().getMethod("getPackagesForUid", int.class).invoke(packageManager, uid);
            if (names == null || names.length == 0 || names.length > 64) throw new SecurityException("No UID owner");
            Map<String, Set<String>> peers = new TreeMap<>();
            Map<String, Long> installations = new TreeMap<>();
            for (String name : names) {
                PackageInfo pi = packageInfo(name, uid / 100000);
                if (pi.applicationInfo == null || pi.applicationInfo.uid != uid) throw new SecurityException("UID changed");
                peers.put(name, AndroidIdentity.signers(pi));
                installations.put(name, pi.firstInstallTime);
            }
            return new ClientIdentity(uid, claimedPackage, peers, installations);
        } catch (SecurityException e) { throw e; }
        catch (Exception e) { throw new SecurityException("Cannot verify installed signing identity", e); }
    }
    String[] packagesForUid(int uid) throws Exception {
        return (String[]) packageManager.getClass().getMethod("getPackagesForUid", int.class).invoke(packageManager,uid);
    }
    Bundle call(String authority, String method, String arg, Bundle extras) throws Exception {
        long identity = Binder.clearCallingIdentity();
        IContentProvider provider = null;
        try {
            provider = ActivityManagerApis.getContentProviderExternal(authority, 0, null, authority);
            if (provider == null) throw new IllegalStateException("Provider is unavailable");
            if (Build.VERSION.SDK_INT >= 31) {
                return provider.call(new AttributionSource.Builder(Process.myUid()).build(), authority, method, arg, extras);
            } else if (Build.VERSION.SDK_INT >= 30) return provider.call(null, null, authority, method, arg, extras);
            else if (Build.VERSION.SDK_INT >= 29) return provider.call((String) null, authority, method, arg, extras);
            else return provider.call(null, method, arg, extras);
        } finally {
            if (provider != null) ActivityManagerApis.removeContentProviderExternal(authority, null);
            Binder.restoreCallingIdentity(identity);
        }
    }
    Bundle managerCall(String method, Bundle extras) throws Exception { return call(Protocol.AUTHORITY, method, null, extras); }
}
