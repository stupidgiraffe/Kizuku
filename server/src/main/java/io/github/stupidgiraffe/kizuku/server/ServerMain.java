package io.github.stupidgiraffe.kizuku.server;

import android.content.pm.PackageInfo;
import android.net.LocalServerSocket;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import java.io.File;
import io.github.stupidgiraffe.kizuku.api.Protocol;
import io.github.stupidgiraffe.kizuku.core.RecoveryPolicy;

/** The same privileged entry point for root, PC ADB and future Wireless ADB. */
public final class ServerMain {
    private static LocalServerSocket ownership;
    // Root the connection as well as its Binder/death recipient for the host lifetime.
    private static ManagerConnection managerConnection;
    public static void main(String[] args) {
        try {
            if (Process.myUid() != 0 && Process.myUid() != 2000) throw new SecurityException("Root or shell UID required");
            if (args.length != 1) throw new IllegalArgumentException("Expected installed manager APK path");
            // No broad process-name kill: a bound socket prevents duplicate supervised hosts.
            ownership = new LocalServerSocket(Protocol.AUTHORITY + ".v1");
            Looper.prepareMainLooper(); Handler handler = new Handler(Looper.getMainLooper());
            PlatformBridge platform = new PlatformBridge();
            PackageInfo manager = platform.packageInfo(Protocol.MANAGER, 0);
            File installed = new File(manager.applicationInfo.sourceDir).getCanonicalFile();
            if (!installed.equals(new File(args[0]).getCanonicalFile()) || !installed.isFile())
                throw new SecurityException("Launch APK is not the installed manager");
            KizukuService service = new KizukuService(platform, handler);
            new LegacyDiscovery(service,handler).start();
            service.startRuntime();
            handler.postDelayed(new Runnable() {
                @Override public void run() {
                    if (!service.managerValid()) { service.close(); System.exit(0); return; }
                    service.sweepResources();
                    handler.postDelayed(this, 15_000);
                }
            }, 15_000);
            managerConnection = new ManagerConnection(service, platform, handler);
            managerConnection.run();
            Log.i("KizukuServer", "Privileged host started: uid=" + Process.myUid());
            Looper.loop();
        } catch (Exception failure) {
            Log.e("KizukuServer", "Startup failed", failure); System.exit(1);
        }
    }
    private static final class ManagerConnection implements Runnable {
        private final KizukuService service;
        private final PlatformBridge platform;
        private final Handler handler;
        private IBinder lifetime;
        private IBinder.DeathRecipient recipient;
        private int attempts;
        ManagerConnection(KizukuService service, PlatformBridge platform, Handler handler) {
            this.service=service; this.platform=platform; this.handler=handler;
        }
        @Override public void run() {
            if (!service.managerValid()) { service.close(); System.exit(0); return; }
            try {
                Bundle input=new Bundle(); input.putBinder(Protocol.BINDER,service);
                Bundle reply=platform.managerCall("attach",input);
                IBinder token=reply==null ? null : reply.getBinder("manager_lifetime");
                if (token==null) throw new IllegalStateException("Manager did not accept service");
                IBinder.DeathRecipient callback=() -> handler.post(() -> {
                    if (lifetime != token) return;
                    lifetime=null; recipient=null; attempts=0;
                    Log.i("KizukuServer","Manager died; reconnecting existing host");
                    handler.removeCallbacks(this); handler.postDelayed(this,2000);
                });
                token.linkToDeath(callback,0);
                if (lifetime!=null && recipient!=null) lifetime.unlinkToDeath(recipient,0);
                lifetime=token; recipient=callback; attempts=0;
                Log.i("KizukuServer","Manager connection established");
            } catch (Exception unavailable) {
                Log.w("KizukuServer","Manager connection unavailable; bounded retry",unavailable);
                long delay=RecoveryPolicy.retryDelay(true,false,true,attempts++);
                if (delay>=0) handler.postDelayed(this,delay);
                else Log.e("KizukuServer","Manager reconnect attempts exhausted");
            }
        }
    }

}
