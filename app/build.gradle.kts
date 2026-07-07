plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.eyecontrol"
    compileSdk = 36

    defaultConfig {
        applicationId = "tw.com.zhu.eyecontrol"   // Play 上架識別碼（namespace 維持原程式碼包名即可）
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    // MediaPipe 的 .task 模型是壓縮格式，打包時不可再壓縮，否則載入失敗。
    androidResources {
        noCompress += "task"
    }
}

dependencies {
    // CameraX：前鏡頭串流（1.4.2+ 的原生庫已對齊 16KB 分頁）
    val cameraxVersion = "1.4.2"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // MediaPipe Tasks Vision：Face Landmarker（含虹膜）；0.10.26 起原生庫以 16KB 分頁對齊編譯
    implementation("com.google.mediapipe:tasks-vision:0.10.26")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
}
