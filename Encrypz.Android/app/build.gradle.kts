import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

fun localProp(key: String, default: String = ""): String =
    (localProps.getProperty(key)?.takeIf { it.isNotBlank() }
        ?: System.getenv(key)?.takeIf { it.isNotBlank() }
        ?: default).replace("\"", "\\\"")

android {
    namespace = "com.encrypz.privacy"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.encrypz.privacy"
        minSdk = 24
        targetSdk = 36
        versionCode = 11
        versionName = "2.0.0"

        buildConfigField("String", "GOOGLE_CLIENT_ID", "\"${localProp("GOOGLE_CLIENT_ID", "")}\"")
        buildConfigField("String", "GOOGLE_CLIENT_SECRET", "\"${localProp("GOOGLE_CLIENT_SECRET", "")}\"")
        buildConfigField("String", "GOOGLE_PROJECT_ID", "\"${localProp("GOOGLE_PROJECT_ID", "")}\"")
        buildConfigField("String", "GOOGLE_AUTH_URI", "\"${localProp("GOOGLE_AUTH_URI", "https://accounts.google.com/o/oauth2/auth")}\"")
        buildConfigField("String", "GOOGLE_TOKEN_URI", "\"${localProp("GOOGLE_TOKEN_URI", "https://oauth2.googleapis.com/token")}\"")
        buildConfigField("String", "GOOGLE_CERT_URL", "\"${localProp("GOOGLE_CERT_URL", "https://www.googleapis.com/oauth2/v1/certs")}\"")
    }

    val releaseKeystoreExists = run {
        val signingProps = Properties().apply {
            val propsFile = rootProject.file("signing.properties")
            if (propsFile.exists()) propsFile.inputStream().use { load(it) }
        }
        rootProject.file(signingProps.getProperty("storeFile", "signing/upload-keystore.jks")).exists()
    }

    signingConfigs {
        create("release") {
            val signingProps = Properties().apply {
                val propsFile = rootProject.file("signing.properties")
                if (propsFile.exists()) propsFile.inputStream().use { load(it) }
            }
            storeFile = rootProject.file(signingProps.getProperty("storeFile", "signing/upload-keystore.jks"))
            storePassword = signingProps.getProperty("storePassword") ?: System.getenv("ENCRYPZ_STORE_PASSWORD")
            keyAlias = signingProps.getProperty("keyAlias", "encrypz-upload")
            keyPassword = signingProps.getProperty("keyPassword") ?: System.getenv("ENCRYPZ_KEY_PASSWORD")
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (releaseKeystoreExists) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = true
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  implementation("androidx.biometric:biometric:1.1.0")
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  implementation("androidx.work:work-runtime-ktx:2.10.1")

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)

  // Auth & UI
  implementation(libs.play.services.auth)
  implementation(libs.credentials)
  implementation(libs.credentials.play.services.auth)
  implementation(libs.androidx.compose.material.icons.extended)
}
