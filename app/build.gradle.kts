plugins {
    id("com.android.application")
}

val releaseKeystorePath = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull

android {
    namespace = "com.example.vscodemobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.vscodemobile"
        minSdk = 24
        targetSdk = 36
        versionCode = 3
        versionName = providers.gradleProperty("appVersion").orElse("0.3.0").get()
    }

    signingConfigs {
        // Use a persistent repository keystore only when GitHub Actions secrets are configured.
        if (!releaseKeystorePath.isNullOrBlank()) {
            create("production") {
                storeFile = file(releaseKeystorePath)
                storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            // The debug key fallback is only for preview releases, not store distribution.
            signingConfig = if (releaseKeystorePath.isNullOrBlank()) {
                signingConfigs.getByName("debug")
            } else {
                signingConfigs.getByName("production")
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.webkit:webkit:1.18.0")
}
