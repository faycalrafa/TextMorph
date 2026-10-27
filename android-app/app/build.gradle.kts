plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.textmorph.app"
    compileSdk = 36
    val appVersionName = providers.environmentVariable("APP_VERSION_NAME")
        .orElse(providers.gradleProperty("appVersionName"))
        .getOrElse("3.5.0")

    defaultConfig {
        applicationId = "com.textmorph.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 54
        versionName = appVersionName

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("keystore/release.keystore")
            storePassword = providers.environmentVariable("KEYSTORE_PASSWORD")
                .orElse(providers.gradleProperty("KEYSTORE_PASSWORD"))
                .getOrElse("novelarrow")
            keyAlias = providers.environmentVariable("KEY_ALIAS")
                .orElse(providers.gradleProperty("KEY_ALIAS"))
                .getOrElse("novelarrow")
            keyPassword = providers.environmentVariable("KEY_PASSWORD")
                .orElse(providers.gradleProperty("KEY_PASSWORD"))
                .getOrElse("novelarrow")
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
      compose = true
      aidl = false
      buildConfig = false
      shaders = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
        excludes += "META-INF/INDEX.LIST"
        excludes += "META-INF/io.netty.versions.properties"
      }
      jniLibs {
        pickFirsts += listOf("**/librnllama*.so", "**/libllama*.so")
      }
    }

    androidResources {
        noCompress += listOf("litertlm", "tflite", "bin", "onnx", "gguf")
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Architecture & Lifecycle
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.lifecycle.service)

  // UI Material 3 & Icons
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.ui.tooling.preview)
  debugImplementation(libs.androidx.compose.ui.tooling)

  // Storage & Preferences
  implementation(libs.androidx.documentfile)
  implementation(libs.androidx.datastore.preferences)

  // Serialization & Coroutines
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.coroutines.android)

  // Markdown rendering
  implementation(libs.markwon.core)

  // Embedded Ktor Server for localhost OpenAI API (127.0.0.1:8080)
  implementation(libs.ktor.server.core)
  implementation(libs.ktor.server.cio)
  implementation(libs.ktor.server.content.negotiation)
  implementation(libs.ktor.serialization.kotlinx.json)
  implementation(libs.ktor.server.cors)

  // Google AI Edge LiteRT-LM native on-device runtime (ARM64 & x86_64 JNI)
  implementation(libs.litertlm.android)

  // llama.cpp native on-device runtime (ARM64 NEON/dotprod/i8mm & x86_64 JNI)
  implementation(libs.llamacpp.android)

  // WorkManager for background model downloading
  implementation(libs.androidx.work.runtime.ktx)

  // Testing
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.espresso.core)
}
