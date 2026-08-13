plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("com.google.gms.google-services")
}

android {
    namespace = "com.example.listazakupow"

    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.listazakupow"

        minSdk = 24
        targetSdk = 36

        val githubRunNumber =
            System.getenv("GITHUB_RUN_NUMBER")
                ?.toIntOrNull()
                ?: 1

        versionCode = githubRunNumber
        versionName = "1.$githubRunNumber"

        testInstrumentationRunner =
            "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile =
                rootProject.file("listazakupow-release.jks")

            storePassword =
                System.getenv("KEYSTORE_PASSWORD")

            keyAlias =
                System.getenv("KEY_ALIAS")

            keyPassword =
                System.getenv("KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false

            signingConfig =
                signingConfigs.getByName("release")

            proguardFiles(
                getDefaultProguardFile(
                    "proguard-android-optimize.txt"
                ),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {

    // Firebase
    implementation(
        platform(
            "com.google.firebase:firebase-bom:34.13.0"
        )
    )

    implementation(
        "com.google.firebase:firebase-auth"
    )

    implementation(
        "com.google.firebase:firebase-firestore"
    )

    // Coil
    implementation(
        "io.coil-kt:coil-compose:2.7.0"
    )

    // Jetpack Compose
    implementation(
        platform(libs.androidx.compose.bom)
    )

    implementation(
        libs.androidx.activity.compose
    )

    implementation(
        libs.androidx.compose.material3
    )

    implementation(
        libs.androidx.compose.ui
    )

    implementation(
        libs.androidx.compose.ui.graphics
    )

    implementation(
        libs.androidx.compose.ui.tooling.preview
    )

    // AndroidX
    implementation(
        libs.androidx.core.ktx
    )

    implementation(
        libs.androidx.lifecycle.runtime.ktx
    )

    // Tests
    testImplementation(
        libs.junit
    )

    androidTestImplementation(
        platform(libs.androidx.compose.bom)
    )

    androidTestImplementation(
        libs.androidx.compose.ui.test.junit4
    )

    androidTestImplementation(
        libs.androidx.espresso.core
    )

    androidTestImplementation(
        libs.androidx.junit
    )

    // Debug
    debugImplementation(
        libs.androidx.compose.ui.test.manifest
    )

    debugImplementation(
        libs.androidx.compose.ui.tooling
    )
}