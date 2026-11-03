plugins {
    id("com.android.application")
}

android {
    namespace = "org.mobileharness.fixture"
    compileSdk = 36
    defaultConfig {
        applicationId = "org.mobileharness.fixture"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = false
        aidl = false
        shaders = false
    }
}
