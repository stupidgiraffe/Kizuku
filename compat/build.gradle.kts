plugins { id("com.android.application") }
android {
    namespace = "io.github.stupidgiraffe.kizuku.compat"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    defaultConfig {
        applicationId = "moe.shizuku.privileged.api"
        minSdk = 26; targetSdk = 37; versionCode = rootProject.extra["kizukuVersionCode"] as Int; versionName = rootProject.extra["kizukuVersionName"] as String
    }
    signingConfigs {
        create("kizukuRelease") {
            storeFile = System.getenv("KIZUKU_KEYSTORE_PATH")?.let { file(it) }
            storePassword = System.getenv("KIZUKU_STORE_PASSWORD")
            keyAlias = "kizuku"
            keyPassword = System.getenv("KIZUKU_KEY_PASSWORD")
        }
    }
    buildTypes { getByName("release") { signingConfig = signingConfigs.getByName("kizukuRelease"); isMinifyEnabled = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
