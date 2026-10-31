plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Single source of truth: ratatoskrAndroidVersion in clients/gradle.properties
// (independent of the desktop version). versionCode has to increase with
// every release Android should treat as an update, so it's derived from
// the version rather than kept by hand: 0.1.0 -> 100, 1.2.3 -> 10203.
val appVersion = providers.gradleProperty("ratatoskrAndroidVersion").get()
val appVersionCode = appVersion.split(".").map(String::toInt).let { (major, minor, patch) ->
    require(minor < 100 && patch < 100) { "minor/patch must be < 100 for versionCode" }
    major * 10_000 + minor * 100 + patch
}

// Release signing comes from the environment (GitHub Actions secrets in CI,
// see clients/README.md). The keystore never lives in the repo. Without
// it, assembleRelease still builds, just unsigned.
val releaseKeystore = providers.environmentVariable("RATATOSKR_KEYSTORE_FILE").orNull
fun signingEnv(name: String): String = providers.environmentVariable(name).orNull
    ?: error("RATATOSKR_KEYSTORE_FILE is set but $name isn't. Set all four signing variables (clients/README.md)")

android {
    namespace = "com.ratatoskr.android"
    compileSdk = 37

    defaultConfig {
        // Permanent once released: Android treats a different ID as a
        // different app. Lowercase by Android convention; otherwise matches
        // the desktop app ID.
        applicationId = "io.github.curtis04ben.ratatoskr"
        // 26 = Android 8.0, where the Autofill Framework (see autofill/)
        // arrived.
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersion
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = signingEnv("RATATOSKR_KEYSTORE_PASSWORD")
                keyAlias = signingEnv("RATATOSKR_KEY_ALIAS")
                keyPassword = signingEnv("RATATOSKR_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Not shrunk yet: Ktor and kotlinx.serialization need R8 keep
            // rules tested on a real device first. Worth doing later. It
            // would roughly halve the APK.
            isMinifyEnabled = false
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // Installs alongside a release build instead of replacing it.
            applicationIdSuffix = ".debug"
        }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation("androidx.activity:activity-compose:1.13.0")
    // Kept in step with :shared's Compose versions.
    implementation("org.jetbrains.compose.foundation:foundation:1.12.1")
    implementation("org.jetbrains.compose.ui:ui:1.12.1")
    // Autofill's own screens use the shared theme's Material components.
    implementation("org.jetbrains.compose.material3:material3:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    testImplementation(kotlin("test-junit"))
}
