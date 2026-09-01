plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.volvo960.obdctl"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.volvo960.obdctl"
        minSdk = 26
        targetSdk = 34
        versionCode = 32
        versionName = "0.32.0"
    }

    signingConfigs {
        // A fixed debug key, committed alongside the app.
        //
        // Without one, Gradle signs with ~/.android/debug.keystore — which a CI
        // runner generates fresh on every build. Every APK then carried a
        // different signature, and Android refuses to install an update signed
        // by a different key, so each build had to be uninstalled first.
        //
        // The credentials below are the ones the Android SDK itself uses for
        // its generated debug keystore; this key is not a secret and protects
        // nothing. It only has to stay the same from build to build.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.activity:activity-ktx:1.9.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
