package io.github.mame1839.codecanchor.core

// root モジュールとアプリの版の突き合わせ。StatusReport.moduleVersion (フックの版) とは別物 —
// こちらは libcaeq.so と audio_effects.xml を入れる root モジュールの版で、別々に更新される。
enum class ModuleVersionState {
    UNKNOWN,
    MATCHED,
    MISMATCHED,
}

object ModuleVersion {

    // 書式は module/version.sh の ca_system_prop が決め、module_test.sh 19 番が固定している。
    const val PROPERTY_CODE = "ro.codecanchor.module_version"

    // 表示にだけ使う。突き合わせには使わない (下の compare を参照)。
    const val PROPERTY_SEMVER = "ro.codecanchor.module_semver"

    private const val GETPROP = "/system/bin/getprop"

    // ⚠️ SystemProperties.get のリフレクションは採らない — 非 SDK API は版ごとに扱いが変わり、
    // 塞がれたときに「値が無い」と区別が付かず、版ずれの警告が黙って出なくなる。
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
