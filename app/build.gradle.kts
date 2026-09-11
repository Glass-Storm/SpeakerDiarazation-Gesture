plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "hk.edu.hkmu.speakerdiarazationdemo"
    compileSdk = 36

    defaultConfig {
        applicationId = "hk.edu.hkmu.speakerdiarazationdemo"
        minSdk = 28
        targetSdk = 28
        versionCode = 1
        // Release version comes from the git tag (e.g. v1.2.3 -> "1.2.3"); falls back to a dev version.
        versionName = System.getenv("GIT_TAG")?.removePrefix("v") ?: "1.0-dev"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release keystore is committed encrypted-free but password-protected; the password
    // and key alias are supplied at build time through environment variables (CI secrets).
    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Fail fast when signing credentials are missing instead of producing an
            // unsigned APK that cannot be installed.
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    lint {
        disable += "ExpiredTargetSdkVersion"
    }

    packaging {
        jniLibs {
            // Work around 16 KB page-size / APK native-load alignment issues on some devices.
            useLegacyPackaging = true
        }
    }
}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)

    // Gesture recognition module (Kotlin)
    implementation(project(":gesture"))
    
    // OkHttp for WebSocket
    implementation("com.squareup.okhttp3:okhttp:4.10.0")
    
    // Gson for JSON parsing
    implementation("com.google.code.gson:gson:2.10.1")
    
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
