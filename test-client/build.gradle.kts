plugins { id("com.android.application") }
android {
    namespace = "io.github.stupidgiraffe.kizuku.testclient"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    defaultConfig {
        applicationId = "io.github.stupidgiraffe.kizuku.testclient"
        minSdk = 26; targetSdk = 37; versionCode = 2; versionName = "0.1.0-preview.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    testBuildType = providers.gradleProperty("testBuildType").orElse("debug").get()
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation(project(":api"))
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
}
