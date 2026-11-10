package io.github.stupidgiraffe.kizuku.server;

import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import java.util.concurrent.ConcurrentHashMap;
import io.github.stupidgiraffe.kizuku.api.Protocol;
import io.github.stupidgiraffe.kizuku.core.Capability;
import io.github.stupidgiraffe.kizuku.core.ClientIdentity;
import moe.shizuku.api.BinderContainer;
import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuApplication;
import moe.shizuku.server.IShizukuService;
import moe.shizuku.server.IShizukuServiceConnection;
import rikka.hidden.compat.ActivityManagerApis;

/** Stock Shizuku v13 wire using the Kizuku identity and grant policy. */
final class LegacyEndpoint extends IShizukuService.Stub {
    private record Client(ClientIdentity identity, IShizukuApplication callback, int apiVersion) {}
    private record Caller(int uid, int pid) {}
    private final KizukuService host;
    private final ConcurrentHashMap<Caller, Client> attached = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Caller, Integer> requests = new ConcurrentHashMap<>();
    private final java.util.Set<LegacyRemoteProcess> processes = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final LegacyUserServices userServices;
    private final LegacyRish rish;
    LegacyEndpoint(KizukuService host) {
        this.host = host;
        userServices = new LegacyUserServices(host, new Handler(Looper.getMainLooper()));
        rish = new LegacyRish(host);
    }
    private Caller caller() { return new Caller(Binder.getCallingUid(), Binder.getCallingPid()); }
    private Client client(boolean consume) {
        Client c = attached.get(caller());
        if (c == null || !host.allowed(c.identity(), Capability.SHIZUKU, consume)) throw new SecurityException("Legacy Shizuku capability is required");
        return c;
    }
    @Override public void attachApplication(IShizukuApplication callback, Bundle args) throws RemoteException {
        if (callback == null || args == null) throw new IllegalArgumentException("Missing callback or identity");
        ClientIdentity identity = host.identify(Binder.getCallingUid(), args.getString("shizuku:attach-package-name"));
        Caller caller = caller();
        if (attached.size() >= Protocol.MAX_CLIENTS && !attached.containsKey(caller)) throw new SecurityException("Client limit reached");
        Client client = new Client(identity, callback, args.getInt("shizuku:attach-api-version", -1));
        callback.asBinder().linkToDeath(() -> {
            if (attached.remove(caller, client)) {
                requests.remove(caller);
                stopProcesses(process -> process.owner.uid() == caller.uid() && process.ownerPid == caller.pid());
                synchronized (host) {
                    userServices.stopCaller(caller.uid(), caller.pid());
                    rish.stopCaller(caller.uid(), caller.pid());
                }
            }
        }, 0);
        attached.put(caller, client);
        Bundle reply = new Bundle(); reply.putInt("shizuku:attach-reply-version", client.apiVersion() < 0 ? 12 : 13); reply.putInt("shizuku:attach-reply-patch-version", 6);
        reply.putInt("shizuku:attach-reply-uid", Process.myUid()); reply.putString("shizuku:attach-reply-secontext", context());
        reply.putBoolean("shizuku:attach-reply-permission-granted", host.allowed(identity, Capability.SHIZUKU, false));
        reply.putBoolean("shizuku:attach-reply-should-show-request-permission-rationale", false);
        callback.bindApplication(reply);
    }
    @Override public void requestPermission(int requestCode) {
        Caller caller = caller(); Client c = attached.get(caller);
        if (c == null) throw new SecurityException("Client must attach first");
        requests.put(caller, requestCode); host.request(c.identity(), Capability.SHIZUKU);
    }
    void permissionChanged(ClientIdentity identity, boolean allowed) {
        synchronized (host) {
            if (!host.legacyContinuing(identity)) {
                userServices.revoke(identity);
                rish.stop(identity::equals);
            }
        }
        attached.forEach((caller, c) -> {
            if (!c.identity().equals(identity)) return;
            Integer code = requests.remove(caller);
            if (code != null) {
                try { Bundle data = new Bundle(); data.putBoolean("shizuku:request-permission-reply-allowed", allowed && host.allowed(identity, Capability.SHIZUKU, false));
                    data.putBoolean("shizuku:request-permission-reply-is-onetime", false); c.callback().dispatchRequestPermissionResult(code, data);
                } catch (RemoteException dead) { attached.remove(caller, c); }
            }
        });
    }
    void deliver(String packageName) {
        if (packageName == null || !packageName.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) throw new IllegalArgumentException("Invalid package");
        try {
            Bundle data = new Bundle(); data.putParcelable("moe.shizuku.privileged.api.intent.extra.BINDER", new BinderContainer(this));
            if (host.platform.call(packageName + ".shizuku", "sendBinder", null, data) == null) throw new IllegalStateException("Client provider rejected Binder");
        } catch (Exception e) { throw new IllegalStateException("Legacy Binder delivery failed", e); }
    }
    @Override public boolean checkSelfPermission() { Client c = attached.get(caller()); return c != null && host.allowed(c.identity(), Capability.SHIZUKU, false); }
    @Override public boolean shouldShowRequestPermissionRationale() { return false; }
    @Override public int getVersion() { client(false); return 13; }
    @Override public int getUid() { client(false); return Process.myUid(); }
    private String context() {
        try { return (String) Class.forName("android.os.SELinux").getMethod("getContext").invoke(null); }
        catch (Exception e) { return "unavailable"; }
    }
    @Override public String getSELinuxContext() { client(false); return context(); }
    @Override public int checkPermission(String permission) throws RemoteException { client(true); return ActivityManagerApis.checkPermission(permission, Process.myPid(), Process.myUid()); }
    @Override public String getSystemProperty(String name, String defaultValue) {
        client(true);
        if (name == null || name.length() > 128) throw new IllegalArgumentException("Invalid property name");
        try { return (String) Class.forName("android.os.SystemProperties").getMethod("get", String.class, String.class).invoke(null, name, defaultValue); }
        catch (Exception e) { throw new IllegalStateException("Property read failed", e); }
    }
    @Override public void setSystemProperty(String name, String value) { throw unsupported(); }
    @Override public IRemoteProcess newProcess(String[] cmd, String[] env, String dir) {
        synchronized (host) {
            Client client = client(false);
            // A finished process can still be read by its owner. Reap descendants before
            // releasing the host's tracking slot, while retaining the Binder and exit status.
            for (LegacyRemoteProcess process : processes) {
                if (process.running()) continue;
                process.reapDescendants();
                processes.remove(process);
            }
            if (processes.size() >= 6) throw new IllegalStateException("Too many active Shizuku processes");
            try {
                LegacyRemoteProcess process = new LegacyRemoteProcess(host, client.identity(), Binder.getCallingPid(), cmd, env, dir);
                processes.add(process);
                if (!host.allowed(client.identity(), Capability.SHIZUKU, true)) {
                    stopProcesses(candidate -> candidate == process);
                    throw new SecurityException("Shizuku access expired");
                }
                return process;
            } catch (java.io.IOException failure) {
                throw new IllegalStateException("Cannot start the Shizuku process", failure);
            }
        }
    }
    void revokeProcesses(ClientIdentity identity) {
        synchronized (host) {
            stopProcesses(process -> process.owner.equals(identity));
            userServices.revoke(identity);
            rish.stop(identity::equals);
        }
    }
    void stopAllProcesses() {
        synchronized (host) {
            stopProcesses(process -> true);
            userServices.stopAll();
            rish.stop(identity -> true);
        }
    }
    void removeStaleProcesses() {
        synchronized (host) {
            stopProcesses(process -> !host.activeIdentity(process.owner));
            userServices.removeStale();
            rish.stop(identity -> !host.legacyContinuing(identity));
        }
    }
    private void stopProcesses(java.util.function.Predicate<LegacyRemoteProcess> matches) {
        for (LegacyRemoteProcess process : processes) {
            if (matches.test(process) && processes.remove(process)) process.stop();
        }
    }
    private Client continuingClient() {
        Client client = attached.get(caller());
        if (client == null || !host.legacyContinuing(client.identity()))
            throw new SecurityException("A session or permanent Shizuku grant is required");
        return client;
    }
    @Override public int addUserService(IShizukuServiceConnection conn, Bundle args) throws RemoteException {
        synchronized (host) {
            Client client = continuingClient();
            return userServices.add(client.identity(), Binder.getCallingPid(), client.apiVersion(), conn, args);
        }
    }
    @Override public int removeUserService(IShizukuServiceConnection conn, Bundle args) {
        synchronized (host) { return userServices.remove(continuingClient().identity(), conn, args); }
    }
    @Override public void exit() { host.enforceManager(); host.shutdown(); }
    @Override public void attachUserService(IBinder binder, Bundle options) throws RemoteException {
        synchronized (host) { userServices.attach(Binder.getCallingUid(), Binder.getCallingPid(), binder, options); }
    }
    @Override public void dispatchPackageChanged(android.content.Intent intent) { host.enforceManager(); }
    @Override public boolean isHidden(int uid) { host.enforceManager(); return false; }
    @Override public void dispatchPermissionConfirmationResult(int uid, int pid, int code, Bundle data) { host.enforceManager(); throw unsupported(); }
    @Override public int getFlagsForUid(int uid, int mask) { host.enforceManager(); throw unsupported(); }
    @Override public void updateFlagsForUid(int uid, int mask, int value) { host.enforceManager(); throw unsupported(); }
    private static UnsupportedOperationException unsupported() { return new UnsupportedOperationException("Not implemented in the foundation build"); }
    @Override public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (data.dataSize() > Protocol.MAX_IPC_BYTES) throw new SecurityException("IPC size limit exceeded");
        if (code == 14 && reply != null) { // Stock attachApplication through API 12.
            data.enforceInterface("moe.shizuku.server.IShizukuService");
            IShizukuApplication callback = IShizukuApplication.Stub.asInterface(data.readStrongBinder());
            Bundle args = new Bundle();
            args.putString("shizuku:attach-package-name", data.readString());
            attachApplication(callback, args);
            reply.writeNoException();
            return true;
        }
        if (LegacyRish.handles(code)) {
            synchronized (host) { rish.transact(continuingClient().identity(), Binder.getCallingPid(), code, data, reply); }
            return true;
        }
        if (code != 1) return super.onTransact(code, data, reply, flags);
        data.enforceInterface("moe.shizuku.server.IShizukuService");
        Client c = client(true); IBinder target = data.readStrongBinder(); int targetCode = data.readInt();
        int targetFlags = c.apiVersion() >= 13 ? data.readInt() : flags;
        if (target == null || targetCode < IBinder.FIRST_CALL_TRANSACTION || targetCode > IBinder.LAST_CALL_TRANSACTION || (targetFlags & ~IBinder.FLAG_ONEWAY) != 0)
            throw new SecurityException("Invalid Binder target transaction");
        boolean verifiedTarget = false;
        // Validate actual service Binder identity, not a spoofable interface descriptor.
        for (String service : new String[]{"package", "activity", "activity_task", "appops", "permissionmgr", "user", "overlay", "deviceidle", "power"}) {
            try { if (target.equals(PlatformBridge.systemService(service))) { verifiedTarget = true; break; } }
            catch (Exception ignored) { /* Missing service is not a trusted target. */ }
        }
        if (!verifiedTarget) throw new SecurityException("Target is not an allowed Android system service");
        Parcel forwarded = Parcel.obtain(); long identity = Binder.clearCallingIdentity();
        try { forwarded.appendFrom(data, data.dataPosition(), data.dataAvail()); return target.transact(targetCode, forwarded, reply, targetFlags); }
        finally { Binder.restoreCallingIdentity(identity); forwarded.recycle(); }
    }
}
