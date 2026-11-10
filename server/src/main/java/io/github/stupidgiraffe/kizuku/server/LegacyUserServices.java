package io.github.stupidgiraffe.kizuku.server;

import android.content.ComponentName;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import io.github.stupidgiraffe.kizuku.api.Protocol;
import io.github.stupidgiraffe.kizuku.core.ClientIdentity;
import moe.shizuku.server.IShizukuServiceConnection;

/** Owns app_process children and stock callback records. Guarded by the KizukuService lock. */
final class LegacyUserServices {
    static final String TOKEN = "shizuku:user-service-arg-token";
    static final String DESCRIPTOR = "kizuku:user-service-descriptor";
    private static final String ARG = "shizuku:user-service-arg-";
    private static final int DESTROY = 16777115;
    private record Key(ClientIdentity identity, String tag) {}
    private record Connection(int pid, IShizukuServiceConnection callback, IBinder.DeathRecipient death) {}

    private final class Service {
        final Key key;
        final String token = UUID.randomUUID().toString();
        final Map<IBinder, Connection> callbacks = new HashMap<>();
        final String apk;
        final long lastUpdate;
        final int version;
        final Runnable timeout = () -> { synchronized (host) { remove(this, true); } };
        boolean daemon;
        java.lang.Process process;
        int pid;
        IBinder binder;
        String descriptor;
        IBinder.DeathRecipient death;

        Service(Key key, PackageInfo info, int version, boolean daemon) {
            this.key = key;
            this.version = version;
            this.daemon = daemon;
            apk = info.applicationInfo.sourceDir;
            lastUpdate = info.lastUpdateTime;
        }
    }

    private final KizukuService host;
    private final Handler handler;
    private final ExecutorService launcher = Executors.newFixedThreadPool(4);
    private final Map<Key, Service> services = new HashMap<>();

    LegacyUserServices(KizukuService host, Handler handler) {
        this.host = host;
        this.handler = handler;
    }

    @SuppressWarnings("deprecation")
    private static ComponentName component(ClientIdentity identity, Bundle args) {
        if (args == null) throw new IllegalArgumentException("Missing UserService arguments");
        ComponentName component = args.getParcelable(ARG + "component");
        if (component == null || !identity.packageName().equals(component.getPackageName()))
            throw new SecurityException("UserService must belong to the attached package");
        if (component.getClassName().length() > 512) throw new IllegalArgumentException("UserService class name is too long");
        return component;
    }

    private static Key key(ClientIdentity identity, ComponentName component, Bundle args) {
        String tag = args.getString(ARG + "tag", component.getClassName());
        if (tag.length() > 512) throw new IllegalArgumentException("UserService tag is too long");
        return new Key(identity, tag);
    }

    int add(ClientIdentity identity, int pid, int apiVersion, IShizukuServiceConnection callback, Bundle args) throws RemoteException {
        if (callback == null) throw new IllegalArgumentException("Missing UserService connection");
        ComponentName component = component(identity, args);
        Key key = key(identity, component, args);
        Service existing = services.get(key);
        if (existing != null && !valid(existing)) {
            remove(existing, true);
            existing = null;
        }
        if (args.getBoolean(ARG + "no-create")) {
            if (existing != null) {
                connect(existing, pid, callback);
                if (existing.binder != null) return apiVersion >= 13 ? existing.version : 0;
            }
            return apiVersion >= 13 ? -1 : 1;
        }
        int version = args.getInt(ARG + "version-code", 1);
        boolean daemon = args.getBoolean(ARG + "daemon", true);
        if (existing != null && existing.version == version) {
            existing.daemon = daemon;
            connect(existing, pid, callback);
            return 0;
        }
        if (existing != null) remove(existing, false);
        long count = services.keySet().stream().filter(item -> item.identity().uid() == identity.uid()).count();
        if (count >= 6 || services.size() >= 24) throw new IllegalStateException("UserService limit reached");

        String suffix = args.getString(ARG + "process-name", "user_service");
        if (suffix.length() > 128 || suffix.indexOf(0) != -1)
            throw new IllegalArgumentException("Invalid UserService process name");
        Service service;
        try { service = new Service(key, packageInfo(identity), version, daemon); }
        catch (Exception e) { throw new SecurityException("Cannot verify UserService APK", e); }
        String className = component.getClassName();
        boolean use32 = args.getBoolean(ARG + "use-32-bit-app-process");
        boolean debug = args.getBoolean(ARG + "debuggable");
        services.put(key, service);
        try {
            connect(service, pid, callback);
            handler.postDelayed(service.timeout, 30_000);
            launcher.execute(() -> launch(service, className, suffix, use32, debug));
        } catch (RuntimeException | RemoteException failure) {
            remove(service, true);
            throw failure;
        }
        return 0;
    }

    int remove(ClientIdentity identity, IShizukuServiceConnection callback, Bundle args) {
        Service service = services.get(key(identity, component(identity, args), args));
        if (service == null) return 1;
        if (args.getBoolean("shizuku:user-service-remove", true)) remove(service, false);
        else if (callback != null) disconnect(service, callback.asBinder(), false);
        return 0;
    }

    private void connect(Service service, int pid, IShizukuServiceConnection callback) throws RemoteException {
        IBinder binder = callback.asBinder();
        if (!service.callbacks.containsKey(binder)) {
            if (service.callbacks.size() >= Protocol.MAX_CLIENTS) throw new IllegalStateException("UserService connection limit reached");
            IBinder.DeathRecipient death = () -> { synchronized (host) { disconnect(service, binder, true); } };
            binder.linkToDeath(death, 0);
            service.callbacks.put(binder, new Connection(pid, callback, death));
        }
        if (service.binder != null) callback.connected(service.binder);
    }

    private void disconnect(Service service, IBinder binder, boolean dead) {
        Connection connection = service.callbacks.remove(binder);
        if (connection != null) binder.unlinkToDeath(connection.death(), 0);
        if (dead && !service.daemon && service.callbacks.isEmpty()) remove(service, false);
    }

    private PackageInfo packageInfo(ClientIdentity identity) throws Exception {
        return host.platform.packageInfo(identity.packageName(), identity.uid() / 100000);
    }

    private boolean valid(Service service) {
        ClientIdentity identity = service.key.identity();
        if (!host.legacyContinuing(identity)) return false;
        try {
            PackageInfo info = packageInfo(identity);
            return info.applicationInfo != null && info.applicationInfo.uid == identity.uid()
                && service.apk.equals(info.applicationInfo.sourceDir) && service.lastUpdate == info.lastUpdateTime;
        } catch (Exception missing) { return false; }
    }

    private void launch(Service service, String className, String suffix, boolean use32, boolean debug) {
        ClientIdentity identity = service.key.identity();
        String name = identity.packageName() + ":" + suffix;
        try {
            List<String> args = new ArrayList<>();
            args.add(use32 && new File("/system/bin/app_process32").exists()
                ? "/system/bin/app_process32" : "/system/bin/app_process");
            if (debug) {
                args.addAll(Arrays.asList("-Xcompiler-option", "--debuggable"));
                if (Build.VERSION.SDK_INT >= 28) {
                    args.add(Build.VERSION.SDK_INT >= 30 ? "-XjdwpProvider:adbconnection" : "-XjdwpProvider:internal");
                    args.add(Build.VERSION.SDK_INT >= 30 ? "-XjdwpOptions:suspend=n,server=y"
                        : "-XjdwpOptions:transport=dt_android_adb,suspend=n,server=y");
                } else args.add("-agentlib:jdwp=transport=dt_android_adb,suspend=n,server=y");
            }
            args.addAll(Arrays.asList("/system/bin", "--nice-name=" + name, LegacyUserServiceMain.class.getName(),
                identity.packageName(), className, Integer.toString(identity.uid()), identity.fingerprint(), service.apk));
            ProcessBuilder builder = new ProcessBuilder(args);
            builder.environment().put("CLASSPATH", host.platform.packageInfo(Protocol.MANAGER, 0).applicationInfo.sourceDir);
            builder.redirectError(new File("/dev/null"));
            java.lang.Process child;
            synchronized (host) {
                if (services.get(service.key) != service || !valid(service)) return;
                child = builder.start();
                service.process = child;
            }
            // The launch token stays in a private pipe rather than argv or logs.
            try (DataInputStream pid = new DataInputStream(child.getInputStream());
                 DataOutputStream bootstrap = new DataOutputStream(child.getOutputStream())) {
                int launchedPid = pid.readInt();
                synchronized (host) {
                    if (services.get(service.key) != service) return;
                    if (launchedPid <= 0 || !child.isAlive()) throw new IllegalStateException("UserService exited");
                    service.pid = launchedPid;
                    bootstrap.writeUTF(service.token);
                    bootstrap.flush();
                }
            }
        } catch (Exception failure) {
            Log.w("KizukuServer", "UserService launch failed for " + identity.packageName(), failure);
            synchronized (host) { remove(service, true); }
        }
    }

    void attach(int uid, int pid, IBinder binder, Bundle options) throws RemoteException {
        if (uid != android.os.Process.myUid() || pid <= 0 || binder == null || options == null)
            throw new SecurityException("Unrecognized UserService child");
        String token = options.getString(TOKEN);
        for (Service service : services.values()) {
            if (service.pid != pid || !service.token.equals(token) || service.binder != null
                || service.process == null || !service.process.isAlive() || !valid(service)) continue;
            IBinder.DeathRecipient death = () -> { synchronized (host) { remove(service, true); } };
            binder.linkToDeath(death, 0);
            service.binder = binder;
            service.descriptor = options.getString(DESCRIPTOR);
            service.death = death;
            handler.removeCallbacks(service.timeout);
            for (Connection connection : service.callbacks.values()) {
                try { connection.callback().connected(binder); } catch (RemoteException ignored) {}
            }
            return;
        }
        throw new SecurityException("Unrecognized UserService launch token");
    }

    private void remove(Service service, boolean immediate) {
        if (!services.remove(service.key, service)) return;
        handler.removeCallbacks(service.timeout);
        for (Map.Entry<IBinder, Connection> entry : service.callbacks.entrySet()) {
            entry.getKey().unlinkToDeath(entry.getValue().death(), 0);
            try { entry.getValue().callback().died(); } catch (RemoteException ignored) {}
        }
        service.callbacks.clear();
        if (service.binder != null) {
            service.binder.unlinkToDeath(service.death, 0);
            if (!immediate && service.descriptor != null)
                launcher.execute(() -> destroyBinder(service.binder, service.descriptor));
        }
        if (service.process != null) {
            if (immediate) service.process.destroyForcibly();
            else handler.postDelayed(service.process::destroyForcibly, 500);
        }
    }

    private static void destroyBinder(IBinder binder, String descriptor) {
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(descriptor);
            binder.transact(DESTROY, data, null, IBinder.FLAG_ONEWAY);
        } catch (RemoteException ignored) {
            // The app_process instance also has a bounded shutdown deadline.
        } finally { data.recycle(); }
    }

    void revoke(ClientIdentity identity) {
        for (Service service : new ArrayList<>(services.values())) {
            if (service.key.identity().equals(identity)) remove(service, true);
        }
    }

    void stopCaller(int uid, int pid) {
        for (Service service : new ArrayList<>(services.values())) {
            if (service.key.identity().uid() != uid) continue;
            for (Map.Entry<IBinder, Connection> callback : new HashMap<>(service.callbacks).entrySet()) {
                if (callback.getValue().pid() == pid) disconnect(service, callback.getKey(), true);
            }
        }
    }

    void removeStale() {
        for (Service service : new ArrayList<>(services.values())) {
            if (!valid(service) || service.process != null && !service.process.isAlive()) remove(service, true);
        }
    }

    void stopAll() {
        for (Service service : new ArrayList<>(services.values())) remove(service, true);
    }
}
