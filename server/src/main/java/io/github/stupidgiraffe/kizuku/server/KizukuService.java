package io.github.stupidgiraffe.kizuku.server;

import android.content.pm.PackageInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Process;
import android.os.SystemClock;
import android.os.RemoteException;
import android.os.Parcel;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.json.JSONArray;
import org.json.JSONObject;
import io.github.stupidgiraffe.kizuku.api.IKizukuService;
import io.github.stupidgiraffe.kizuku.api.Protocol;
import io.github.stupidgiraffe.kizuku.api.AndroidIdentity;
import io.github.stupidgiraffe.kizuku.core.Capability;
import io.github.stupidgiraffe.kizuku.core.ClientIdentity;
import io.github.stupidgiraffe.kizuku.core.GrantLedger;

final class KizukuService extends IKizukuService.Stub {
    final PlatformBridge platform;
    private final ClientIdentity manager;
    private final String managerApk;
    private final Handler main;
    private final long started = SystemClock.elapsedRealtime();
    private GrantLedger ledger = new GrantLedger();
    private final Map<String, ClientIdentity> clients = new LinkedHashMap<>();
    private final Map<ClientIdentity, Long> pending = new LinkedHashMap<>();
    private volatile boolean safeMode;
    final LegacyEndpoint legacy;
    private final AxRuntime runtime;
    private final AxeronEndpoint axeron;
    private final String runtimeError;
    private final java.util.concurrent.ExecutorService runtimeLifecycle = java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile boolean closed;

    KizukuService(PlatformBridge platform, Handler main) throws Exception {
        this.platform = platform;
        this.main = main;
        PackageInfo pi = platform.packageInfo(Protocol.MANAGER, 0);
        if (pi.applicationInfo == null) throw new SecurityException("Missing manager");
        manager = platform.identity(pi.applicationInfo.uid, Protocol.MANAGER);
        managerApk = pi.applicationInfo.sourceDir;
        legacy = new LegacyEndpoint(this);
        Bundle configuration = platform.loadManagerConfiguration();
        if (configuration == null) throw new IllegalStateException("Cannot load authorization store");
        safeMode = configuration.getBoolean("safe_mode", false);
        AxRuntime available = null;
        String error = null;
        try { available = new AxRuntime(); }
        catch (Exception failure) {
            error = failure.getMessage() == null ? "Runtime directory is unavailable" : failure.getMessage();
            Log.w("KizukuRuntime", "Runtime initialization failed", failure);
        }
        runtime = available;
        runtimeError = error;
        axeron = new AxeronEndpoint(this, main, this::launchAxeron);
        JSONArray saved = new JSONArray(configuration.getString("grants", "[]"));
        if (saved.length() > Protocol.MAX_CLIENTS) throw new SecurityException("Too many saved grants");
        for (int i = 0; i < saved.length(); i++) {
            JSONObject g = saved.getJSONObject(i);
            ClientIdentity recorded = AndroidIdentity.decode(g);
            try {
                ClientIdentity installed = platform.identity(recorded.uid(), recorded.packageName());
                if (!recorded.equals(installed)) continue;
                long mask = g.getLong("mask") & Protocol.SUPPORTED;
                if (mask == 0) continue;
                ledger.grant(recorded, mask, GrantLedger.Lifetime.PERMANENT);
                clients.put(key(recorded), recorded);
            } catch (SecurityException removedOrReplaced) { /* Never upgrade stale trust. */ }
        }
    }
    void startRuntime() {
        if (runtime == null) return;
        axeron.start();
        if (safeMode) runtime.pause();
        else {
            clients.values().forEach(axeron::deliver);
            runtimeLifecycle.execute(runtime.plugins::startEnabled);
        }
    }
    void sweepResources() {
        legacy.removeStaleProcesses();
        if (runtime != null && !closed) runtime.sweepOwners(this::activeIdentity);
    }
    void close() {
        if (closed) return;
        closed = true;
        runtimeLifecycle.shutdownNow();
        legacy.stopAllProcesses();
        if (runtime != null) runtime.close();
    }
    private static String key(ClientIdentity c) { return c.uid() + ":" + c.packageName(); }
    synchronized ClientIdentity identify(int uid, String packageName) {
        ClientIdentity client = platform.identity(uid, packageName);
        String key = key(client);
        if (!clients.containsKey(key) && clients.size() >= Protocol.MAX_CLIENTS) throw new SecurityException("Client limit reached");
        ClientIdentity previous = clients.get(key);
        if (previous != null && !previous.equals(client)) {
            ledger.revokePrincipal(uid, packageName, Capability.KNOWN);
            pending.remove(previous);
            try { platform.managerCall("load_grants", null); } catch (Exception failure) { safeMode = true; }
        }
        clients.put(key, client);
        return client;
    }
    void enforceManager() {
        ClientIdentity current = platform.identity(Binder.getCallingUid(), Protocol.MANAGER);
        if (!manager.equals(current)) throw new SecurityException("Manager signing identity mismatch");
    }
    boolean managerValid() {
        try {
            PackageInfo current = platform.packageInfo(Protocol.MANAGER,0);
            return manager.equals(platform.identity(manager.uid(),Protocol.MANAGER))
                && current.applicationInfo != null && managerApk.equals(current.applicationInfo.sourceDir);
        } catch (Exception removedOrReplaced) { return false; }
    }
    boolean activeIdentity(ClientIdentity identity) {
        if (closed || safeMode || !managerValid()) return false;
        try { return identity.equals(platform.identity(identity.uid(), identity.packageName())); }
        catch (SecurityException removed) { return false; }
    }
    synchronized boolean allowed(ClientIdentity identity, long mask, boolean consume) {
        if (closed || safeMode || !managerValid()) return false;
        try {
            if (!identity.equals(platform.identity(identity.uid(), identity.packageName()))) {
                ledger.revokePrincipal(identity.uid(), identity.packageName(), Capability.KNOWN);
                try { platform.managerCall("load_grants", null); } catch (Exception failure) { safeMode = true; }
                return false;
            }
        } catch (SecurityException gone) {
            ledger.revokePrincipal(identity.uid(), identity.packageName(), Capability.KNOWN);
            try { platform.managerCall("load_grants", null); } catch (Exception failure) { safeMode = true; }
            return false;
        }
        return consume ? ledger.consume(identity, mask) : ledger.allows(identity, mask);
    }
    synchronized boolean legacyContinuing(ClientIdentity identity) {
        return allowed(identity, Capability.SHIZUKU, false) && ledger.allowsContinuing(identity, Capability.SHIZUKU);
    }
    synchronized void request(ClientIdentity client, long mask) {
        if (mask <= 0 || (mask & ~Protocol.SUPPORTED) != 0) throw new IllegalArgumentException("Capability is not implemented");
        pending.merge(client, mask, (existing, incoming) -> existing | incoming);
        notifyManager();
    }
    private void notifyManager() {
        try { platform.managerCall("changed", null); } catch (Exception ignored) { /* Pending remains queryable. */ }
    }
    @Override public Bundle status() {
        enforceManager();
        Bundle status = new Bundle();
        status.putInt("uid", Process.myUid()); status.putInt("pid", Process.myPid());
        status.putInt("protocol", Protocol.VERSION);
        status.putLong("uptime_ms", SystemClock.elapsedRealtime() - started);
        status.putBoolean("safe_mode", safeMode);
        status.putBoolean("axeron_runtime", runtime != null && !safeMode && !closed);
        status.putString("runtime_error", runtimeError);
        status.putString("transport", Process.myUid() == 0 ? "Root" : "ADB");
        return status;
    }
    @Override public synchronized Bundle negotiate(String packageName, int minimumVersion, int maximumVersion) {
        if (minimumVersion > Protocol.VERSION || maximumVersion < Protocol.VERSION || minimumVersion < 1 || minimumVersion > maximumVersion)
            throw new IllegalArgumentException("No compatible native protocol version");
        ClientIdentity client = identify(Binder.getCallingUid(), packageName);
        Bundle result = new Bundle(); result.putInt("protocol", Protocol.VERSION);
        result.putLong("supported", runtime != null ? Protocol.SUPPORTED : Protocol.SUPPORTED & ~Capability.AXERON);
        result.putLong("granted", safeMode ? 0 : ledger.capabilities(client));
        return result;
    }
    @Override public void requestCapabilities(String packageName, long capabilities) { request(identify(Binder.getCallingUid(), packageName), capabilities); }
    @Override public Bundle diagnostics(String packageName) {
        ClientIdentity client = identify(Binder.getCallingUid(), packageName);
        if (!allowed(client, Capability.DIAGNOSTICS, true)) throw new SecurityException("Native diagnostics capability is required");
        Bundle result = new Bundle(); result.putInt("server_uid", Process.myUid()); result.putInt("server_pid", Process.myPid());
        result.putInt("sdk", android.os.Build.VERSION.SDK_INT); result.putLong("uptime_ms", SystemClock.elapsedRealtime() - started);
        return result;
    }
    @Override public synchronized Bundle[] applications() {
        enforceManager();
        Bundle[] result = new Bundle[clients.size()]; int i = 0;
        for (ClientIdentity client : clients.values()) {
            Bundle app = new Bundle(); app.putString("package", client.packageName()); app.putInt("uid", client.uid());
            boolean verified;
            try { verified = client.equals(platform.identity(client.uid(), client.packageName())); } catch (SecurityException missing) { verified = false; }
            if (!verified) {
                ledger.revokePrincipal(client.uid(),client.packageName(),Capability.KNOWN);
                pending.remove(client);
                try { platform.managerCall("load_grants",null); } catch (Exception failure) { safeMode=true; }
            }
            app.putLong("granted", verified && !safeMode ? ledger.capabilities(client) : 0);
            app.putLong("assigned", verified ? ledger.capabilities(client) : 0);
            app.putLong("requested", verified ? pending.getOrDefault(client, 0L) : 0L);
            app.putBoolean("verified", verified);
            try { app.putString("identity", AndroidIdentity.encode(client).toString()); } catch (Exception failure) { throw new IllegalStateException(failure); }
            app.putString("certificate", client.members().get(client.packageName()).iterator().next());
            app.putStringArray("shared_uid", client.members().keySet().toArray(new String[0]));
            result[i++] = app;
        }
        return result;
    }
    @Override public synchronized void grant(String packageName, int uid, long capabilities, int lifetime, String expectedIdentity) {
        enforceManager();
        if ((capabilities & ~Protocol.SUPPORTED) != 0 || lifetime < 0 || lifetime >= GrantLedger.Lifetime.values().length)
            throw new IllegalArgumentException("Unsupported grant");
        ClientIdentity client = identify(uid, packageName);
        if (!client.fingerprint().equals(expectedIdentity)) throw new SecurityException("Approval identity changed; review again");
        GrantLedger candidate = ledger.copy(); candidate.grant(client, capabilities, GrantLedger.Lifetime.values()[lifetime]);
        ledger = candidate;
        permissionChanged(client, capabilities, true);
    }
    @Override public synchronized void revoke(String packageName, int uid, long capabilities) {
        enforceManager(); ClientIdentity client = identify(uid, packageName);
        GrantLedger candidate = ledger.copy(); candidate.revokePrincipal(uid, packageName, capabilities);
        ledger = candidate;
        permissionChanged(client, capabilities, false);
    }
    private void permissionChanged(ClientIdentity client, long capabilities, boolean granted) {
        long remaining = pending.getOrDefault(client, 0L) & ~capabilities;
        if (remaining == 0) pending.remove(client); else pending.put(client, remaining);
        if ((capabilities & Capability.SHIZUKU) != 0) {
            if (!granted) legacy.revokeProcesses(client);
            legacy.permissionChanged(client, granted);
        }
        if ((capabilities & Capability.AXERON) != 0 && runtime != null) {
            if (!granted) runtime.stopOwner(client);
            axeron.permissionChanged(client);
        }
        notifyManager();
    }
    @Override public void deliverLegacy(String packageName) { enforceManager(); legacy.deliver(packageName); }
    @Override public IBinder legacyBinder() { enforceManager(); return legacy; }
    @Override public synchronized void setSafeMode(boolean enabled) {
        enforceManager();
        if (safeMode != enabled) {
            safeMode = enabled;
            if (enabled) legacy.stopAllProcesses();
            if (runtime != null) {
                if (enabled) runtime.pause();
                else {
                    runtime.resume();
                    runtimeLifecycle.execute(runtime.plugins::startEnabled);
                }
                clients.values().forEach(axeron::permissionChanged);
            }
        }
        notifyManager();
    }
    @Override public void shutdown() { enforceManager(); main.postDelayed(() -> { close(); System.exit(0); }, 100); }

    private AxRuntime requireRuntime() {
        if (closed || safeMode) throw new SecurityException("The runtime is suspended");
        if (runtime == null) throw new IllegalStateException(runtimeError);
        return runtime;
    }
    private interface RuntimeAction<T> { T run(AxRuntime runtime) throws Exception; }
    private <T> T managerRuntime(RuntimeAction<T> action) {
        enforceManager();
        try { return action.run(requireRuntime()); }
        catch (RuntimeException failure) { throw failure; }
        catch (Exception failure) { throw new IllegalStateException(failure.getMessage(), failure); }
    }
    @Override public Bundle runtimeCommand(String command) { return managerRuntime(runtime -> runtime.shell(command)); }
    @Override public Bundle[] runtimePlugins() { return managerRuntime(runtime -> runtime.plugins.list()); }
    @Override public Bundle installRuntimePlugin(ParcelFileDescriptor archive) {
        try { return managerRuntime(runtime -> runtime.plugins.install(archive)); }
        finally { if (archive != null) try { archive.close(); } catch (java.io.IOException ignored) {} }
    }
    @Override public Bundle controlRuntimePlugin(String id, String operation) {
        return managerRuntime(runtime -> runtime.plugins.control(id, operation));
    }
    @Override public ParcelFileDescriptor openRuntimeWebAsset(String id, String path) {
        return managerRuntime(runtime -> runtime.plugins.asset(id, path));
    }
    @Override public Bundle runtimeWebCommand(String id, String command, String directory) {
        return managerRuntime(runtime -> runtime.plugins.webCommand(id, command, directory));
    }
    @Override public Bundle systemSetting(String table, String key, String value, String operation) {
        return managerRuntime(runtime -> runtime.setting(table, key, value, operation));
    }
    @Override public Bundle setPackageEnabled(String packageName, boolean enabled) {
        return managerRuntime(runtime -> {
            PackageInfo info = platform.packageInfo(packageName, 0);
            if (info.applicationInfo == null || info.applicationInfo.uid < 10000
                    || (info.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                    || Protocol.MANAGER.equals(packageName) || "moe.shizuku.privileged.api".equals(packageName))
                throw new SecurityException("Only user-installed apps outside Kizuku can be changed");
            return runtime.setPackageEnabled(packageName, enabled);
        });
    }
    @Override public void requestAxeronAccess(String packageName, int uid) {
        enforceManager();
        requireRuntime();
        ClientIdentity owner = identify(uid, packageName);
        if (allowed(owner, Capability.AXERON, false)) axeron.deliver(owner);
        else request(owner, Capability.AXERON);
    }
    private synchronized frb.axeron.server.IAxshProcess launchAxeron(ClientIdentity owner, String[] argv, String[] env,
            String directory, ParcelFileDescriptor stdin, ParcelFileDescriptor stdout, ParcelFileDescriptor stderr,
            boolean pty, long size, ParcelFileDescriptor status) throws Exception {
        if (!allowed(owner, Capability.AXERON, false)) throw new SecurityException("Axeron capability is required");
        var process = requireRuntime().launch(owner, argv, env, directory, stdin, stdout, stderr, pty, size, status);
        ledger.consume(owner, Capability.AXERON);
        return process;
    }
    @Override public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (data.dataSize() > Protocol.MAX_IPC_BYTES) throw new SecurityException("IPC size limit exceeded");
        return super.onTransact(code, data, reply, flags);
    }
}
