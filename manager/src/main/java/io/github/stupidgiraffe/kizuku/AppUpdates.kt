package io.github.stupidgiraffe.kizuku

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.InputStream
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.coroutineContext

internal data class KizukuRelease(val version: String, val url: String, val sha256: String, val size: Long)

internal object AppUpdates {
    private const val REPOSITORY = "stupidgiraffe/Kizuku"
    const val PROJECT_URL = "https://github.com/$REPOSITORY"
    const val SUPPORT_URL = "https://buymeacoffee.com/stupidgiraffe"
    const val OPEN_SETTINGS = "io.github.stupidgiraffe.kizuku.OPEN_UPDATE_SETTINGS"
    private const val JOB_ID = 6841
    private const val UPDATE_CHANNEL = "app_updates"
    private const val UPDATE_NOTIFICATION = 6841
    private const val CHECKS = "auto_check"
    private const val DOWNLOADS = "auto_download"
    private const val MAX_APK_SIZE = 128L * 1024 * 1024
    private val versionPattern = Regex("""(\d{1,6})\.(\d{1,6})\.(\d{1,6})(?:-(preview|rc)\.(\d{1,6}))?""")
    private val digestPattern = Regex("[0-9a-f]{64}")
    private val requests = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()

    data class State(
        val busy: Boolean = false,
        val release: KizukuRelease? = null,
        val downloaded: String? = null,
        val message: String? = null,
    )

    private fun preferences(context: Context) = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    fun automatic(context: Context) = preferences(context).getBoolean(CHECKS, false)
    fun autoDownload(context: Context) = preferences(context).getBoolean(DOWNLOADS, false)

    fun setAutomatic(context: Context, enabled: Boolean) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (enabled) {
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, UpdateCheckJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(24 * 60 * 60 * 1000L)
                .setPersisted(true)
                .build()
            check(scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS) { "Android could not schedule update checks." }
        } else scheduler.cancel(JOB_ID)
        preferences(context).edit().putBoolean(CHECKS, enabled).apply()
    }

    fun setAutoDownload(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(DOWNLOADS, enabled).apply()
    }

    suspend fun restore(context: Context) = withContext(Dispatchers.IO) {
        lock.withLock {
            val staged = apk(context)
            val downloaded = archivedVersion(context, staged)
            mutable.value = mutable.value.copy(downloaded = downloaded)
            if (downloaded == null && staged.delete()) {
                context.getSystemService(NotificationManager::class.java).cancel(UPDATE_NOTIFICATION)
            }
        }
    }

    fun requestCheck(context: Context) {
        requests.launch { check(context.applicationContext) }
    }

    fun requestDownload(context: Context, release: KizukuRelease) {
        requests.launch { runUpdateAction { save(context.applicationContext, release) } }
    }

    fun openedFromNotification(context: Context) {
        val app = context.applicationContext
        requests.launch {
            restore(app)
            if (state.value.downloaded == null && state.value.release == null) check(app)
        }
    }

    suspend fun check(context: Context, background: Boolean = false) = runUpdateAction {
        val current = mutable.value.copy(downloaded = archivedVersion(context))
        val newest = open("https://api.github.com/repos/$REPOSITORY/releases?per_page=30") {
            selectRelease(it.bufferedReader().readText(), BuildConfig.VERSION_NAME)
        }
        mutable.value = current.copy(release = newest, message = if (newest == null) "Kizuku is up to date." else null)
        if (newest != null && willAutoDownload(context) && newest.version != current.downloaded) {
            save(context, newest)
        } else if (background && newest != null && newest.version != current.downloaded) {
            notify(context, "Kizuku ${newest.version} is available")
        }
    }

    private fun willAutoDownload(context: Context) = automatic(context) && autoDownload(context)

    private suspend fun runUpdateAction(action: suspend () -> Unit) = withContext(Dispatchers.IO) {
        lock.withLock {
            mutable.value = mutable.value.copy(busy = true, message = null)
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutable.value = mutable.value.copy(message = failure.message?.take(160) ?: "Update failed. Try again.")
            } finally {
                mutable.value = mutable.value.copy(busy = false)
            }
        }
    }

    private suspend fun save(context: Context, release: KizukuRelease) {
        val target = apk(context)
        check(target.parentFile?.let { it.isDirectory || it.mkdirs() } == true) { "Could not create the update directory." }
        val temporary = File(target.parentFile, "download.part.apk")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val job = coroutineContext
            open(release.url) { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var size = 0L
                    while (true) {
                        job.ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        size += read
                        check(size <= release.size) { "The APK exceeded its published size." }
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                    }
                    check(size == release.size) { "The APK download was incomplete." }
                }
            }
            job.ensureActive()
            val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            check(hash == release.sha256) { "The APK did not match the published SHA-256." }
            check(archivedVersion(context, temporary) == release.version) { "The APK did not match this installation’s package, version, or signing certificate." }
            check(temporary.renameTo(target)) { "Could not save the verified APK." }
            mutable.value = mutable.value.copy(downloaded = release.version, message = null)
            notify(context, "Kizuku ${release.version} is ready to install")
        } finally {
            temporary.delete()
        }
    }

    fun installIntent(context: Context): Intent {
        if (!context.packageManager.canRequestPackageInstalls()) {
            return Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.fromParts("package", context.packageName, null))
        }
        val apk = apk(context)
        check(apk.isFile && state.value.downloaded != null) { "Download the update first." }
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(FileProvider.getUriForFile(context, "${context.packageName}.updates", apk), "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun apk(context: Context) = File(context.filesDir, "updates/Kizuku.apk")

    @Suppress("DEPRECATION")
    internal fun archivedVersion(context: Context, file: File = apk(context), installedCode: Int = BuildConfig.VERSION_CODE): String? {
        if (!file.isFile) return null
        val packages = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = packages.getPackageArchiveInfo(file.path, flags) ?: return null
        if (archive.packageName != context.packageName || archive.versionCode <= installedCode) return null
        val installed = packages.getPackageInfo(context.packageName, flags)
        fun signers(info: PackageInfo) = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners.orEmpty().toSet()
        } else info.signatures.orEmpty().toSet()
        val trusted = signers(installed)
        return archive.versionName.takeIf { trusted.isNotEmpty() && trusted == signers(archive) }
    }

    private fun notify(context: Context, title: String) {
        val notifications = context.getSystemService(NotificationManager::class.java)
        if (!notifications.areNotificationsEnabled()) return
        notifications.createNotificationChannel(NotificationChannel(UPDATE_CHANNEL, "Kizuku updates", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = Intent(context, MainActivity::class.java).setAction(OPEN_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val action = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, UPDATE_CHANNEL)
            .setSmallIcon(R.mipmap.ic_kizuku)
            .setContentTitle(title)
            .setContentIntent(action)
            .setAutoCancel(true)
            .build()
        if (Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) notifications.notify(UPDATE_NOTIFICATION, notification)
    }

    private inline fun <T> open(url: String, read: (InputStream) -> T): T {
        val connection = URL(url).openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = 12_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("User-Agent", "Kizuku/${BuildConfig.VERSION_NAME}")
            if (url.startsWith("https://api.github.com/")) {
                connection.setRequestProperty("Accept", "application/vnd.github+json")
            }
            check(connection.responseCode == 200) { "GitHub returned HTTP ${connection.responseCode}. Try again later." }
            return connection.inputStream.use(read)
        } finally {
            connection.disconnect()
        }
    }

    internal fun selectRelease(response: String, installedVersion: String): KizukuRelease? {
        val installed = versionRank(installedVersion) ?: return null
        val releases = JSONArray(response)
        var selected: KizukuRelease? = null
        var newest = installed
        for (index in 0 until releases.length()) {
            val release = releases.getJSONObject(index)
            val tag = release.optString("tag_name")
            if (release.optBoolean("draft") || !tag.startsWith("v")) continue
            val version = tag.removePrefix("v")
            val rank = versionRank(version) ?: continue
            if (rank <= newest) continue
            val assets = release.optJSONArray("assets") ?: continue
            val name = "Kizuku-$tag.apk"
            val url = "$PROJECT_URL/releases/download/$tag/$name"
            for (assetIndex in 0 until assets.length()) {
                val asset = assets.getJSONObject(assetIndex)
                val size = asset.optLong("size")
                val hash = asset.optString("digest").removePrefix("sha256:")
                if (asset.optString("name") == name && asset.optString("browser_download_url") == url &&
                    asset.optString("digest").startsWith("sha256:") && digestPattern.matches(hash) && size in 1..MAX_APK_SIZE
                ) {
                    selected = KizukuRelease(version, url, hash, size)
                    newest = rank
                    break
                }
            }
        }
        return selected
    }

    private fun versionRank(version: String): List<Int>? {
        val fields = versionPattern.matchEntire(version)?.groupValues ?: return null
        val channel = when (fields[4]) { "preview" -> 0; "rc" -> 1; else -> 2 }
        return listOf(fields[1].toInt(), fields[2].toInt(), fields[3].toInt(), channel, fields[5].toIntOrNull() ?: 0)
    }

    private operator fun List<Int>.compareTo(other: List<Int>): Int {
        for (index in indices) {
            if (this[index] != other[index]) return this[index].compareTo(other[index])
        }
        return 0
    }
}

class UpdateCheckJob : JobService() {
    private var check: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        if (!AppUpdates.automatic(this)) return false
        check = CoroutineScope(Dispatchers.Main).launch {
            AppUpdates.check(applicationContext, background = true)
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        check?.cancel()
        check = null
        return AppUpdates.automatic(this)
    }
}
