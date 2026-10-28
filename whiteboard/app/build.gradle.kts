import com.google.protobuf.gradle.id
import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.protobuf)
  alias(libs.plugins.screenshot)
}

val localProperties = Properties().apply {
    val propertiesFile = rootProject.file("local.properties")
    if (propertiesFile.isFile) {
        propertiesFile.inputStream().use(::load)
    }
}

val repositoryEnvironment = Properties().apply {
    val environmentFile = rootProject.file("../.env")
    if (environmentFile.isFile) {
        environmentFile.inputStream().use(::load)
    }
}

fun credential(environmentName: String, localPropertyName: String): String {
    val value = repositoryEnvironment.getProperty(environmentName)
        ?: localProperties.getProperty(localPropertyName, "")
    return value.trim().let { trimmed ->
        when {
            trimmed.length >= 2 && trimmed.startsWith('"') && trimmed.endsWith('"') ->
                trimmed.substring(1, trimmed.lastIndex)
            trimmed.length >= 2 && trimmed.startsWith('\'') && trimmed.endsWith('\'') ->
                trimmed.substring(1, trimmed.lastIndex)
            else -> trimmed
        }
    }
}

fun buildConfigString(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

// Credentials are compiled into BuildConfig as plain string constants, and R8 does not obscure
// them. That is fine for a locally built demo, but an artifact built from a contributor's .env and
// then published would ship their Ditto license. CI sets this variable for the release path so a
// build that would bake in credentials fails loudly instead of producing a leaky bundle.
val forbidBakedCredentials =
    providers.environmentVariable("WHITEBOARD_FORBID_BAKED_CREDENTIALS").orNull == "1"

fun guardedCredential(environmentName: String, localPropertyName: String): String {
    val value = credential(environmentName, localPropertyName)
    if (forbidBakedCredentials && value.isNotBlank()) {
        throw GradleException(
            "$environmentName is set, but WHITEBOARD_FORBID_BAKED_CREDENTIALS=1 forbids embedding " +
                "Ditto credentials in a distributable artifact. Unset it, or clear .env/local.properties.",
        )
    }
    return value
}

android {
    namespace = "com.ditto.whiteboard"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.ditto.whiteboard"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            "String",
            "DITTO_DATABASE_ID",
            buildConfigString(guardedCredential("DITTO_DATABASE_ID", "dittoWhiteboardDatabaseId")),
        )
        buildConfigField(
            "String",
            "DITTO_OFFLINE_LICENSE_TOKEN",
            buildConfigString(guardedCredential("DITTO_LICENSE", "dittoWhiteboardOfflineLicenseToken")),
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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

    experimentalProperties["android.experimental.enableScreenshotTest"] = true

    testOptions {
      unitTests.isIncludeAndroidResources = true
    }

    lint {
      // A warning is a defect we have not triaged yet, so the build refuses to produce one.
      warningsAsErrors = true
      abortOnError = true
      // The only exception: "a newer version of X is available" fires on upstream's release
      // schedule, not on anything in this repo. Left enabled it would break CI spontaneously,
      // days after a green build, for a change nobody made. Dependency currency is reviewed
      // deliberately (see docs/RELEASING.md), not enforced by a check that rots on its own.
      disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }

    packaging {
      jniLibs {
        // The pinned Ditto preview bundles a 4 KiB-aligned C++ runtime. Prefer the app's NDK 27
        // runtime override, whose 64-bit variants support Android's 16 KiB page-size devices.
        pickFirsts += "**/libc++_shared.so"
      }
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
        excludes += "/META-INF/INDEX.LIST"
        excludes += "/META-INF/io.netty.versions.properties"
      }
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
      // Same policy for the Kotlin compiler: no warnings reach a reviewer unexamined.
      allWarningsAsErrors = true
    }
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  testImplementation(composeBom)
  androidTestImplementation(composeBom)
  screenshotTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.collections.immutable)
  implementation(libs.kotlinx.serialization.json)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(project(":anvil-material3"))
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.adaptive.navigation3)

  // Ditto Data Streams and the compact wire protocol.
  implementation(libs.ditto)
  implementation(libs.protobuf.javalite)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.compose.ui.test.junit4)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  screenshotTestImplementation(libs.screenshot.validation.api)
  screenshotTestImplementation(libs.androidx.compose.ui.tooling)
}

protobuf {
  protoc {
    artifact = libs.protobuf.protoc.get().toString()
  }
  generateProtoTasks {
    all().forEach { task ->
      task.builtins {
        id("java") { option("lite") }
      }
    }
  }
}
