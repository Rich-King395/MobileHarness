import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import javax.inject.Inject

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "org.mobileharness.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "org.mobileharness.app"
        minSdk = 30
        targetSdk = 36
        versionCode = 21
        versionName = "0.9.0-dev"
        testInstrumentationRunner = "org.mobileharness.app.LiveServiceTestRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
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
