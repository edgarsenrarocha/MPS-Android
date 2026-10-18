plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mpsdiag"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mpsdiag"
        minSdk = 23
        targetSdk = 35
        versionCode = 4
        versionName = "0.4.0"
    }

    signingConfigs {
        // Same key as tools/build.sh, so APKs from either build install over each other.
        create("shared") {
            storeFile = rootProject.file("signing/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
