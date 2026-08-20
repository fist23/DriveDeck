import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "pt.dashboardauto"
    compileSdk = 36

    defaultConfig {
        applicationId = "pt.dashboardauto"
        minSdk = 26
        targetSdk = 35
        versionCode = 29
        versionName = "0.9.12"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

val releaseKeystorePath = providers.environmentVariable("DRIVEDECK_KEYSTORE_PATH").orNull
val releaseKeyAlias = providers.environmentVariable("DRIVEDECK_KEY_ALIAS").orNull
val releaseStorePassword = providers.environmentVariable("DRIVEDECK_STORE_PASSWORD").orNull
val releaseKeyPassword = providers.environmentVariable("DRIVEDECK_KEY_PASSWORD").orNull
val hasReleaseSigning = listOf(releaseKeystorePath, releaseKeyAlias, releaseStorePassword, releaseKeyPassword).all { !it.isNullOrBlank() }

if (hasReleaseSigning) {
    android.signingConfigs.create("release") {
        storeFile = file(releaseKeystorePath!!)
        keyAlias = releaseKeyAlias
        storePassword = releaseStorePassword
        keyPassword = releaseKeyPassword
    }
}

android.buildTypes.getByName("release") {
    isMinifyEnabled = false
    if (hasReleaseSigning) signingConfig = android.signingConfigs.getByName("release")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
