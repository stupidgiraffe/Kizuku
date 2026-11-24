plugins { id("com.android.library") }
android {
    namespace = "com.termux.view"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    lint { disable += setOf("ObsoleteSdkInt", "NewApi", "MissingTranslation") }
}
dependencies { implementation("androidx.annotation:annotation:1.9.1") }
