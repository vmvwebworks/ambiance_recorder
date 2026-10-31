plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.ambiance.recorder"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.ambiance.recorder"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
}
