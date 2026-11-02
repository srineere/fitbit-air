import java.util.Properties

plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

// Release signing comes from android/key.properties (local, never committed)
// or AIR_RELAY_* environment variables (CI). Without either, release builds
// fall back to the debug key and must not be distributed.
val releaseKey = Properties().apply {
    rootProject.file("key.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
}
fun releaseSetting(name: String, env: String): String? =
    releaseKey.getProperty(name) ?: System.getenv(env)

android {
    namespace = "dev.airpulse.fitbit_air_example"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    defaultConfig {
        // Final: Play and GitHub releases share this ID; it can never change.
        applicationId = "dev.airpulse.airrelay"
        minSdk = flutter.minSdkVersion
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    signingConfigs {
        create("release") {
            releaseSetting("storeFile", "AIR_RELAY_KEYSTORE")?.let { path ->
                storeFile = file(path)
                storePassword = releaseSetting("storePassword", "AIR_RELAY_KEYSTORE_PASSWORD")
                keyAlias = releaseSetting("keyAlias", "AIR_RELAY_KEY_ALIAS")
                keyPassword = releaseSetting("keyPassword", "AIR_RELAY_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            val release = signingConfigs.getByName("release")
            signingConfig = if (release.storeFile != null) release else signingConfigs.getByName("debug")
        }
    }
}

flutter {
    source = "../.."
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
