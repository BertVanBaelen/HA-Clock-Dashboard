import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/**
 * versionName follows the app add-on version (ATR-REL-03): read from
 * ../alarm_actions_app/config.yaml, overridable with -PappVersion=x.y.z.
 * versionCode = major * 1_000_000 + minor * 1_000 + patch, overridable with -PappVersionCode=N.
 */
val addonConfig = rootProject.layout.projectDirectory.file("../alarm_actions_app/config.yaml")
val configVersion: String? = providers.fileContents(addonConfig).asText.orNull
    ?.lineSequence()
    ?.firstNotNullOfOrNull { Regex("""^version:\s*"?(\d+\.\d+\.\d+)"?\s*$""").find(it.trim())?.groupValues?.get(1) }
val appVersionName: String = providers.gradleProperty("appVersion").orNull ?: configVersion ?: "0.0.1"
val appVersionCode: Int = providers.gradleProperty("appVersionCode").orNull?.toInt() ?: run {
    val parts = appVersionName.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    require(parts.size == 3 && parts[1] < 1000 && parts[2] < 1000) { "Version must be x.y.z: $appVersionName" }
    parts[0] * 1_000_000 + parts[1] * 1_000 + parts[2]
}

/** Release signing from environment variables or Gradle properties; unsigned when absent (ASEC-07). */
fun signingValue(name: String): String? =
    providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }
        ?: providers.gradleProperty(name).orNull?.takeIf { it.isNotBlank() }

val keystorePath = signingValue("ANDROID_KEYSTORE_FILE")
val hasReleaseSigning = keystorePath != null && signingValue("ANDROID_KEYSTORE_PASSWORD") != null &&
    signingValue("ANDROID_KEY_ALIAS") != null

android {
    namespace = "alarmclock.vanbaelen.dev"
    compileSdk = 37

    defaultConfig {
        applicationId = "alarmclock.vanbaelen.dev"
        minSdk = 29
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = File(keystorePath!!)
                storePassword = signingValue("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = signingValue("ANDROID_KEY_ALIAS")
                keyPassword = signingValue("ANDROID_KEY_PASSWORD") ?: signingValue("ANDROID_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
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
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            // Single source of truth for the schedule vectors shared with the server (ATR-SYN-01).
            val vectors = rootProject.layout.projectDirectory.file("../docs/test-vectors/schedule.json").asFile
            it.inputs.file(vectors).withPropertyName("scheduleVectors")
            it.systemProperty("scheduleVectors", vectors.absolutePath)
            it.systemProperty("addonConfig", addonConfig.asFile.absolutePath)
        }
    }
}

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.browser)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
