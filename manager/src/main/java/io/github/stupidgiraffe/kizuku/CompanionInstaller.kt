package io.github.stupidgiraffe.kizuku

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings

object CompanionInstaller {
    const val PACKAGE = "moe.shizuku.privileged.api"
    fun state(context: Context): String = try {
        val info = context.packageManager.getPackageInfo(PACKAGE, 0)
        if (context.packageManager.checkSignatures(context.packageName, PACKAGE) == PackageManager.SIGNATURE_MATCH) "Kizuku companion ${info.versionName} installed"
        else "Conflict: stock Shizuku or another companion is installed"
    } catch (_: PackageManager.NameNotFoundException) { "Not installed" }
    fun install(context: Context) {
        check(!state(context).startsWith("Conflict")) { "Remove the existing Shizuku package in Android Settings first. Existing permissions are not imported yet." }
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            return
        }
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setAppPackageName(PACKAGE) }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                context.assets.open("legacy-compat.apk").use { source -> session.openWrite("base.apk", 0, -1).use { target -> source.copyTo(target); session.fsync(target) } }
                val callback = Intent(context, InstallResultReceiver::class.java)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                session.commit(PendingIntent.getBroadcast(context, id, callback, flags).intentSender)
            }
        } catch (e: Exception) { installer.abandonSession(id); throw e }
    }
    fun remove(context: Context) {
        check(context.packageManager.checkSignatures(context.packageName, PACKAGE) == PackageManager.SIGNATURE_MATCH) { "Only the Kizuku companion can be removed here" }
        context.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$PACKAGE")))
    }
}

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1) == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION") val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            if (confirm != null) context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        CoreConnection.changed()
    }
}
