plugins { id("com.android.library"); id("dev.rikka.tools.refine") }
android {
    namespace = "io.github.stupidgiraffe.kizuku.server"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation(project(":api"))
    implementation("dev.rikka.hidden:compat:4.4.0")
    compileOnly("dev.rikka.hidden:stub:4.4.0")
    implementation("androidx.annotation:annotation:1.9.1")
}
