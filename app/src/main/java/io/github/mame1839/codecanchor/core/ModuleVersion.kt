package io.github.mame1839.codecanchor.core

object ModuleVersion {

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
}
