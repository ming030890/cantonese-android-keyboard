import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

// Signing credentials are loaded from keystore.properties (gitignored) or
// environment variables. Release builds are signed only when all four values
// are present; otherwise the release variant falls back to unsigned so local
// debug builds still succeed.
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun cred(key: String): String? =
    keystoreProps.getProperty(key) ?: System.getenv(key)

val ksFile = cred("KEYSTORE_FILE")
val ksPassword = cred("KEYSTORE_PASSWORD")
val ksAlias = cred("KEY_ALIAS")
val ksKeyPassword = cred("KEY_PASSWORD")
val hasReleaseSigning = ksFile != null && ksPassword != null &&
    ksAlias != null && ksKeyPassword != null

android {
    namespace = "com.madeinhk.cantonesevoicekeyboard"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.madeinhk.cantonesevoicekeyboard"
        minSdk = 32
        targetSdk = 36
        versionCode = 52
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The Qwen ASR model is delivered as FIVE fast-follow Play Asset Delivery
    // packs so each stays under Play's 512 MiB per-pack ceiling. Shard 1 +
    // tokenizer files live in :model_pack_a; shards 2..5 live in :model_pack_b..e.
    // See scripts/reshard_model.py for the build-time split.
    assetPacks += listOf(":model_pack_a", ":model_pack_b", ":model_pack_c", ":model_pack_d", ":model_pack_e")


    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(ksFile!!)
                storePassword = ksPassword
                keyAlias = ksAlias
                keyPassword = ksKeyPassword
            }
        }
    }

    buildTypes {
        release {
            buildConfigField("boolean", "INTERNAL_TYPING_TRIAL", providers.gradleProperty("internalTypingTrial").orElse("false").get())
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro", "native-lm-rules.pro")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            buildConfigField("boolean", "INTERNAL_TYPING_TRIAL", providers.gradleProperty("internalTypingTrial").orElse("false").get())
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

    testOptions {
      unitTests.isIncludeAndroidResources = true

      screenshotTests.create("screenshotTest") {
        engineVersion = libs.versions.screenshot.get()
        targetVariants.add("debug")

        dependencies {
          implementation(libs.androidx.compose.ui.tooling)
          implementation(libs.screenshot.validation.api)
        }
      }
    }
}

androidComponents {
    onVariants { variant ->
        // The exact same scenario driver supplies unit assertions and screenshots.
        (variant as com.android.build.api.variant.HasHostTests).hostTests.values.forEach { hostTest ->
            hostTest.sources.kotlin?.addStaticSourceDirectory("src/scenarioTest/java")
        }
        (variant as com.android.build.api.variant.HasTestSuites).suites.values.forEach { suite ->
            suite.sources.filterIsInstance<com.android.build.api.variant.TestSuiteSourceSet.HostJar>().forEach { source ->
                source.kotlin?.addStaticSourceDirectory("src/scenarioTest/java")
            }
        }
    }

    beforeVariants(selector().withBuildType("release")) { variantBuilder ->
        (variantBuilder as com.android.build.api.variant.HasHostTestsBuilder)
            .hostTests
            .getValue(com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE)
            .enable = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Play Asset Delivery (model download / location resolution)
  implementation(libs.play.asset.delivery)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.work.runtime.ktx)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.emoji2)
  implementation(libs.androidx.emoji2.emojipicker)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation("org.json:json:20240303")
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
}

// Baseline updates are deliberately separate; this gate only validates checked-in images.
tasks.register("verifyKeyboard") {
    group = "verification"
    description = "Verify keyboard event sequences, screenshot baselines, and debug assembly."
    dependsOn("testDebugUnitTest", "testDebugScreenshotTestDefaultTestSuite", "assembleDebug")
}

// Opt-in host JNI integration tests use the same Rust exports as Android.
tasks.withType<Test>().configureEach {
    providers.gradleProperty("bilingualAssets").orNull?.let {systemProperty("bilingual.assets",it)}
    providers.gradleProperty("englishNativeLibrary").orNull?.let {
        systemProperty("english.native.library", it)
        systemProperty("java.library.path", File(it).parent)
    }
}
