package io.github.stupidgiraffe.kizuku

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import io.github.stupidgiraffe.kizuku.api.AndroidIdentity
import io.github.stupidgiraffe.kizuku.core.ClientIdentity

/** Android calls this receiver after explicit device enrollment. */
class KizukuDeviceAdminReceiver : DeviceAdminReceiver()

/** Device Owner operations run under the manager UID using Android's policy APIs. */
internal class DeviceOwnerPolicies(private val context: Context) {
    private val admin = ComponentName(context, KizukuDeviceAdminReceiver::class.java)
    private val dpm get() = context.getSystemService(DevicePolicyManager::class.java)
    private val pm get() = context.packageManager
    private val applied get() = context.getSharedPreferences("device_owner_policies", Context.MODE_PRIVATE)

    val enrollmentCommand get() = "adb shell dpm set-device-owner ${admin.flattenToShortString()}"
    val supported get() = pm.hasSystemFeature(PackageManager.FEATURE_DEVICE_ADMIN) && dpm != null
    val enrolled get() = dpm?.let { it.isDeviceOwnerApp(context.packageName) && it.isAdminActive(admin) } == true

    data class App(val name: String, val packageName: String, val uid: Int)
    data class Target(val app: App, val identity: ClientIdentity)
    data class Status(val suspended: Boolean, val uninstallBlocked: Boolean)
    enum class Policy { SUSPEND, BLOCK_UNINSTALL }

    @Suppress("DEPRECATION")
    fun apps(): List<App> {
        checkOwner()
        return pm.getInstalledApplications(0).mapNotNull { info ->
            if (!eligible(info)) return@mapNotNull null
            val label = pm.getApplicationLabel(info).toString()
                .filterNot { Character.getType(it) == Character.FORMAT.toInt() || it.isISOControl() }
                .take(80).ifBlank { info.packageName }
            App(label, info.packageName, info.uid)
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    fun select(app: App): Target {
        checkOwner()
        val target = Target(app, AndroidIdentity.resolve(pm, app.uid, app.packageName))
        verify(target)
        return target
    }

    fun status(target: Target): Status {
        val policy = checkOwner()
        verify(target)
        return Status(
            suspended = policy.isPackageSuspended(admin, target.app.packageName),
            uninstallBlocked = policy.isUninstallBlocked(admin, target.app.packageName),
        )
    }

    @Synchronized
    fun apply(target: Target, policy: Policy, enabled: Boolean): Status {
        val manager = checkOwner()
        verify(target)
        val packageName = target.app.packageName
        // Remember a request before applying it so a process restart can still undo it.
        if (enabled) track(policy, packageName, true)
        setPolicy(manager, policy, packageName, enabled)
        if (!enabled) track(policy, packageName, false)
        val current = status(target)
        val actual = when (policy) {
            Policy.SUSPEND -> current.suspended
            Policy.BLOCK_UNINSTALL -> current.uninstallBlocked
        }
        check(actual == enabled) { "Android reported a different policy. Check whether another admin manages this app." }
        return current
    }

    @Suppress("DEPRECATION")
    @Synchronized
    fun removeOwner() {
        val manager = checkOwner()
        // Android's owner-removal API only guarantees best-effort cleanup.
        for (policy in Policy.entries) {
            for (packageName in applied.getStringSet(policy.name, emptySet()).orEmpty().toList()) {
                val installed = try {
                    pm.getApplicationInfo(packageName, 0)
                    true
                } catch (_: PackageManager.NameNotFoundException) {
                    false
                }
                if (installed) setPolicy(manager, policy, packageName, false)
                track(policy, packageName, false)
            }
        }
        manager.clearDeviceOwnerApp(context.packageName)
        check(!enrolled) { "Android still reports Kizuku as Device Owner." }
    }

    private fun setPolicy(manager: DevicePolicyManager, policy: Policy, packageName: String, enabled: Boolean) {
        when (policy) {
            Policy.SUSPEND -> check(manager.setPackagesSuspended(admin, arrayOf(packageName), enabled).isEmpty()) {
                "Android refused to change suspension for $packageName."
            }
            Policy.BLOCK_UNINSTALL -> manager.setUninstallBlocked(admin, packageName, enabled)
        }
    }

    private fun track(policy: Policy, packageName: String, enabled: Boolean) {
        val packages = applied.getStringSet(policy.name, emptySet()).orEmpty().toMutableSet()
        if (enabled) packages.add(packageName) else packages.remove(packageName)
        check(applied.edit().putStringSet(policy.name, packages).commit()) { "Unable to save Device Owner policy state." }
    }

    private fun checkOwner(): DevicePolicyManager {
        check(enrolled) { "Enroll Kizuku as Device Owner first." }
        return checkNotNull(dpm)
    }

    @Suppress("DEPRECATION")
    private fun verify(target: Target) {
        val app = target.app
        val current = pm.getApplicationInfo(app.packageName, 0)
        check(eligible(current) && current.uid == app.uid &&
            AndroidIdentity.resolve(pm, app.uid, app.packageName) == target.identity) {
            "App identity changed. Select the app again."
        }
    }

    private fun eligible(info: ApplicationInfo) =
        info.packageName != context.packageName && info.packageName != CompanionInstaller.PACKAGE &&
            info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
}
