import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val localSecrets = Properties().apply {
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) localFile.inputStream().use { load(it) }
}
val gnaniApiKey = providers.gradleProperty("GNANI_API_KEY")
    .orElse(providers.environmentVariable("GNANI_API_KEY"))
    .orElse(localSecrets.getProperty("gnani.api.key", ""))
    .get()
val escapedGnaniApiKey = gnaniApiKey.replace("\\", "\\\\").replace("\"", "\\\"")
val evonBaseUrl = localSecrets.getProperty("evon.base.url", "").trim()
val evonModel = localSecrets.getProperty("evon.model", "gnani/gnani-evon-v3.3-30B-A3B").trim().ifBlank { "gnani/gnani-evon-v3.3-30B-A3B" }
val evonApiKey = localSecrets.getProperty("evon.api.key", "").trim()
val translationApiKey = localSecrets.getProperty("translation.api.key", "").trim()
val cloudApiBaseUrl = localSecrets.getProperty("cloud.api.base.url", "").trim().trimEnd('/')
val escapedEvonBaseUrl = evonBaseUrl.replace("\\", "\\\\").replace("\"", "\\\"")
val escapedEvonModel = evonModel.replace("\\", "\\\\").replace("\"", "\\\"")
val escapedEvonApiKey = evonApiKey.replace("\\", "\\\\").replace("\"", "\\\"")
val escapedTranslationApiKey = translationApiKey.replace("\\", "\\\\").replace("\"", "\\\"")
val escapedCloudApiBaseUrl = cloudApiBaseUrl.replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.meshlink.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.meshlink.app"
        minSdk = 24
        targetSdk = 37
        versionCode = 17
        versionName = "2.0"
        // Credentials default to empty for every variant. Only the local debug build
        // receives the developer key; release APKs never package it.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // Debug APKs are for local testing only: embedded keys can be extracted.
            buildConfigField("String", "GNANI_API_KEY", "\"${escapedGnaniApiKey}\"")
            buildConfigField("String", "EVON_BASE_URL", "\"${escapedEvonBaseUrl}\"")
            buildConfigField("String", "EVON_MODEL", "\"${escapedEvonModel}\"")
            buildConfigField("String", "EVON_API_KEY", "\"${escapedEvonApiKey}\"")
            buildConfigField("String", "TRANSLATION_API_KEY", "\"${escapedTranslationApiKey}\"")
            buildConfigField("String", "CLOUD_API_BASE_URL", "\"${escapedCloudApiBaseUrl}\"")
        }
        release {
            buildConfigField("String", "GNANI_API_KEY", "\"\"")
            buildConfigField("String", "EVON_BASE_URL", "\"\"")
            buildConfigField("String", "EVON_MODEL", "\"gnani/gnani-evon-v3.3-30B-A3B\"")
            buildConfigField("String", "EVON_API_KEY", "\"\"")
            buildConfigField("String", "TRANSLATION_API_KEY", "\"\"")
            buildConfigField("String", "CLOUD_API_BASE_URL", "\"${escapedCloudApiBaseUrl}\"")
            optimization { enable = false }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
