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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object CompanionInstaller {
    const val PACKAGE = "moe.shizuku.privileged.api"
    enum class Kind(val packageName: String, val asset: String, val preferences: String, val label: String) {
        SHIZUKU(PACKAGE, "legacy-compat.apk", "companion_install", "Shizuku"),
        DHIZUKU(DhizukuAuthorization.COMPANION, "dhizuku-compat.apk", "dhizuku_compat_install", "Dhizuku"),
    }
    private const val KIND = "kizuku_companion_kind"
    private const val NO_SESSION = -1
    private const val SESSION = "session"
    private const val NOTICE = "notice"
    private const val CALLBACK_SESSION = "kizuku_install_session"
    private val revision = MutableStateFlow(0)
    val changes = revision.asStateFlow()

    data class State(val text: String, val installed: Boolean = false, val conflict: Boolean = false)
    fun packageState(context: Context, kind: Kind = Kind.SHIZUKU): State = try {
        val info = context.packageManager.getPackageInfo(kind.packageName, 0)
        if (context.packageManager.checkSignatures(context.packageName, kind.packageName) == PackageManager.SIGNATURE_MATCH) {
            State("Kizuku companion ${info.versionName} installed", installed = true)
        } else State("Another ${kind.label} app owns the compatibility package.", conflict = true)
    } catch (_: PackageManager.NameNotFoundException) { State("Companion is not installed") }

    private fun store(context: Context, kind: Kind) = context.getSharedPreferences(kind.preferences, Context.MODE_PRIVATE)
    fun notice(context: Context, kind: Kind = Kind.SHIZUKU) = store(context, kind).getString(NOTICE, null)
    fun installing(context: Context, kind: Kind = Kind.SHIZUKU) = store(context, kind).getInt(SESSION, NO_SESSION) != NO_SESSION
    private fun changed() { revision.value += 1; CoreConnection.changed() }

    fun reset(context: Context, kind: Kind = Kind.SHIZUKU) {
        val id = store(context, kind).getInt(SESSION, NO_SESSION)
        if (id != NO_SESSION) runCatching { context.packageManager.packageInstaller.abandonSession(id) }
        complete(context, kind, "Installation session cleared.")
    }

    fun install(context: Context, kind: Kind = Kind.SHIZUKU) {
        // Android may drop the session before it delivers the final broadcast.
        // Keep the recorded ID until that callback or an explicit reset.
        check(!installing(context, kind)) { "An installation is already open." }
        check(!packageState(context, kind).conflict) { "The ${kind.label} compatibility package is already in use." }
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            return
        }
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(kind.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            check(store(context, kind).edit().putInt(SESSION, id).remove(NOTICE).commit()) { "Cannot save the installation session." }
            changed()
            installer.openSession(id).use { session ->
                context.assets.open(kind.asset).use { source ->
                    session.openWrite("base.apk", 0, -1).use { target ->
                        source.copyTo(target)
                        session.fsync(target)
                    }
                }
                val callback = Intent(context, InstallResultReceiver::class.java).putExtra(CALLBACK_SESSION, id).putExtra(KIND, kind.name)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                session.commit(PendingIntent.getBroadcast(context, id, callback, flags).intentSender)
            }
        } catch (failure: Exception) {
            runCatching { installer.abandonSession(id) }
            complete(context, kind, failure.message ?: "Cannot start companion installation.")
        }
    }

    fun removalIntent(context: Context, kind: Kind = Kind.SHIZUKU): Intent {
        check(packageState(context, kind).installed) { "Only the Kizuku companion can be removed here." }
        return Intent(Intent.ACTION_DELETE, Uri.parse("package:${kind.packageName}")).putExtra(Intent.EXTRA_RETURN_RESULT, true)
    }

    fun removalResult(context: Context, result: Int, data: Intent?, kind: Kind = Kind.SHIZUKU): String {
        val current = packageState(context, kind)
        changed()
        if (!current.installed && !current.conflict) return "Companion removed."
        val reason = data?.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        if (reason.isNullOrBlank() && (result == android.app.Activity.RESULT_CANCELED || (result == android.app.Activity.RESULT_FIRST_USER && data == null))) {
            return "Removal was canceled or blocked by Android."
        }
        return reason?.take(240) ?: when (result) {
            android.app.Activity.RESULT_OK -> "Android returned success, but the compatibility package is still installed."
            else -> "Android did not remove the companion (result $result)."
        }
    }

    fun handleInstallResult(context: Context, intent: Intent) {
        val kind = intent.getStringExtra(KIND)?.let { Kind.valueOf(it) } ?: Kind.SHIZUKU
        val active = store(context, kind).getInt(SESSION, NO_SESSION)
        if (active == NO_SESSION || intent.getIntExtra(CALLBACK_SESSION, NO_SESSION) != active) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION") val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            try {
                checkNotNull(confirmation) { "Android did not provide the installation confirmation." }
                context.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (failure: Exception) {
                runCatching { context.packageManager.packageInstaller.abandonSession(active) }
                complete(context, kind, failure.message ?: "Could not open Android’s confirmation.")
            }
            return
        }
        complete(context, kind, when (status) {
            PackageInstaller.STATUS_SUCCESS -> if (packageState(context, kind).installed) "Companion installed."
                else "Android returned success, but the signed companion is unavailable."
            PackageInstaller.STATUS_FAILURE_ABORTED -> "Installation canceled."
            else -> intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.take(240)
                ?: "Android could not install the companion (status $status)."
        })
    }

    private fun complete(context: Context, kind: Kind, notice: String) {
        store(context, kind).edit().remove(SESSION).putString(NOTICE, notice).apply()
        changed()
    }
}

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = CompanionInstaller.handleInstallResult(context, intent)
}
