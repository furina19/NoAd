plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.fatfish.noad"
    compileSdk = 34

    // ====== 固定签名：解决不同机器/CI 编译 APK 签名不同导致无法覆盖安装的问题 ======
    // 如果项目根目录存在 noad.keystore，就用它签名（保证每次编译签名一致）
    // 如果不存在（本地首次编译），回退到系统默认 debug 签名
    val keystoreFile = rootProject.file("noad.keystore")
    signingConfigs {
        create("fixed") {
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = "noad123"
                keyAlias = "noad"
                keyPassword = "noad123"
            }
        }
    }

    defaultConfig {
        applicationId = "com.fatfish.noad"
        minSdk = 24
        targetSdk = 34
        versionCode = 4
        versionName = "1.2.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 固定签名（如果 keystore 存在）
            val fixed = signingConfigs.findByName("fixed")
            if (fixed != null && fixed.storeFile?.exists() == true) {
                signingConfig = fixed
            }
        }
        debug {
            isMinifyEnabled = false
            // 固定签名（如果 keystore 存在）
            val fixed = signingConfigs.findByName("fixed")
            if (fixed != null && fixed.storeFile?.exists() == true) {
                signingConfig = fixed
            }
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
    // AndroidX 核心
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.preference:preference-ktx:1.2.1")

    // 协程（轻量异步）
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}