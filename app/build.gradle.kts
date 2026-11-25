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
        versionCode = 73
        versionName = "1.25.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // 渚ц浇鍒嗗彂锛歳elease 涓?debug 鍚岀敤 debug 瀵嗛挜鈥斺€旂鍚嶄竴鑷存墠鑳?install -r 瑕嗙洊瀹夎涓嶄涪鏁版嵁锛?        // 涓?release 闈?debuggable锛岀郴缁熶笉鍐嶅脊銆孉ndroid 搴旂敤鍏煎鎬с€嶅紑鍙戣€呰鍛?        getByName("debug")
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
        // AGP 8.2 鎵撳寘鏈帇缂?.so 鍙寜 4KB 瀵归綈锛涙敼涓哄帇缂╂墦鍖咃紙瀹夎鏃惰В鍘嬭惤鐩橈級锛?        // 缁曞紑 zip 灞?16KB 瀵归綈瑕佹眰锛汦LF 鏈綋鐨?16KB 瀵归綈鐢?CameraX 1.4.2 鐨勫簱淇濊瘉
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
    // 澶氳韩浠斤紙multi-profile锛塛ebView锛歅rofileStore 姣忚韩浠界嫭绔?cookie/localStorage/缂撳瓨
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // 鎵爜锛歓Xing 绾?Java 瑙ｇ爜鍐呮牳锛堟棤 Play Services 渚濊禆锛屽吋瀹瑰崕涓鸿澶囷級+ CameraX 鍙栨櫙
    // CameraX 鈮?.4 鍘熺敓搴?libimage_processing_util_jni.so 鎵嶆寜 16KB 椤靛榻愶紙1.3.4 浼氳绯荤粺寮瑰吋瀹规€ц鍛婏級
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
