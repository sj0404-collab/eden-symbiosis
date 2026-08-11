plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.symbiosis.panel"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.symbiosis.panel"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            // No shrinking: the whole app is one activity, so R8 would save
            // kilobytes while making a crash report harder to read.
            isMinifyEnabled = false
            // Signed with the debug key on purpose. This is sideloaded from a
            // link, never published to Play, and a release build with no
            // signing config cannot be installed at all.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.9.2")
}
