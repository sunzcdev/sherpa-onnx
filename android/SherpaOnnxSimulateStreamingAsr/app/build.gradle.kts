plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
}

android {
    namespace = "com.k2fsa.sherpa.onnx.simulate.streaming.asr"
    compileSdk = 34

    // 固定 debug 签名: 每次 CI 构建用同一个 keystore，保证签名一致
    // 否则 GitHub Actions runner 每次都生成新的 ~/.android/debug.keystore，
    // 导致 INSTALL_FAILED_UPDATE_INCOMPATIBLE
    // 注意: AGP 预注册了名为 debug 的 signingConfig，只能 getByName 改属性，
    // create("debug") 会报 "SigningConfig with that name already exists"
    signingConfigs {
        // AGP 预注册了名为 debug 的 signingConfig，只能 getByName 改属性，
        // create("debug") 会报 "SigningConfig with that name already exists"
        getByName("debug") {
            storeFile = file("${rootProject.projectDir}/debug.keystore")
            storePassword = "android"
            keyAlias = "debug"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "com.k2fsa.sherpa.onnx.simulate.streaming.asr"
        minSdk = 21
        targetSdk = 34
        versionCode = 202
        versionName = "0.2.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.compose)
    // sherpa-onnx AAR (prebuilt, avoids C++ NDK compilation in CI)
    implementation(files("libs/sherpa-onnx-1.13.7.aar"))
    // v0.2 OCR 导入: ML Kit 中文文本识别 (bundled 模型, 离线可用)
    implementation(libs.mlkit.text.chinese)
    // v0.2 拍照导入: CameraX
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20231013")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}