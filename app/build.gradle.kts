import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// 署名鍵は keystore/release.jks (git 管理外)。CI では secret から復元する。
// 鍵が無い環境では release も自動的に未署名ビルドになる。
val keystoreFile = rootProject.file("keystore/release.jks")
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore/release.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun secret(name: String, default: String = ""): String =
    keystoreProps.getProperty(name) ?: System.getenv(name) ?: default

android {
    namespace = "io.github.mame1839.codecanchor"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.mame1839.codecanchor"
        minSdk = 31
        targetSdk = 36
        versionCode = (findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (findProperty("versionName") as String?) ?: "0.1.0"
    }

    signingConfigs {
        if (keystoreFile.exists()) {
            create("release") {
                storeFile = keystoreFile
                storePassword = secret("CA_STORE_PASSWORD")
                keyAlias = secret("CA_KEY_ALIAS", "codecanchor")
                keyPassword = secret("CA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // フックのエントリクラスと Xposed API 呼び出しを壊さないため難読化はしない
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreFile.exists()) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/*.version")
    }
}

dependencies {
    // Xposed API は実行時に LSPosed が提供するので APK には含めない
    compileOnly(files("libs/xposed-api-82.jar"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling.preview)
}
