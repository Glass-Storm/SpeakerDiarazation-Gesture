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

    // The hub's FROZEN `ecosys.v1` contract (phone-manager). `:app` is the one
    // module that consumes it; the eventual ecosys client (T15) will live here.
    // The artifact was generated in protobuf-LITE mode, so its runtime is
    // protobuf-javalite/grpc-protobuf-lite — NEVER full protobuf-java or
    // grpc-protobuf (mixing the two corrupts the wire serializer at runtime).
    // Declared explicitly (in addition to the contract's transitive POM deps) so
    // the runtime is visible and pinned to the hub's exact versions.
    implementation(libs.ecosys.contract)
    implementation(libs.grpc.stub)
    implementation(libs.grpc.protobuf.lite)
    implementation(libs.grpc.kotlin.stub)
    implementation(libs.protobuf.javalite)
    implementation(libs.protobuf.kotlin.lite)

    // Gson for JSON parsing
    implementation("com.google.code.gson:gson:2.10.1")
    
    testImplementation(libs.junit)
    // Stands an ecosys.v1 gRPC server up in the same JVM as the unit test so the
    // stream client can be round-tripped without a socket, device, or emulator.
    testImplementation(libs.grpc.inprocess)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
