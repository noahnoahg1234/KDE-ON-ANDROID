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
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
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
        unitTests.all { it.systemProperty("robolectric.graphicsMode", "NATIVE"); it.maxHeapSize = "2g" }
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

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
