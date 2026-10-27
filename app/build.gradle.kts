import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.serialization)
}

// API key sources (first non-blank wins): process env, local.properties, root .env.
// Never hard-code. Empty string when unset; ApiKeyProvider treats blank as absent.
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use(localProperties::load)
}
// Minimal .env reader: NAME=value lines, skips blanks/comments, strips quotes.
// A file containing only a bare key with no NAME= line provides nothing.
val dotEnv = mutableMapOf<String, String>()
val dotEnvFile = rootProject.file(".env")
if (dotEnvFile.exists()) {
    dotEnvFile.forEachLine { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEachLine
        val eq = trimmed.indexOf('=')
        if (eq <= 0) return@forEachLine
        val name = trimmed.substring(0, eq).trim().removePrefix("export").trim()
        val value = trimmed.substring(eq + 1).trim().trim('"').trim('\'')
        if (name.isNotEmpty()) dotEnv[name] = value
    }
}
fun configValue(vararg names: String): String {
    for (name in names) {
        System.getenv(name)?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        localProperties.getProperty(name)?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        dotEnv[name]?.takeIf { it.isNotBlank() }?.let { return it.trim() }
    }
    return ""
}
// META_API_KEY matches LiteLLM convention; MODEL_API_KEY matches Meta's own examples.
val metaApiKey: String = configValue("META_API_KEY", "MODEL_API_KEY")
// Official Meta Model API defaults (dev.meta.ai/docs); override via env/local.properties/.env.
val metaApiBaseUrl: String =
    configValue("META_API_BASE_URL", "META_API_BASE")
        .takeIf { it.isNotBlank() } ?: "https://api.meta.ai/v1/"
val metaSttUrl: String =
    configValue("META_STT_URL").takeIf { it.isNotBlank() } ?: "wss://api.meta.ai/v1/asr/realtime"

android {
    namespace = "com.smartcess.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.smartcess.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "META_API_KEY", "\"$metaApiKey\"")
        buildConfigField("String", "META_API_BASE_URL", "\"$metaApiBaseUrl\"")
        buildConfigField("String", "META_STT_URL", "\"$metaSttUrl\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Distributable APKs are always keyless: release ignores every key
            // source. Real keys reach release builds only via in-app Settings.
            buildConfigField("String", "META_API_KEY", "\"\"")
        }
        debug {
            // Keep BuildConfig fields present in both build types.
            buildConfigField("String", "META_API_KEY", "\"$metaApiKey\"")
            buildConfigField("String", "META_API_BASE_URL", "\"$metaApiBaseUrl\"")
            buildConfigField("String", "META_STT_URL", "\"$metaSttUrl\"")
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Room schema export location for SmartCessDatabase (exportSchema = true).
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    // Night Concourse pictogram insets (Mic, PhotoCamera live in extended).
    implementation(libs.compose.material.icons.extended)
    implementation(libs.navigation.compose)
    implementation(libs.hilt.navigation.compose)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.work.runtime.ktx)

    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.mlkit.text.recognition)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.security.crypto)

    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.androidx.core.testing)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.work.testing)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.room.testing)
}

// Room schema JSONs on the instrumented-test classpath for MigrationTestHelper.
androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.sources.assets?.addStaticSourceDirectory("schemas")
    }
}
