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

fun localProp(key: String): String =
    (localProps.getProperty(key) ?: System.getenv(key) ?: "").replace("\"", "\\\"")

android {
    namespace = "com.encrypz.privacy"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.encrypz.privacy"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1"

        buildConfigField("String", "ACCOUNT_API_BASE_URL", "\"${localProp("ACCOUNT_API_BASE_URL")}\"")
        buildConfigField("String", "GOOGLE_CLIENT_ID", "\"${localProp("GOOGLE_CLIENT_ID")}\"")
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
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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
