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

// 版は release.yml がタグから -PversionName / -PversionCode で渡す。既定値は debug とローカル用なので、
// プロパティが渡されているのに読めないときは既定値に落とさず失敗させる。
val versionNameProp = (findProperty("versionName") as String?)?.trim()
val versionCodeProp = (findProperty("versionCode") as String?)?.trim()
if (versionNameProp != null && versionNameProp.isEmpty()) {
    error("versionName プロパティが空")
}
if (versionCodeProp != null && versionCodeProp.toIntOrNull() == null) {
    error("versionCode プロパティが整数でない: \"$versionCodeProp\"")
}

// プロパティが無いローカルビルドは直近のタグに合わせる。literal を書くとタグと二重管理になり、
// 実機に入れたビルドの版が古いまま表示される。
val latestTag: String = providers.exec {
    commandLine("git", "describe", "--tags", "--abbrev=0")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().removePrefix("v") }.orNull
    ?.takeIf { Regex("^\\d+\\.\\d+\\.\\d+$").matches(it) } ?: "0.0.0"

// release.yml と同じ式にする (major * 10000 + minor * 100 + patch)。
fun versionCodeOf(name: String): Int {
    val parts = name.split(".").map { it.toInt() }
    return parts[0] * 10_000 + parts[1] * 100 + parts[2]
}

android {
    namespace = "io.github.mame1839.codecanchor"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.mame1839.codecanchor"
        minSdk = 31
        targetSdk = 36
        // タグが取れない環境 (浅い clone、アーカイブ展開) では 0 になるが、0 は AGP が受け付けない
        versionCode = versionCodeProp?.toInt() ?: versionCodeOf(latestTag).coerceAtLeast(1)
        versionName = versionNameProp ?: latestTag

        externalNativeBuild {
            cmake {
                // 絞るのはネイティブのビルドだけ。ndk { abiFilters } にすると APK 全体の対応 ABI が
                // 減り、依存の AAR が持つ .so まで落ちてアプリ自体が非 arm64 端末に入らなくなる。
                // EQ が使えない端末では、アプリは入ったうえで理由を出す。
                abiFilters += "arm64-v8a"
                arguments += listOf("-DANDROID_STL=c++_static", "-DANDROID_PLATFORM=android-31")
            }
        }
    }

    ndkVersion = "29.0.14206865"

    // .so の置き先は /vendor/lib64/soundfx だけなので、他の ABI をビルドしても置き場が無い。
    // ANDROID_STL=c++_static は必須 — libc++_shared.so を vendor の namespace から解決できず、
    // 依存すると dlopen が黙って失敗する。static なら DT_NEEDED に現れない。
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    signingConfigs {
        if (keystoreFile.exists()) {
            create("release") {
                storeFile = keystoreFile
                storePassword = secret("CA_STORE_PASSWORD")
                keyAlias = secret("CA_KEY_ALIAS", "codecanchor")
                keyPassword = secret("CA_KEY_PASSWORD")
                // v3 が無いと将来の鍵ローテーションができない。片方だけ指定すると
                // もう片方が既定値の false に落ちるので v2 も明示する。
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            // 未使用コードの削除だけ行う。クラス名は proguard-rules.pro の -dontobfuscate で保つ
            // (Xposed はエントリクラスを名前で読み込む)
            isMinifyEnabled = true
            isShrinkResources = true
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

    // org.json は android.jar ではスタブなので、単体テストは Robolectric で実機の実装を載せる。
    // isReturnDefaultValues は付けない — 付けると例外の代わりに全メソッドが 0 / null を返し、
    // hash() の往復テストが「何も検証していないのに緑」になる。
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    // 既存の指摘は lint-baseline.xml に記録済み。新しい指摘だけ落とす。
    lint {
        baseline = file("lint-baseline.xml")
        warningsAsErrors = true
        // 上流が新しい版を公開した日に、こちらのコードが 1 行も変わっていないのにビルドが落ちる
        // 検査。**再現性が無い**ので外す。版を上げるのは意図してやる作業で、CI に催促させない。
        // baseline に逃がさないのは、baseline が「いま出ている指摘」を固定するものだから —
        // 次の版が出れば新しい指摘として素通りしてしまい、抑止にならない。
        disable += setOf("AndroidGradlePluginVersion", "NewerVersionAvailable", "GradleDependency")
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/*.version")
        // **APK に載せた実行ファイル (libcaeqset.so) を su から起動するので、展開させる。**
        // AGP の既定は useLegacyPackaging = false で、マニフェストに
        // android:extractNativeLibs="false" が入る。その場合 .so は APK の中に置かれたままで、
        // **nativeLibraryDir にファイルが 1 つも現れない** — リンカは apk!/lib/... の形で
        // dlopen できるが、**exec はできない。**EQ の値を書く経路が
        // 「No such file or directory」で死ぬ。
        // 退路も無い: アプリの files/ へ複製して実行する形は、Android 10 以降の W^X で
        // 「アプリが書ける場所からの exec」が塞がれているため通らない。
        jniLibs.useLegacyPackaging = true
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

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
