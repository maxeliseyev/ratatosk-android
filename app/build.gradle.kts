import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // AGP 9 has built-in Kotlin support; only the Compose compiler plugin is
    // applied on top of it.
    alias(libs.plugins.kotlin.compose)
}

// Release signing: locally from keystore.properties (gitignored), in CI from
// the environment. Without a key the release build falls back to the debug
// signature so `assembleRelease` still works.
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun secret(name: String): String? = keystoreProps.getProperty(name) ?: System.getenv(name)

val releaseKeystore = secret("RATATOSK_KEYSTORE_FILE")
val hasReleaseKey = !releaseKeystore.isNullOrBlank() && file(releaseKeystore).exists()

android {
    namespace = "dev.ratatosk"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.ratatosk"
        minSdk = 26
        targetSdk = 36
        // Overridden by the release workflow: -PversionName=0.2.0 -PversionCode=42.
        versionCode = (providers.gradleProperty("versionCode").orNull ?: "1").toInt()
        versionName = providers.gradleProperty("versionName").orNull ?: "0.1.0"

        ndk {
            // ABIs that scripts/update-core.sh builds.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(releaseKeystore!!)
                storePassword = secret("RATATOSK_KEYSTORE_PASSWORD")
                keyAlias = secret("RATATOSK_KEY_ALIAS")
                keyPassword = secret("RATATOSK_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 is deliberately off for now: the JNI surface and CameraX are
            // small, and a working signed build matters more than a few MB.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)
}
