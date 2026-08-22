plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.skilltoremember.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "de.skilltoremember.app"
        minSdk = 26
        targetSdk = 34
        // Kommt in der CI aus der Laufnummer (jeder main-Build ist eine neuere
        // Version, ohne manuelles Hochzählen); lokal gebaut bleibt es 1.
        versionCode = (project.findProperty("VERSION_CODE") as String?)?.toIntOrNull() ?: 1
        versionName = "0.7.0"
    }

    // Keystore und Passwort kommen aus GitHub-Secrets (siehe build.yml).
    // Ohne beides wird die Release-Variante unsigniert gebaut.
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
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Data-Layer-Übertragung der Einstellungen an die Wear-OS-App.
    implementation("com.google.android.gms:play-services-wearable:18.1.0")
}
