package io.github.stupidgiraffe.kizuku

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** Runs against installed Preview 4, downloading the candidate over emulator-local HTTPS. */
@RunWith(AndroidJUnit4::class)
class UpdateInstallTest {
    @Test fun downloadVerifyAndApproveAndroidUpdate() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val version = checkNotNull(args.getString("candidate_version"))
        val sha = checkNotNull(args.getString("candidate_sha256"))
        val size = checkNotNull(args.getString("candidate_size")).toLong()
        val original = HttpsURLConnection.getDefaultSSLSocketFactory()
        val tls = SSLContext.getInstance("TLS")
        // This certificate override exists only in signed instrumentation, on a disposable emulator.
        // Hostname verification still requires the fixture's localhost SAN.
        tls.init(null, arrayOf(object : X509TrustManager {
            override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
            override fun checkClientTrusted(chain: Array<X509Certificate>, auth: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, auth: String) = Unit
        }), SecureRandom())
        try {
            HttpsURLConnection.setDefaultSSLSocketFactory(tls.socketFactory)
            AppUpdates.requestDownload(context, KizukuRelease(version, "https://localhost:38443/candidate.apk", sha, size))
            val deadline = SystemClock.elapsedRealtime() + 60_000
            while (AppUpdates.state.value.downloaded != version && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(200)
            }
            assertEquals(AppUpdates.state.value.message, version, AppUpdates.state.value.downloaded)
            instrumentation.sendStatus(0, Bundle().apply { putString("kizuku_update_download_verified", sha) })
            val open = AppUpdates.installIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(open)
            val approveBy = SystemClock.elapsedRealtime() + 20_000
            while (SystemClock.elapsedRealtime() < approveBy) {
                val nodes = nodes(instrumentation)
                val update = nodes.firstOrNull {
                    it.packageName?.toString() != context.packageName &&
                        it.text?.toString() == "Update" && it.isVisibleToUser && it.isEnabled
                }
                if (update != null) {
                    var clickable: AccessibilityNodeInfo? = update
                    while (clickable != null && !clickable.isClickable) clickable = clickable.parent
                    assertTrue("Android's real update button was clicked",
                        clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
                    instrumentation.sendStatus(0, Bundle().apply { putString("kizuku_update_android_approved", "true") })
                    // Package replacement ends this process. The shell verifies the new installation.
                    SystemClock.sleep(30_000)
                    return
                }
                SystemClock.sleep(200)
            }
            throw AssertionError("Android did not present an update confirmation")
        } finally {
            HttpsURLConnection.setDefaultSSLSocketFactory(original)
        }
    }

    private fun nodes(instrumentation: Instrumentation): List<AccessibilityNodeInfo> = buildList {
        fun visit(node: AccessibilityNodeInfo) {
            add(node)
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
        }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }
}
