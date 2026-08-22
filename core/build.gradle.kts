plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// Gemeinsame Logik von Handy-App (:app) und Uhr-App (:wear): KI-Anbindung,
// Chat-/Skill-Speicher und die komplette Gedächtnis-Engine samt GitHub-Sync.
// Die Klassen behalten ihre Pakete unter de.skilltoremember.app.*.
android {
    namespace = "de.skilltoremember.core"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Data-Layer-Nachrichten Uhr -> Handy (Kalender-/E-Mail-Weiterleitung in DeviceActions).
    implementation("com.google.android.gms:play-services-wearable:18.1.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
