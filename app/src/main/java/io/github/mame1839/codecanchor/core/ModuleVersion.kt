package io.github.mame1839.codecanchor.core

/**
 * 音響処理モジュール (root モジュール) とアプリの版の突き合わせ。
 *
 * **StatusReport.moduleVersion とは別物。**あちらは Bluetooth プロセスで動いているフックの版で、
 * こちらは `/vendor/lib64/soundfx/libcaeq.so` と `audio_effects.xml` を入れる root モジュールの版。
 * 2 つは別々に更新されるので、片方だけ古い状態が普通に起きる。
 */
enum class ModuleVersionState {
    /** プロパティが読めない。モジュールが入っていないか、版を出さない古いモジュール。 */
    UNKNOWN,
    MATCHED,
    MISMATCHED,
}

object ModuleVersion {

    /**
     * versionCode (整数)。突き合わせに使う。
     *
     * `/data/adb` は**アプリから読めない**ので module.prop を直接見る経路は無く、モジュールが
     * system.prop で出すこのプロパティが唯一の経路。書式は module/version.sh の
     * `ca_system_prop` が決めていて、module/test/module_test.sh の 19 番が固定している。
     */
    const val PROPERTY_CODE = "ro.codecanchor.module_version"

    /** versionName (semver)。**表示にだけ使う。**突き合わせには使わない (下の compare を参照)。 */
    const val PROPERTY_SEMVER = "ro.codecanchor.module_semver"

    private const val GETPROP = "/system/bin/getprop"

    /**
     * プロパティを読む。読めなければ空文字。**プロセスの生存中に変わらないので、呼ぶのは 1 回だけ。**
     *
     * `android.os.SystemProperties.get` をリフレクションで呼ぶ手もあり、Android 16 / targetSdk 36 /
     * HyperOS 3 の実機では通ることも確かめた (2026-08-07)。**それでも採らない** — 非 SDK の API は
     * 版ごとに扱いが変わり、塞がれたときに飛ぶのは例外だけで**「値が無い」と区別が付かない。**
     * 版ずれの警告が黙って出なくなる形で壊れるので、気づく手立てが無い (lint も PrivateApi で止める)。
     *
     * exec の実測は 1 回あたり約 13 ms (同端末)。init で 1 回だけなので、
     * 同じ場所で走っている設定ファイルの読み込みや queryEffects() と同じ桁に収まる。
     */
    fun read(key: String): String = runCatching {
        val process = ProcessBuilder(GETPROP, key).redirectErrorStream(true).start()
        try {
            val out = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            out.trim()
        } finally {
            process.destroy()
        }
    }.getOrDefault("")

    /**
     * 突き合わせは **versionCode (整数) で行う。**
     *
     * semver を使わないのは、debug ビルドの versionName に `-debug` が付いて、同じ版でも
     * 必ず食い違うため。versionCode は build.gradle.kts / release.yml / module/version.sh の
     * 3 つが同じ式 (major * 10000 + minor * 100 + patch、下限 1) で、タグ 1 つから出ている。
     *
     * 整数として読めない値は MISMATCHED ではなく UNKNOWN。読めない値からは
     * 「ずれている」と言い切れないので、何も出さないほうを選ぶ。
     */
    fun compare(moduleVersionCode: String, appVersionCode: Int): ModuleVersionState {
        val code = moduleVersionCode.trim().toIntOrNull() ?: return ModuleVersionState.UNKNOWN
        return if (code == appVersionCode) ModuleVersionState.MATCHED else ModuleVersionState.MISMATCHED
    }
}
