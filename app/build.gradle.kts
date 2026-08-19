import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val keystoreFile = rootProject.file("keystore/release.jks")
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore/release.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun secret(name: String, default: String = ""): String =
    keystoreProps.getProperty(name) ?: System.getenv(name) ?: default

val versionNameProp = (findProperty("versionName") as String?)?.trim()
val versionCodeProp = (findProperty("versionCode") as String?)?.trim()
if (versionNameProp != null && versionNameProp.isEmpty()) {
    error("versionName プロパティが空")
}
if (versionCodeProp != null && versionCodeProp.toIntOrNull() == null) {
    error("versionCode プロパティが整数でない: \"$versionCodeProp\"")
}

val latestTag: String? = providers.exec {
    commandLine("git", "describe", "--tags", "--abbrev=0", "--match", "v[0-9]*")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().removePrefix("v") }.orNull
    ?.takeIf { Regex("^\\d+\\.\\d+\\.\\d+$").matches(it) }

val resolvedVersionName: String = versionNameProp ?: latestTag
    ?: error("版が決まらない: v<major>.<minor>.<patch> のタグが読めず -PversionName も無い")

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
        versionCode = versionCodeProp?.toInt() ?: versionCodeOf(resolvedVersionName).coerceAtLeast(1)
        versionName = resolvedVersionName

        externalNativeBuild {
            cmake {
                abiFilters += "arm64-v8a"
                arguments += listOf("-DANDROID_STL=c++_static", "-DANDROID_PLATFORM=android-31")
            }
        }
    }

    ndkVersion = "29.0.14206865"

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
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
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

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    lint {
        baseline = file("lint-baseline.xml")
        warningsAsErrors = true
        disable += setOf("AndroidGradlePluginVersion", "NewerVersionAvailable", "GradleDependency")
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/*.version")
        jniLibs.useLegacyPackaging = true
    }
}

tasks.withType<Test>().configureEach {
    inputs.dir(layout.projectDirectory.dir("src/main/cpp"))
        .withPropertyName("nativeSourcesReadByTests")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

dependencies {
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
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
