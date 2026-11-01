package io.github.stupidgiraffe.kizuku

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Parcel

/** Stock shell discovery protocol: delivery conveys no capability grant. */
class LegacyBinderRequestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "io.github.stupidgiraffe.kizuku.REQUEST_LEGACY") return
        val callback = intent.getBundleExtra("data")?.getBinder("binder") ?: return
        val service = CoreConnection.service ?: return
        val work = goAsync()
        Thread({
            val data=Parcel.obtain()
            try {
                // Native endpoint explicitly exposes the legacy facade only to its manager.
                data.writeStrongBinder(service.legacyBinder())
                data.writeString(context.applicationInfo.sourceDir)
                callback.transact(1,data,null,IBinder.FLAG_ONEWAY)
            } catch (_: Exception) { /* A dead callback is not an activation failure. */ }
            finally { data.recycle();work.finish() }
        },"legacy-discovery").start()
    }
}
