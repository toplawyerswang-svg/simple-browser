plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.trae.simplebrowser"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.trae.lightboat"
        minSdk = 24
        targetSdk = 34
        versionCode = 64
        versionName = "1.18.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // 侧载分发：release 与 debug 同用 debug 密钥——签名一致才能 install -r 覆盖安装不丢数据，
        // 且 release 非 debuggable，系统不再弹「Android 应用兼容性」开发者警告
        getByName("debug")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    packaging {
        // AGP 8.2 打包未压缩 .so 只按 4KB 对齐；改为压缩打包（安装时解压落盘），
        // 绕开 zip 层 16KB 对齐要求；ELF 本体的 16KB 对齐由 CameraX 1.4.2 的库保证
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    // 多身份（multi-profile）WebView：ProfileStore 每身份独立 cookie/localStorage/缓存
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // 扫码：ZXing 纯 Java 解码内核（无 Play Services 依赖，兼容华为设备）+ CameraX 取景
    // CameraX ≥1.4 原生库 libimage_processing_util_jni.so 才按 16KB 页对齐（1.3.4 会被系统弹兼容性警告）
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
}
