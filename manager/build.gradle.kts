plugins { id("com.android.application"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "io.github.stupidgiraffe.kizuku"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    defaultConfig {
        applicationId = "io.github.stupidgiraffe.kizuku"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    lint { abortOnError = true }
}
dependencies {
    implementation(project(":api"))
    implementation(project(":server"))
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.core:core-ktx:1.17.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}

abstract class BundleCompanionTask : DefaultTask() {
    @get:InputFile abstract val apk: RegularFileProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @TaskAction fun bundle() {
        val destination = outputDirectory.file("legacy-compat.apk").get().asFile
        destination.parentFile.mkdirs()
        apk.get().asFile.copyTo(destination, overwrite = true)
    }
}
androidComponents.onVariants(androidComponents.selector().withBuildType("debug")) { variant ->
    val bundle = tasks.register<BundleCompanionTask>("bundleDebugCompanion") {
        dependsOn(":compat:assembleDebug")
        apk.set(project(":compat").layout.buildDirectory.file("outputs/apk/debug/compat-debug.apk"))
        outputDirectory.set(layout.buildDirectory.dir("generated/companion/debug"))
    }
    variant.sources.assets?.addGeneratedSourceDirectory(bundle) { it.outputDirectory }
}
