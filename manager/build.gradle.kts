plugins { id("com.android.application"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "io.github.stupidgiraffe.kizuku"
    compileSdk = 37
    ndkVersion = "29.0.14206865"
    buildToolsVersion = "37.0.0"
    defaultConfig {
        applicationId = "io.github.stupidgiraffe.kizuku"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "0.1.0-preview.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true; prefab = true }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.31.6" } }
    buildTypes { release { isMinifyEnabled = false } }
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
    lint { abortOnError = true }
}
dependencies {
    implementation(project(":api"))
    implementation("org.bouncycastle:bcpkix-jdk18on:1.86")
    implementation("io.github.vvb2060.ndk:boringssl:20251124")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
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
    @get:InputFiles abstract val notices: ConfigurableFileCollection
    @get:InputFile abstract val apk: RegularFileProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @TaskAction fun bundle() {
        val destination = outputDirectory.file("legacy-compat.apk").get().asFile
        destination.parentFile.mkdirs()
        apk.get().asFile.copyTo(destination, overwrite = true)
        notices.files.forEach { it.copyTo(outputDirectory.file(it.name).get().asFile, overwrite = true) }
    }
}
androidComponents.onVariants { variant ->
    val kind = requireNotNull(variant.buildType)
    val title = kind.replaceFirstChar { it.uppercase() }
    val bundle = tasks.register<BundleCompanionTask>("bundle${title}Companion") {
        dependsOn(":compat:assemble${title}")
        notices.from(rootProject.file("LICENSE"), rootProject.file("NOTICE"), rootProject.file("BORINGSSL-LICENSE.txt"), rootProject.file("BOUNCYCASTLE-LICENSE.txt"))
        apk.set(project(":compat").layout.buildDirectory.file("outputs/apk/${kind}/compat-${kind}.apk"))
        outputDirectory.set(layout.buildDirectory.dir("generated/companion/${kind}"))
    }
    variant.sources.assets?.addGeneratedSourceDirectory(bundle) { it.outputDirectory }
}
