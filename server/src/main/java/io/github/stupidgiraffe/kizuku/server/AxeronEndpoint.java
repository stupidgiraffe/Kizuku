package io.github.stupidgiraffe.kizuku.server;

import android.app.ActivityManagerHidden;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import frb.axeron.server.Environment;
import frb.axeron.server.IAxeronService;
import frb.axeron.server.IAxshProcess;
import frb.axeron.server.IFileService;
import frb.axeron.server.PluginInfo;
import frb.axeron.server.ServerInfo;
import io.github.stupidgiraffe.kizuku.api.Protocol;
import io.github.stupidgiraffe.kizuku.core.Capability;
import io.github.stupidgiraffe.kizuku.core.ClientIdentity;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import moe.shizuku.server.IShizukuApplication;
import moe.shizuku.server.IShizukuService;
import rikka.hidden.compat.ActivityManagerApis;
import rikka.hidden.compat.adapter.ProcessObserverAdapter;
import rikka.hidden.compat.adapter.UidObserverAdapter;
import rikka.parcelablelist.ParcelableListSlice;

/** Axeron-API 3.0.0 client wire over Kizuku's existing host and authorization ledger. */
final class AxeronEndpoint {
    interface ProcessLauncher {
        IAxshProcess launch(ClientIdentity owner, String[] argv, String[] env, String dir,
                            ParcelFileDescriptor stdin, ParcelFileDescriptor stdout,
                            ParcelFileDescriptor stderr, boolean pty, long size,
                            ParcelFileDescriptor status) throws Exception;
    }

    private record Principal(int uid, String packageName) {}
    private record Delivery(Principal principal, int pid) {}
    private record Attachment(IShizukuApplication callback, IBinder.DeathRecipient death,
                              AtomicReference<Integer> request) {}

    private static final String TAG = "KizukuAxeron";
    private static final String PROVIDER_PERMISSION = "android.permission.INTERACT_ACROSS_USERS_FULL";
    private static final String CLIENT_UID = "axeron:bind-application-client-uid";
    private static final String ATTACH_PACKAGE = "shizuku:attach-package-name";
    private static final String ATTACH_API_VERSION = "shizuku:attach-api-version";
    private static final String REPLY_ALLOWED = "shizuku:request-permission-reply-allowed";
    private static final String DESCRIPTOR = "frb.axeron.server.IAxeronService";
    private static final int TRANSACTION_NEW_PROCESS = IBinder.FIRST_CALL_TRANSACTION + 112;

    private final KizukuService host;
    private final Handler handler;
    private final ProcessLauncher launcher;
    private final long starting = SystemClock.elapsedRealtime();
    private final Map<Principal, ClientCapability> capabilities = new ConcurrentHashMap<>();
    private final Map<Principal, ClientIdentity> discoverable = new ConcurrentHashMap<>();
    private final Set<Delivery> delivered = ConcurrentHashMap.newKeySet();
    // A client provider may block. Keep its Binder traffic off the host lifecycle looper.
    private final ThreadPoolExecutor delivery = new ThreadPoolExecutor(2, 2, 10, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(32), task -> {
            Thread thread = new Thread(task, "kizuku-axeron-provider");
            thread.setDaemon(true);
            return thread;
        });

    AxeronEndpoint(KizukuService host, Handler handler, ProcessLauncher launcher) {
        this.host = host;
        this.handler = handler;
        this.launcher = launcher;
        delivery.allowCoreThreadTimeOut(true);
    }

    void start() {
        try {
            ActivityManagerApis.registerProcessObserver(new ProcessObserverAdapter() {
                @Override public void onForegroundActivitiesChanged(int pid, int uid, boolean foreground) {
                    if (foreground) discover(uid, pid);
                }
                @Override public void onProcessStateChanged(int pid, int uid, int state) {
                    discover(uid, pid);
                }
                @Override public void onProcessDied(int pid, int uid) {
                    delivered.removeIf(item -> item.principal().uid() == uid && item.pid() == pid);
                }
            });
        } catch (Exception failure) {
            Log.w(TAG, "Process observation unavailable", failure);
        }
        try {
            ActivityManagerApis.registerUidObserver(new UidObserverAdapter() {
                @Override public void onUidActive(int uid) { discover(uid, -1); }
                @Override public void onUidGone(int uid, boolean disabled) {
                    delivered.removeIf(item -> item.principal().uid() == uid);
                }
            }, ActivityManagerHidden.UID_OBSERVER_ACTIVE | ActivityManagerHidden.UID_OBSERVER_GONE,
                ActivityManagerHidden.PROCESS_STATE_UNKNOWN, null);
        } catch (Exception failure) {
            Log.w(TAG, "UID observation unavailable", failure);
        }
    }

    private void discover(int uid, int pid) {
        discoverable.forEach((principal, identity) -> {
            if (principal.uid() != uid) return;
            Delivery item = new Delivery(principal, pid);
            if (delivered.add(item)) scheduleDelivery(identity, item);
        });
    }

    void deliver(ClientIdentity identity) {
        if (!host.allowed(identity, Capability.AXERON, false)) return;
        discoverable.put(principal(identity), identity);
        scheduleDelivery(identity, null);
    }

    private void scheduleDelivery(ClientIdentity identity, Delivery item) {
        try {
            delivery.execute(() -> {
                if (!sendCapability(identity) && item != null) delivered.remove(item);
            });
        } catch (RejectedExecutionException full) {
            if (item != null) delivered.remove(item);
            Log.w(TAG, "Axeron provider delivery queue is full");
        }
    }

    private boolean sendCapability(ClientIdentity identity) {
        if (!host.allowed(identity, Capability.AXERON, false)) return false;
        String authority = identity.packageName() + ".axeron";
        int userId = identity.uid() / 100000;
        try {
            PackageInfo info = host.platform.packageInfo(identity.packageName(), userId, PackageManager.GET_PROVIDERS);
            if (info.applicationInfo == null || info.applicationInfo.uid != identity.uid()
                || info.providers == null || !info.applicationInfo.enabled) return false;
            for (ProviderInfo provider : info.providers) {
                if (!authority.equals(provider.authority) || !provider.exported || !provider.enabled
                    || !PROVIDER_PERMISSION.equals(provider.readPermission)
                    || !PROVIDER_PERMISSION.equals(provider.writePermission)) continue;
                if (!host.allowed(identity, Capability.AXERON, false)) return false;
                Bundle data = new Bundle();
                data.putBinder("axeron:capability", capabilityFor(identity));
                return host.platform.call(authority, userId, "sendCapability", null, data) != null;
            }
        } catch (Exception unavailable) {
            Log.w(TAG, "Axeron client provider unavailable for " + identity.packageName());
        }
        return false;
    }

    void permissionChanged(ClientIdentity identity) {
        // KizukuService may still hold its ledger lock. Never make callback IPC on that stack.
        handler.post(() -> {
            boolean allowed = host.allowed(identity, Capability.AXERON, false);
            Principal principal = principal(identity);
            ClientCapability capability = capabilities.get(principal);
            if (capability != null && capability.identity.equals(identity)) capability.answer(allowed);
            if (allowed) {
                deliver(identity);
            } else {
                discoverable.remove(principal, identity);
                delivered.removeIf(item -> item.principal().equals(principal));
            }
        });
    }

    IBinder capability(String packageName, int uid) {
        host.enforceManager();
        return capabilityFor(host.identify(uid, packageName));
    }

    private ClientCapability capabilityFor(ClientIdentity identity) {
        return capabilities.compute(principal(identity), (key, current) ->
            current != null && current.identity.equals(identity) ? current : new ClientCapability(identity));
    }

    private static Principal principal(ClientIdentity identity) {
        return new Principal(identity.uid(), identity.packageName());
    }

    private void verifyOwner(ClientIdentity owner) {
        if (Binder.getCallingUid() != owner.uid()
            || !owner.equals(host.platform.identity(owner.uid(), owner.packageName()))) {
            throw new SecurityException("Axeron Binder belongs to a different installed client");
        }
    }

    private static UnsupportedOperationException unsupported(String operation) {
        return new UnsupportedOperationException(operation + " is unavailable in Kizuku's Axeron endpoint");
    }

    private static String selinuxContext() {
        try {
            return (String) Class.forName("android.os.SELinux").getMethod("getContext").invoke(null);
        } catch (ReflectiveOperationException unavailable) {
            return "unknown";
        }
    }

    private static void close(ParcelFileDescriptor fd) {
        if (fd == null) return;
        try { fd.close(); } catch (IOException ignored) { /* The client may have closed already. */ }
    }

    private final class ClientCapability extends IAxeronService.Stub {
        private final ClientIdentity identity;
        private final Map<Integer, Attachment> attached = new ConcurrentHashMap<>();

        ClientCapability(ClientIdentity identity) {
            this.identity = identity;
        }

        @Override public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (data.dataSize() > Protocol.MAX_IPC_BYTES) throw new SecurityException("IPC size limit exceeded");
            if (code != INTERFACE_TRANSACTION) verifyOwner(identity);
            if (code == TRANSACTION_NEW_PROCESS) {
                if ((flags & IBinder.FLAG_ONEWAY) != 0) {
                    throw new IllegalArgumentException("Axeron launch requires a reply");
                }
                return dispatchProcess(data, reply);
            }
            return super.onTransact(code, data, reply, flags);
        }

        private boolean dispatchProcess(Parcel data, Parcel reply) {
            // The generated stub cannot close FDs it decoded before a later parcel read failed.
            // Keep the release AIDL field order here and own those FDs until the launch succeeds.
            data.enforceInterface(DESCRIPTOR);
            if (reply == null) throw new IllegalArgumentException("Axeron launch requires a reply");
            ParcelFileDescriptor stdin = null;
            ParcelFileDescriptor stdout = null;
            ParcelFileDescriptor stderr = null;
            ParcelFileDescriptor status = null;
            boolean launched = false;
            try {
                String[] argv = data.createStringArray();
                String[] env = data.createStringArray();
                String dir = data.readString();
                stdin = data.readTypedObject(ParcelFileDescriptor.CREATOR);
                stdout = data.readTypedObject(ParcelFileDescriptor.CREATOR);
                stderr = data.readTypedObject(ParcelFileDescriptor.CREATOR);
                boolean pty = data.readInt() != 0;
                long size = data.readLong();
                if (data.dataAvail() != 0) status = data.readTypedObject(ParcelFileDescriptor.CREATOR);
                if (data.dataAvail() != 0) throw new IllegalArgumentException("Unexpected Axeron launch data");
                IAxshProcess process = newAxshProcess(argv, env, dir, stdin, stdout, stderr, pty, size, status);
                launched = true;
                reply.writeNoException();
                reply.writeStrongBinder(process.asBinder());
                return true;
            } finally {
                if (!launched) {
                    close(stdin);
                    close(stdout);
                    close(stderr);
                    close(status);
                }
            }
        }

        @Override public void attachApplication(IShizukuApplication callback, Bundle args) throws RemoteException {
            if (callback == null || args == null || !identity.packageName().equals(args.getString(ATTACH_PACKAGE))) {
                throw new SecurityException("Axeron attach package does not match this capability");
            }
            int pid = Binder.getCallingPid();
            IBinder token = callback.asBinder();
            synchronized (attached) {
                Attachment previous = attached.get(pid);
                if (previous == null || !token.equals(previous.callback().asBinder())) {
                    if (previous == null && attached.size() >= Protocol.MAX_CLIENTS) {
                        throw new SecurityException("Axeron process limit reached");
                    }
                    IBinder.DeathRecipient death = () -> {
                        synchronized (attached) {
                            Attachment current = attached.get(pid);
                            if (current != null && token.equals(current.callback().asBinder())) attached.remove(pid);
                        }
                    };
                    token.linkToDeath(death, 0);
                    attached.put(pid, new Attachment(callback, death, new AtomicReference<>()));
                    if (previous != null) previous.callback().asBinder().unlinkToDeath(previous.death(), 0);
                }
            }
            Bundle reply = new Bundle();
            reply.putInt(CLIENT_UID, identity.uid());
            reply.putInt("shizuku:attach-reply-uid", Process.myUid());
            reply.putInt("shizuku:attach-reply-version", args.getInt(ATTACH_API_VERSION, -1) == -1 ? 12 : 13);
            reply.putInt("shizuku:attach-reply-patch-version", 5);
            reply.putString("shizuku:attach-reply-secontext", selinuxContext());
            reply.putBoolean("shizuku:attach-reply-permission-granted", checkSelfPermission());
            reply.putBoolean("shizuku:attach-reply-should-show-request-permission-rationale", false);
            long caller = Binder.clearCallingIdentity();
            try {
                callback.bindApplication(reply);
            } finally {
                Binder.restoreCallingIdentity(caller);
            }
        }

        @Override public void requestPermission(int requestCode) {
            Attachment attachment = attached.get(Binder.getCallingPid());
            if (attachment == null) throw new SecurityException("Axeron client must attach first");
            attachment.request().set(requestCode);
            if (checkSelfPermission()) {
                handler.post(() -> answer(attachment, true));
            } else {
                host.request(identity, Capability.AXERON);
            }
        }

        void answer(boolean allowed) {
            attached.values().forEach(attachment -> answer(attachment, allowed));
        }

        private void answer(Attachment attachment, boolean allowed) {
            Integer code = attachment.request().getAndSet(null);
            if (code == null) return;
            Bundle reply = new Bundle();
            reply.putBoolean(REPLY_ALLOWED, allowed && checkSelfPermission());
            reply.putBoolean("shizuku:request-permission-reply-is-onetime", false);
            try {
                attachment.callback().dispatchRequestPermissionResult(code, reply);
            } catch (RemoteException disconnected) {
                // The callback's death recipient removes the attachment.
            }
        }

        @Override public boolean checkSelfPermission() {
            return host.allowed(identity, Capability.AXERON, false);
        }

        @Override public boolean shouldShowRequestPermissionRationale() {
            return false;
        }

        @Override public ServerInfo getServerInfo() {
            return new ServerInfo("3.0.0", 30000, Process.myUid(), Process.myPid(), selinuxContext(),
                starting, checkSelfPermission(), "", false);
        }

        @Override public IAxshProcess newAxshProcess(String[] argv, String[] env, String dir,
                ParcelFileDescriptor stdin, ParcelFileDescriptor stdout, ParcelFileDescriptor stderr,
                boolean pty, long size, ParcelFileDescriptor status) {
            try {
                if (pty) throw unsupported("Axeron PTY");
                if (argv == null || argv.length == 0 || stdin == null || stdout == null || stderr == null) {
                    throw new IllegalArgumentException("Missing Axeron command or stream");
                }
                if (!host.allowed(identity, Capability.AXERON, false)) {
                    throw new SecurityException("Axeron capability is required");
                }
                IAxshProcess process = launcher.launch(identity, argv, env, dir, stdin, stdout, stderr, false, size, status);
                if (process == null) throw new IllegalStateException("Axeron launcher did not return a process");
                return new IAxshProcess.Stub() {
                    @Override public void setWindowSize(long size) { verifyOwner(identity); }
                    @Override public int waitFor() throws RemoteException {
                        verifyOwner(identity);
                        return process.waitFor();
                    }
                    @Override public void destroy() throws RemoteException {
                        verifyOwner(identity);
                        process.destroy();
                    }
                };
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IllegalStateException("Unable to start Axeron process", failure);
            }
        }

        // A null socket name tells an AxBroker client to use its ordinary Binder-backed broker.
        @Override public String getAxshSocketName() {
            if (!checkSelfPermission()) throw new SecurityException("Axeron capability is required");
            return null;
        }

        @Override public Environment getEnvironment(int envType) {
            if (!checkSelfPermission()) throw new SecurityException("Axeron capability is required");
            if (envType != 0) throw unsupported("Axeron environment type " + envType);
            // The upstream client reads TYPE_ENV before launch. An empty map asks it to use
            // Kizuku's server-side process defaults without disclosing the host environment.
            return new Environment(Map.of());
        }

        @Override public ParcelFileDescriptor openAxshChannel() { throw unsupported("Native axsh channel"); }
        @Override public IFileService getFileService() { throw unsupported("Axeron file service"); }
        @Override public ParcelableListSlice<PackageInfo> getPackages(int flags) { throw unsupported("Axeron package list"); }
        @Override public ParcelableListSlice<PluginInfo> getPlugins() { throw unsupported("Axeron plugin list"); }
        @Override public PluginInfo getPluginById(String id) { throw unsupported("Axeron plugin lookup"); }
        @Override public boolean isFirstInit(boolean markAsFirstInit) { throw unsupported("Axeron server initialization"); }
        @Override public IShizukuService getShizukuService() { throw unsupported("Axeron Shizuku service"); }
        @Override public void enableShizukuService(boolean enable) { throw unsupported("Axeron Shizuku controls"); }
        @Override public void setNewEnvironment(Environment env) { throw unsupported("Axeron environment mutation"); }
        @Override public void attachUserService(IBinder binder, Bundle options) { throw unsupported("Axeron user service attach"); }
        @Override public void dispatchPermissionConfirmationResult(int uid, int pid, int code, Bundle data) { throw unsupported("Axeron grant mutation"); }
        @Override public int getFlagsForUid(int uid, int mask) { throw unsupported("Axeron flags"); }
        @Override public void updateFlagsForUid(int uid, int mask, int value) { throw unsupported("Axeron flag mutation"); }
        @Override public int checkPermission(String permission) { throw unsupported("Axeron Android permission query"); }
        @Override public void setPreferSystemBinaries(boolean prefer) { throw unsupported("Axeron binary preference mutation"); }
        @Override public int[] getFlagsForUids(int[] uids, int mask) { throw unsupported("Axeron flags"); }
        @Override public void setWakeLock(boolean enabled) { throw unsupported("Axeron wake lock mutation"); }
        @Override public boolean isWakeLockHeld() { throw unsupported("Axeron wake lock query"); }
        @Override public IBinder getClientCapability(int uid) { throw unsupported("Axeron capability minting"); }
        @Override public void exit() { throw unsupported("Axeron host exit"); }
    }
}
