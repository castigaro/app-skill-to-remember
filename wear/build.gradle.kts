plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.skilltoremember.app.wear"
    compileSdk = 34

    defaultConfig {
        // Gleiche applicationId und Signatur wie die Handy-App — nur dann
        // stellt die Data-Layer-API die Einstellungen vom Handy hier zu.
        applicationId = "de.skilltoremember.app"
        minSdk = 30 // Wear OS 3 (Galaxy Watch 4)
        targetSdk = 33
        // Kommt in der CI aus der Laufnummer (jeder main-Build ist eine neuere
        // Version, ohne manuelles Hochzählen); lokal gebaut bleibt es 1.
        versionCode = (project.findProperty("VERSION_CODE") as String?)?.toIntOrNull() ?: 1
        versionName = "0.11.0"
    }

    val releaseKeystore = rootProject.file("keystore/release.jks")
    val releaseStorePassword = project.findProperty("RELEASE_STORE_PASSWORD") as String?

    signingConfigs {
        create("release") {
            storeFile = releaseKeystore
            storePassword = releaseStorePassword
            keyAlias = "appsonar"
            keyPassword = releaseStorePassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseKeystore.exists() && releaseStorePassword != null) {
                signingConfig = signingConfigs.getByName("release")
            }
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
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    // androidx.wear zieht transitiv ein altes Fragment (<1.3.0) herein, was der
    // Release-Lint wegen registerForActivityResult zu Recht anmeckert — explizit anheben.
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.wear:wear:1.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.android.gms:play-services-wearable:18.1.0")
}
