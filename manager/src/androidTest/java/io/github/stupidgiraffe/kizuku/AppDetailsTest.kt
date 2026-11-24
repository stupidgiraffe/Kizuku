package io.github.stupidgiraffe.kizuku

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stupidgiraffe.kizuku.api.AndroidIdentity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDetailsTest {
    @Test fun realPermissionAppOpsAndForceStopRespectTheSelectedIdentity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val host = checkNotNull(CoreConnection.service)
        val pkg = "${app.packageName}.testclient"
        val permission = "android.permission.CAMERA"
        val info = app.packageManager.getApplicationInfo(pkg, 0)
        val identity = AndroidIdentity.resolve(app.packageManager, info.uid, pkg)
        val hostUid = host.status().getInt("uid")
        val snapshot = Bundle().apply {
            putString("identity", AndroidIdentity.encode(identity).toString())
            putString("package", pkg)
            putString("label", "Kizuku Probe")
            putInt(AuthorizationStore.HOST_UID, hostUid)
        }
        fun action(kind: String, name: String = "", value: String = ""): Bundle {
            val result = host.runtimePackageAction(snapshot, kind, name, value)
            assertEquals(result.getString("stderr"), 0, result.getInt("exit"))
            return result
        }
        try {
            action("permission", permission, "grant")
            assertEquals(PackageManager.PERMISSION_GRANTED, app.packageManager.checkPermission(permission, pkg))
            action("appops-set", "android:camera", "ignore")
            assertTrue(action("appops-get").getString("stdout", "").contains("CAMERA: ignore"))
            action("appops-set", "android:camera", "allow")
            val activity = instrumentation.startActivitySync(
                Intent(app, AppDetailsActivity::class.java).putExtra("snapshot", snapshot)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.runOnMainSync { activity.finish() }
            action("force-stop")
            assertTrue(app.packageManager.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_STOPPED != 0)
            val stale = Bundle(snapshot).apply { putInt(AuthorizationStore.HOST_UID, if (hostUid == 0) 2000 else 0) }
            assertThrows(SecurityException::class.java) { host.runtimePackageAction(stale, "force-stop", "", "") }
        } finally {
            action("appops-set", "android:camera", "default")
            action("permission", permission, "revoke")
        }
        assertEquals(PackageManager.PERMISSION_DENIED, app.packageManager.checkPermission(permission, pkg))
    }
}
