plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sf.streamview"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sf.streamview"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        ndk {
            // libVLC 은 ABI 마다 네이티브 라이브러리를 싣는다. 전부 담으면 APK 가 수백 MB 로
            // 불어나므로 실제로 쓰는 태블릿의 ABI 하나만 남긴다.
            abiFilters += listOf("arm64-v8a")
        }
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
    // RTSP 재생. ESP32 는 MJPEG 를 RTP 로 실어 보내는데, ExoPlayer/Media3 의 RTSP 는 MJPEG
    // 페이로드를 지원하지 않는다. libVLC 는 지원한다.
    implementation("org.videolan.android:libvlc-all:3.6.0")
}
