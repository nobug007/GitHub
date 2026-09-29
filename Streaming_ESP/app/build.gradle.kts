plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sf.streamingesp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sf.streamingesp"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    androidResources {
        // ONNX 모델은 이미 압축된 가중치라 APK 압축 이득이 거의 없고, 압축해두면 실행 시
        // 13MB를 통째로 풀어야 해서 첫 실행이 느려진다.
        noCompress += "onnx"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // YOLOv8-pose 추론 엔진. CPU(XNNPACK) 기준 태블릿에서 프레임당 60~150ms 수준.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")
}
