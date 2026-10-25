import java.io.ByteArrayOutputStream
import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ======================================================================
// 固定签名：本地编译和 GitHub Actions 共用同一把密钥
// ----------------------------------------------------------------------
// 首次构建时若项目根目录没有 noad.keystore，会自动用 JDK 自带的 keytool
// 生成一把固定参数的密钥（别名 noad / 密码 noad123）。
// 生成后该文件会保留在项目里并提交到仓库 → 所有来源编译出的 APK
// 签名一致，可以互相覆盖安装。
// ======================================================================
val noadKeystore: File = rootProject.file("noad.keystore")

if (!noadKeystore.exists()) {
    try {
        val isWindows = System.getProperty("os.name", "")
            .lowercase()
            .contains("windows")
        val keytoolInJdk = File(
            System.getProperty("java.home"),
            "bin/keytool" + if (isWindows) ".exe" else ""
        )
        val keytool = if (keytoolInJdk.exists()) keytoolInJdk.absolutePath else "keytool"
        exec {
            commandLine(
                keytool,
                "-genkeypair",
                "-keystore", noadKeystore.absolutePath,
                "-alias", "noad",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "30000",
                "-storepass", "noad123",
                "-keypass", "noad123",
                "-dname", "CN=NoAd, OU=NoAd, O=NoAd, L=Unknown, ST=Unknown, C=CN",
                "-noprompt"
            )
            workingDir = rootProject.projectDir
            standardOutput = ByteArrayOutputStream()
            errorOutput = ByteArrayOutputStream()
        }
        println("✅ 已生成固定签名密钥: ${noadKeystore.absolutePath}")
    } catch (e: Exception) {
        println("⚠️ 自动生成签名密钥失败，将回退到系统默认 debug 签名: ${e.message}")
    }
}

val hasFixedKeystore: Boolean = noadKeystore.exists()

android {
    namespace = "com.fatfish.noad"
    compileSdk = 34

    signingConfigs {
        create("fixed") {
            if (hasFixedKeystore) {
                storeFile = noadKeystore
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
        versionCode = 6
        versionName = "1.3.1"

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
            if (hasFixedKeystore) {
                signingConfig = signingConfigs.getByName("fixed")
            }
        }
        debug {
            isMinifyEnabled = false
            if (hasFixedKeystore) {
                signingConfig = signingConfigs.getByName("fixed")
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