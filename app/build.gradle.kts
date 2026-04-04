plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.cantonesevoiceinput"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.cantonesevoiceinput"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.benchmark.junit4.AndroidBenchmarkRunner"

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                cppFlags += "-O3"
            }
        }

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }

        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    androidResources {
        noCompress += setOf("safetensors", "json", "txt")
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    testBuildType = "benchmark"

    testOptions {
        animationsDisabled = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    add("benchmarkImplementation", "androidx.benchmark:benchmark-common:1.3.4")
    androidTestImplementation("androidx.benchmark:benchmark-junit4:1.3.4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
