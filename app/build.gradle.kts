import java.util.Properties

plugins {
    id("com.android.application")
}

val supabaseProperties = Properties()
val supabaseFile = rootProject.file("supabase.properties")
if (supabaseFile.exists()) supabaseFile.inputStream().use { supabaseProperties.load(it) }
fun supabaseProperty(name: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() }?.trim()
        ?: supabaseProperties.getProperty(name, "").trim()
fun buildConfigString(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""

// Release signing is deliberately external to the repository.
// Values can come from keystore.properties (ignored by git) or environment variables.
val signingProperties = Properties()
val signingFile = rootProject.file("keystore.properties")
if (signingFile.exists()) signingFile.inputStream().use { signingProperties.load(it) }

fun signingValue(propertyName: String, envName: String): String =
    System.getenv(envName)?.takeIf { it.isNotBlank() }
        ?: signingProperties.getProperty(propertyName, "").trim()

val releaseStoreFile = signingValue("storeFile", "MRX_RELEASE_STORE_FILE")
val releaseStorePassword = signingValue("storePassword", "MRX_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "MRX_RELEASE_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "MRX_RELEASE_KEY_PASSWORD")
val releaseSigningConfigured = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { it.isNotBlank() }

val releaseTaskRequested = gradle.startParameter.taskNames.any { task ->
    task.substringAfterLast(":").contains("release", ignoreCase = true)
}

if (releaseTaskRequested && !releaseSigningConfigured) {
    throw GradleException(
        "Release signing is not configured. Create keystore.properties from " +
            "keystore.properties.example, or provide MRX_RELEASE_* environment variables. " +
            "Do not commit the keystore or passwords."
    )
}

android {
    namespace = "com.socialnetwork.app"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.socialnetwork.app"
        // Widest practical range with the current 2026 AndroidX Activity stack.
        // ActivityResult APIs require API 21+, while the app code provides runtime fallbacks
        // for pre-23, pre-24 and pre-29 platform behavior.
        minSdk = 21
        targetSdk = 37
        versionCode = 51
        versionName = "2.19.6"
        buildConfigField("String", "SUPABASE_URL", buildConfigString(supabaseProperty("SUPABASE_URL")))
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", buildConfigString(supabaseProperty("SUPABASE_PUBLISHABLE_KEY")))
        buildConfigField("String", "SUPABASE_DATA_READ_URL", buildConfigString(supabaseProperty("SUPABASE_DATA_READ_URL").ifBlank { supabaseProperty("SUPABASE_URL") }))
        buildConfigField("String", "SUPABASE_DATA_WRITE_URL", buildConfigString(supabaseProperty("SUPABASE_DATA_WRITE_URL").ifBlank { supabaseProperty("SUPABASE_URL") }))
        buildConfigField("String", "SUPABASE_AUTH_URL", buildConfigString(supabaseProperty("SUPABASE_AUTH_URL").ifBlank { supabaseProperty("SUPABASE_URL") }))
        buildConfigField("String", "SUPABASE_STORAGE_URL", buildConfigString(supabaseProperty("SUPABASE_STORAGE_URL").ifBlank { supabaseProperty("SUPABASE_URL") }))
    }

    signingConfigs {
        create("release") {
            if (releaseSigningConfigured) {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { buildConfig = true }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
}
