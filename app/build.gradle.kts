import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.punxrus.privacyglass"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.punxrus.privacyglass"
        minSdk = 33
        targetSdk = 36
        versionCode = 4
        versionName = "0.4.0"
    }

    // A fixed key committed to the repo so every CI build can update the
    // previous install. It's a throwaway debug key, not a publishing key.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
}
