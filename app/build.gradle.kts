/** Writes droidsystem/index.txt: every file bundled in the DroidSystem image, so the app never relies on AssetManager.list(). */
abstract class DroidSystemIndexTask : DefaultTask() {
    @get:InputDirectory abstract val source: DirectoryProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction fun run() {
        val src = source.get().asFile
        val files = src.walkTopDown().filter { it.isFile }.map { it.relativeTo(src).invariantSeparatorsPath }.sorted().toList()
        val out = outputDir.get().asFile.resolve("droidsystem")
        out.mkdirs()
        out.resolve("index.txt").writeText(files.joinToString("\n"))
    }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "org.kdx.shell"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.kdx.shell"
        minSdk = 26
        // Android 9 target, like Termux: apps targeting 10+ may not execute programs they download,
        // which DroidSystem needs to run Debian's Linux programs (via proot). Install by sideloading.
        targetSdk = 28
        versionCode = 46
        versionName = "3.9.2"
        // proot is bundled for these CPUs (see app/src/main/jniLibs)
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Signed with the debug key so the release APK installs out of the box.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("robolectric.graphicsMode", "NATIVE")
            it.maxHeapSize = "2g"
            it.environment("LC_ALL", "C.UTF-8") // Debian packages contain UTF-8 file names
            System.getenv("KDX_EXTRA_CA")?.let { ca -> it.environment("KDX_EXTRA_CA", ca) }
            System.getenv("KDX_TEST_PROXY")?.let { p -> it.environment("KDX_TEST_PROXY", p) }
            // Opt-in Debian/proot integration test: ./gradlew testDebugUnitTest -Pkdx.integration=/some/dir
            project.findProperty("kdx.integration")?.let { dir -> it.systemProperty("kdx.integration", dir.toString()); it.outputs.upToDateWhen { false } }
            project.findProperty("kdx.flatpak")?.let { id -> it.systemProperty("kdx.flatpak", id.toString()) }
            project.findProperty("kdx.debug")?.let { d -> it.systemProperty("kdx.debug", d.toString()) }
            project.findProperty("kdx.wait")?.let { d -> it.systemProperty("kdx.wait", d.toString()) }
            project.findProperty("kdx.fpsSize")?.let { d -> it.systemProperty("kdx.fpsSize", d.toString()) }
            project.findProperty("kdx.fpsApp")?.let { d -> it.systemProperty("kdx.fpsApp", d.toString()) }
            project.findProperty("kdx.vncPort")?.let { d -> it.systemProperty("kdx.vncPort", d.toString()); it.outputs.upToDateWhen { false } }
            project.findProperty("kdx.crashExe")?.let { d -> it.systemProperty("kdx.crashExe", d.toString()) }
            project.findProperty("kdx.windFresh")?.let { d -> it.systemProperty("kdx.windFresh", d.toString()) }
            project.findProperty("kdx.arch")?.let { d -> it.systemProperty("kdx.arch", d.toString()) }
            project.findProperty("kdx.distro")?.let { d -> it.systemProperty("kdx.distro", d.toString()) }
            project.findProperty("kdx.pack")?.let { d -> it.systemProperty("kdx.pack", d.toString()); it.outputs.upToDateWhen { false } }
            project.findProperty("kdx.wind")?.let { d -> it.systemProperty("kdx.wind", d.toString()); it.outputs.upToDateWhen { false } }
            project.findProperty("kdx.proot")?.let { d -> it.systemProperty("kdx.proot", d.toString()) }
            project.findProperty("kdx.plasma")?.let { d -> it.systemProperty("kdx.plasma", d.toString()); it.outputs.upToDateWhen { false } }
            project.findProperty("kdx.tunRelay")?.let { d -> it.systemProperty("kdx.tunRelay", d.toString()); it.outputs.upToDateWhen { false } }
        }
    }
    packaging {
        // proot ships as lib*.so so Android installs it executable; keep the files on disk, unmodified
        jniLibs { useLegacyPackaging = true; keepDebugSymbols += "**/*.so" }
    }
    lint {
        disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

val droidIndex = tasks.register<DroidSystemIndexTask>("droidSystemIndex") {
    source.set(layout.projectDirectory.dir("src/main/assets/droidsystem"))
}
androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(droidIndex, DroidSystemIndexTask::outputDir)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    // Archive formats for DroidSystem (.deb = ar + tar.{gz,xz,bz2})
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")
    // zstd: Ubuntu's packages are compressed with it (Android version with its native libraries; the desktop jar for tests)
    implementation("com.github.luben:zstd-jni:1.5.7-4@aar")
    testImplementation("com.github.luben:zstd-jni:1.5.7-4")
    // Android apps in floating windows: Android's call for that (setLaunchWindowingMode) is hidden from apps
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    // Tor (The Tor Project / Guardian Project build) for the Privacy VPN. Pure Java; its newer Kotlin stdlib isn't needed.
    // 0.4.9.8 is the newest release whose classes (Java 21) AGP 8.7's D8 can read; later ones are Java 24 bytecode.
    implementation("info.guardianproject:tor-android:0.4.9.8") { exclude(group = "org.jetbrains.kotlin") }

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // real org.json for plain JVM tests
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// tor-android's AAR metadata asks for compileSdk 37 only because that's what it was built with; its code (a plain
// Service) needs nothing newer than API 24, and AGP 8.7 can't compile against 37.
tasks.matching { it.name.startsWith("check") && it.name.endsWith("AarMetadata") }.configureEach { enabled = false }
