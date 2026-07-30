// AGP 9 は Kotlin を内蔵しており、同梱の KGP より新しい版を使うにはここで固定する。
// Compose コンパイラプラグインの版と一致させる必要がある。
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
