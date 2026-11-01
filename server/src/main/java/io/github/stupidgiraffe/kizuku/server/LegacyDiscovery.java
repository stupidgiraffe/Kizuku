package io.github.stupidgiraffe.kizuku.server;

import android.app.ActivityManagerHidden;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import java.util.HashSet;
import java.util.Set;
import rikka.hidden.compat.ActivityManagerApis;
import rikka.hidden.compat.adapter.ProcessObserverAdapter;
import rikka.hidden.compat.adapter.UidObserverAdapter;

/** Existing stock SDK apps need no Kizuku-specific call or SDK modification. */
final class LegacyDiscovery {
    private final Set<Integer> delivered = new HashSet<>();
    private final KizukuService host;
    private final Handler handler;
    LegacyDiscovery(KizukuService host, Handler handler) { this.host=host;this.handler=handler; }
    private void active(int uid) {
        if (uid < 10000 || uid / 100000 != 0) return;
        handler.post(() -> {
            if (delivered.contains(uid)) return;
            try {
                String[] names=host.platform.packagesForUid(uid);
                if(names==null) return;
                for(String name:names) {
                    PackageInfo info=host.platform.packageInfo(name,0,PackageManager.GET_PROVIDERS | PackageManager.GET_PERMISSIONS);
                    boolean requests=false;
                    if(info.requestedPermissions!=null) for(String permission:info.requestedPermissions) if("moe.shizuku.manager.permission.API_V23".equals(permission)) requests=true;
                    if(!requests || info.providers==null) continue;
                    for(android.content.pm.ProviderInfo provider:info.providers) {
                        if((name+".shizuku").equals(provider.authority) && provider.exported) {
                            host.legacy.deliver(name); delivered.add(uid); break;
                        }
                    }
                }
            } catch(Exception failure) { android.util.Log.w("KizukuServer","Legacy client not ready; retry on next foreground event"); }
        });
    }
    void start() throws Exception {
        ActivityManagerApis.registerProcessObserver(new ProcessObserverAdapter() {
            @Override public void onForegroundActivitiesChanged(int pid,int uid,boolean foreground) { if(foreground) active(uid); }
            @Override public void onProcessStateChanged(int pid,int uid,int state) { active(uid); }
            @Override public void onProcessDied(int pid,int uid) { handler.post(() -> delivered.remove(uid)); }
        });
        ActivityManagerApis.registerUidObserver(new UidObserverAdapter() {
            @Override public void onUidActive(int uid) { active(uid); }
            @Override public void onUidCachedChanged(int uid,boolean cached) { if(!cached) active(uid); }
            @Override public void onUidGone(int uid,boolean disabled) { handler.post(() -> delivered.remove(uid)); }
        }, ActivityManagerHidden.UID_OBSERVER_ACTIVE | ActivityManagerHidden.UID_OBSERVER_GONE | ActivityManagerHidden.UID_OBSERVER_CACHED, ActivityManagerHidden.PROCESS_STATE_UNKNOWN, null);
    }
}
