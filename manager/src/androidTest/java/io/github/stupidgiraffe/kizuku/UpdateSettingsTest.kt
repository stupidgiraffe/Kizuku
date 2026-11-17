package io.github.stupidgiraffe.kizuku

import android.app.job.JobScheduler
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class UpdateSettingsTest {
    @Test fun updateDiscoverySchedulingAndApkIdentity() {
        val releases = JSONArray()
            .put(release("0.2.1", draft = true))
            .put(release("0.2.0"))
            .put(release("0.2.0-preview.10"))
            .put(release("0.3.0", foreign = true))
            .put(release("0.4.0", digest = ""))
        assertEquals("0.2.0", AppUpdates.selectRelease(releases.toString(), "0.2.0-preview.2")?.version)
        assertNull(AppUpdates.selectRelease(releases.toString(), "0.2.0"))
        val previews = JSONArray().put(release("0.2.0-preview.9")).put(release("0.2.0-preview.10"))
        assertEquals("0.2.0-preview.10", AppUpdates.selectRelease(previews.toString(), "0.2.0-preview.3")?.version)

        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val installedApk = File(app.applicationInfo.sourceDir)
        val oldVersionCode = BuildConfig.VERSION_CODE - 1
        assertNotNull("Accepts a newer APK with the manager certificate", AppUpdates.archivedVersion(app, installedApk, oldVersionCode))
        assertNull("Rejects the installed version", AppUpdates.archivedVersion(app, installedApk))
        val probe = app.packageManager.getApplicationInfo("${app.packageName}.testclient", 0)
        assertNull("Rejects foreign packages even with the same signer", AppUpdates.archivedVersion(app, File(probe.sourceDir), oldVersionCode))

        val scheduler = app.getSystemService(JobScheduler::class.java)
        assertFalse(AppUpdates.automatic(app))
        assertFalse(AppUpdates.autoDownload(app))
        try {
            AppUpdates.setAutomatic(app, true)
            AppUpdates.setAutoDownload(app, true)
            val job = scheduler.allPendingJobs.single { it.service.className == UpdateCheckJob::class.java.name }
            assertTrue(AppUpdates.automatic(app))
            assertTrue(AppUpdates.autoDownload(app))
            assertTrue(job.isPersisted)
            assertEquals(24 * 60 * 60 * 1000L, job.intervalMillis)
        } finally {
            AppUpdates.setAutoDownload(app, false)
            AppUpdates.setAutomatic(app, false)
        }
        assertFalse(scheduler.allPendingJobs.any { it.service.className == UpdateCheckJob::class.java.name })
    }

    private fun release(version: String, draft: Boolean = false, foreign: Boolean = false, digest: String = "sha256:" + "a".repeat(64)): JSONObject {
        val name = "Kizuku-v$version.apk"
        val project = if (foreign) "https://github.com/example/other" else AppUpdates.PROJECT_URL
        val asset = JSONObject().put("name", name).put("size", 12345).put("digest", digest)
            .put("browser_download_url", "$project/releases/download/v$version/$name")
        return JSONObject().put("draft", draft).put("tag_name", "v$version").put("assets", JSONArray().put(asset))
    }
}
