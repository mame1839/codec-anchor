package io.github.mame1839.codecanchor.core

enum class ModuleVersionState {
    UNKNOWN,
    MATCHED,
    MISMATCHED,
}

object ModuleVersion {

    const val PROPERTY_CODE = "ro.codecanchor.module_version"

    const val PROPERTY_SEMVER = "ro.codecanchor.module_semver"

    private const val GETPROP = "/system/bin/getprop"

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

    fun compare(moduleVersionCode: String, appVersionCode: Int): ModuleVersionState {
        val code = moduleVersionCode.trim().toIntOrNull() ?: return ModuleVersionState.UNKNOWN
        return if (code == appVersionCode) ModuleVersionState.MATCHED else ModuleVersionState.MISMATCHED
    }
}
