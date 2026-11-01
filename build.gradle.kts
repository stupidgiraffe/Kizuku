plugins {
    id("com.android.application") version "9.4.1" apply false
    id("com.android.library") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    id("dev.rikka.tools.refine") version "4.4.0" apply false
}

val kizukuRelease = java.util.Properties().apply { rootProject.file("release.properties").inputStream().use { load(it) } }
extra["kizukuVersionCode"] = kizukuRelease.getProperty("versionCode").toInt()
extra["kizukuVersionName"] = kizukuRelease.getProperty("versionName")
