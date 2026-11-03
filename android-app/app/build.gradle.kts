import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import javax.inject.Inject

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Passwords are supplied only for a short-lived local signing invocation.
// Never serialize them into Gradle's configuration cache or a project file.
val signingVariables = listOf(
    "MOBILEHARNESS_RELEASE_STORE_FILE", "MOBILEHARNESS_RELEASE_STORE_PASSWORD",
    "MOBILEHARNESS_RELEASE_KEY_ALIAS", "MOBILEHARNESS_RELEASE_KEY_PASSWORD",
)
val releaseCredentials = signingVariables.map { providers.environmentVariable(it).orNull }
val releaseSigningReady = releaseCredentials.all { !it.isNullOrBlank() }
check(releaseCredentials.all { it == null } || releaseSigningReady) {
    "Incomplete release signing environment. Use tools/Invoke-ReleaseBuild.ps1."
}
val releasePackagingTasks = setOf("packageRelease", "signReleaseBundle", "bundleRelease", "assembleRelease")
check(releaseSigningReady || gradle.startParameter.taskNames.none { it.substringAfterLast(':') in releasePackagingTasks }) {
    "Release signing is unavailable. Use tools/Invoke-ReleaseBuild.ps1; no debug-key fallback is allowed."
}
if (releaseSigningReady) {
    check(!gradle.startParameter.isConfigurationCacheRequested) {
        "Signed builds require --no-configuration-cache. Use tools/Invoke-ReleaseBuild.ps1."
    }
    val store = file(releaseCredentials[0]!!).canonicalFile
    val repository = rootProject.projectDir.parentFile.canonicalFile.toPath()
    check(store.isFile && !store.toPath().startsWith(repository)) {
        "The release keystore must exist outside the repository."
    }
}

android {
    namespace = "org.mobileharness.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "org.mobileharness.app"
        minSdk = 30
        targetSdk = 36
        versionCode = 22
        versionName = "0.10.0"
        testInstrumentationRunner = "org.mobileharness.app.LiveServiceTestRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    signingConfigs {
        if (releaseSigningReady) create("localRelease") {
            storeFile = file(releaseCredentials[0]!!)
            storePassword = releaseCredentials[1]
            keyAlias = releaseCredentials[2]
            keyPassword = releaseCredentials[3]
            storeType = "PKCS12"
        }
    }
    buildTypes {
        debug { versionNameSuffix = "-dev" }
        release {
            applicationIdSuffix = ".release"
            versionNameSuffix = "-beta.1"
            isMinifyEnabled = false
            isDebuggable = false
            if (releaseSigningReady) signingConfig = signingConfigs.getByName("localRelease")
        }
    }
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}

// Public checkouts can sync and build debug without private credentials. A
// release packaging request must fail closed instead of creating an unsigned APK.
tasks.configureEach {
    if (name in releasePackagingTasks) {
        doFirst {
            check(releaseSigningReady) {
                "Release signing is unavailable. Use tools/Invoke-ReleaseBuild.ps1; no debug-key fallback is allowed."
            }
        }
    }
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

abstract class SyncPromptAssets : DefaultTask() {
    @get:InputDirectory abstract val source: DirectoryProperty
    @get:InputDirectory abstract val automaticSource: DirectoryProperty
    @get:OutputDirectory abstract val output: DirectoryProperty
    @get:Inject abstract val fs: FileSystemOperations
    @TaskAction fun sync() {
        fs.sync {
            into(output)
            into("prompts/v1.1.0") { from(source) }
            into("prompts/v1.2.0") { from(automaticSource) }
        }
    }
}
val syncPromptAssets = tasks.register<SyncPromptAssets>("syncPromptAssets") {
    source.set(rootProject.layout.projectDirectory.dir("../prompts/v1.1.0"))
    automaticSource.set(rootProject.layout.projectDirectory.dir("../prompts/v1.2.0"))
    output.set(layout.buildDirectory.dir("generated/promptAssets"))
}
androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(syncPromptAssets, SyncPromptAssets::output)
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.13.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
