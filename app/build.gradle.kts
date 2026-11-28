import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "de.pflegeexamen.app"
    compileSdk = 36

    val contentUrl = providers.gradleProperty("contentUrl").orElse("").get()
    require(contentUrl.isEmpty() || runCatching {
        val uri = URI(contentUrl)
        uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null && uri.query == null && uri.path == "/content"
    }.getOrDefault(false)) { "contentUrl muss eine öffentliche HTTPS-URL mit Pfad /content sein." }

    // Only explicitly enabled for the signed public distribution; no custom update host.
    val enableAppUpdates = providers.gradleProperty("enableAppUpdates").orElse("false").get()
    require(enableAppUpdates == "true" || enableAppUpdates == "false") { "enableAppUpdates muss true oder false sein." }

    // Build-time switches only, never user settings. Participation is experimental and off by default.
    val enableRankedParticipation = providers.gradleProperty("enableRankedParticipation").orElse("false").get()
    require(enableRankedParticipation == "true" || enableRankedParticipation == "false") { "enableRankedParticipation muss true oder false sein." }
    val rankingUrl = providers.gradleProperty("rankingUrl").orElse("").get()
    require(enableRankedParticipation != "true" || rankingUrl.isNotBlank()) { "Wertungsteilnahme benötigt eine Ranking-URL." }
    require(rankingUrl.isEmpty() || runCatching {
        val uri = URI(rankingUrl)
        uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null && uri.query == null && uri.rawPath == "/leaderboard"
    }.getOrDefault(false)) { "rankingUrl muss eine öffentliche HTTPS-URL mit Pfad /leaderboard sein." }

    defaultConfig {
        applicationId = "de.pflegeexamen.app"
        minSdk = 26
        targetSdk = 35
        buildConfigField("String", "CONTENT_URL", "\"$contentUrl\"")
        buildConfigField("String", "RANKING_URL", "\"$rankingUrl\"")
        buildConfigField("boolean", "ENABLE_RANKED_PARTICIPATION", enableRankedParticipation)
        buildConfigField("boolean", "ENABLE_APP_UPDATES", enableAppUpdates)
        versionCode = 23
        versionName = "3.0.12"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Optional, explicitly supplied release credentials. Never embed them in source.
    // A partial configuration is an error rather than silently producing an unsigned APK.
    val releaseKeyNames = listOf("PFLEGE_RELEASE_KEYSTORE", "PFLEGE_RELEASE_STORE_PASSWORD", "PFLEGE_RELEASE_KEY_ALIAS", "PFLEGE_RELEASE_KEY_PASSWORD")
    val releaseKeys = releaseKeyNames.associateWith { providers.environmentVariable(it).orNull?.takeIf(String::isNotBlank) }
    val hasReleaseKeys = releaseKeys.values.any { it != null }
    if (hasReleaseKeys) {
        require(releaseKeys.values.all { it != null }) { "Release-Signatur ist unvollständig konfiguriert." }
        require(file(releaseKeys.getValue("PFLEGE_RELEASE_KEYSTORE")!!).isFile) { "Release-Keystore nicht gefunden." }
        signingConfigs.create("configuredRelease") {
            storeFile = file(releaseKeys.getValue("PFLEGE_RELEASE_KEYSTORE")!!)
            storePassword = releaseKeys.getValue("PFLEGE_RELEASE_STORE_PASSWORD")
            keyAlias = releaseKeys.getValue("PFLEGE_RELEASE_KEY_ALIAS")
            keyPassword = releaseKeys.getValue("PFLEGE_RELEASE_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            if (hasReleaseKeys) signingConfig = signingConfigs.getByName("configuredRelease")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE*"
            excludes += "META-INF/NOTICE*"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // 100% Offline PDF & OCR (Bundled Latin Model)
    implementation(libs.pdfbox.android)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.genai.prompt)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
