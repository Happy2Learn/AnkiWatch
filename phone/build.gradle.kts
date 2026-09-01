plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.rella.ankiwear.phone"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        // Must match the watch app's applicationId for the Wear OS Data Layer
        // to treat the two apps as a pair.
        applicationId = "com.rella.ankiwear"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
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
    }
}

dependencies {
    implementation("com.google.android.gms:play-services-wearable:18.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")
    implementation("org.jsoup:jsoup:1.17.2")
    implementation(platform(libs.compose.bom))
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    debugImplementation(libs.ui.tooling)

    testImplementation("junit:junit:4.13.2")
    // Real org.json implementation for JVM unit tests (Android provides this
    // at runtime, but on the plain JVM we need the library).
    testImplementation("org.json:json:20231013")
}
