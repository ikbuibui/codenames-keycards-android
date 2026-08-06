plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
}

// Set these environment variables to produce a signed release APK. They are
// supplied by the GitHub release workflow and are deliberately not in source.
val releaseStoreFile: String? = System.getenv("RELEASE_STORE_FILE")
val releaseStorePassword: String? = System.getenv("RELEASE_STORE_PASSWORD")
val releaseKeyAlias: String? = System.getenv("RELEASE_KEY_ALIAS")
val releaseKeyPassword: String? = System.getenv("RELEASE_KEY_PASSWORD")
val hasReleaseSigningCredentials = listOf(
  releaseStoreFile,
  releaseStorePassword,
  releaseKeyAlias,
  releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
  namespace = "com.codenames.keycards"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.codenames.keycards"
    minSdk = 24
    targetSdk = 36
    versionCode = 4
    versionName = "4.0"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    if (hasReleaseSigningCredentials) {
      create("release") {
        storeType = "PKCS12"
        storeFile = rootProject.file(requireNotNull(releaseStoreFile))
        storePassword = requireNotNull(releaseStorePassword)
        keyAlias = requireNotNull(releaseKeyAlias)
        keyPassword = requireNotNull(releaseKeyPassword)
      }
    }
  }

  buildTypes {
    release {
      if (hasReleaseSigningCredentials) {
        signingConfig = signingConfigs.getByName("release")
      }
      isMinifyEnabled = false
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
    buildConfig = false
    shaders = false
  }
  packaging {
    resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
  }
  splits {
    abi {
      isEnable = true
      reset()
      include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
      isUniversalApk = true
    }
  }
  sourceSets.getByName("androidTest").assets.directories.add("src/test/resources")
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.camera.core)
  implementation(libs.androidx.camera.camera2)
  implementation(libs.androidx.camera.lifecycle)
  implementation(libs.androidx.camera.view)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.exifinterface)
  implementation(libs.opencv)
  implementation(libs.tesseract4android)

  debugImplementation(libs.androidx.compose.ui.tooling)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  testImplementation(libs.junit)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
}
