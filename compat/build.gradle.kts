plugins { id("com.android.application") }
android {
    namespace = "io.github.stupidgiraffe.kizuku.compat"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    defaultConfig {
        applicationId = "moe.shizuku.privileged.api"
        minSdk = 26; targetSdk = 37; versionCode = 1; versionName = "0.1.0"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
