package io.github.mame1839.codecanchor.core

object EqFir {

    const val LINE_TAG = "CA_EQ_FIR"

    const val WHY_KEY = "why"

    const val NOT_ADDRESSABLE = "not_addressable"

    const val NO_ARENA = "no_arena"

    const val BLOCK_UNFIT = "block_unfit"

    val REPORTABLE = setOf(NOT_ADDRESSABLE, NO_ARENA, BLOCK_UNFIT)

    fun whyOf(stdout: String): String? {
        var why: String? = null
        for (raw in stdout.lineSequence()) {
            val tokens = raw.trim().split(WHITESPACE)
            if (tokens.firstOrNull() != LINE_TAG) continue
            why = fieldsOf(tokens)[WHY_KEY]
        }
        return why
    }

    fun fellBackToStandard(stdout: String): Boolean = whyOf(stdout) in REPORTABLE

    private fun fieldsOf(tokens: List<String>): Map<String, String> =
        tokens.asSequence().drop(1).mapNotNull { field ->
            val cut = field.indexOf('=')
            if (cut <= 0) null else field.substring(0, cut) to field.substring(cut + 1)
        }.toMap()

    private val WHITESPACE = Regex("""\s+""")
}
