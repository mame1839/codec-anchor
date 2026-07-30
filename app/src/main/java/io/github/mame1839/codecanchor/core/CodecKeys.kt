package io.github.mame1839.codecanchor.core

object CodecKeys {
    const val KEEP_INT = -1
    const val KEEP_LONG = -1L
    const val KEEP_MASK = 0

    const val SAMPLE_RATE_44100 = 0x1
    const val SAMPLE_RATE_48000 = 0x2
    const val SAMPLE_RATE_88200 = 0x4
    const val SAMPLE_RATE_96000 = 0x8
    const val SAMPLE_RATE_176400 = 0x10
    const val SAMPLE_RATE_192000 = 0x20

    const val BITS_PER_SAMPLE_16 = 0x1
    const val BITS_PER_SAMPLE_24 = 0x2
    const val BITS_PER_SAMPLE_32 = 0x4

    const val CHANNEL_MODE_MONO = 0x1
    const val CHANNEL_MODE_STEREO = 0x2

    const val CODEC_PRIORITY_HIGHEST = 1_000_000

    const val OPTIONAL_CODECS_SUPPORTED = 1
    const val OPTIONAL_CODECS_PREF_ENABLED = 1

    const val CODEC_TYPE_SBC = 0

    val SAMPLE_RATES: List<Pair<Int, String>> = listOf(
        SAMPLE_RATE_44100 to "44.1 kHz",
        SAMPLE_RATE_48000 to "48 kHz",
        SAMPLE_RATE_88200 to "88.2 kHz",
        SAMPLE_RATE_96000 to "96 kHz",
        SAMPLE_RATE_176400 to "176.4 kHz",
        SAMPLE_RATE_192000 to "192 kHz",
    )

    val BIT_DEPTHS: List<Pair<Int, String>> = listOf(
        BITS_PER_SAMPLE_16 to "16 bit",
        BITS_PER_SAMPLE_24 to "24 bit",
        BITS_PER_SAMPLE_32 to "32 bit",
    )

    // ラベルは言語に依存しない表記に留める。説明文の翻訳は UI 側のリソースで行う。
    val CHANNEL_MODES: List<Pair<Int, String>> = listOf(
        CHANNEL_MODE_MONO to "Mono",
        CHANNEL_MODE_STEREO to "Stereo",
    )

    val SAMPLE_RATE_BITS: Int = orOf(SAMPLE_RATES)
    val BIT_DEPTH_BITS: Int = orOf(BIT_DEPTHS)
    val CHANNEL_MODE_BITS: Int = orOf(CHANNEL_MODES)

    const val LDAC_ABR = 1003L

    val LDAC_QUALITIES: List<Pair<Long, String>> = listOf(
        1000L to "990/909 kbps",
        1001L to "660/606 kbps",
        1002L to "330/303 kbps",
        LDAC_ABR to "ABR",
    )

    val FALLBACK_CODEC_NAMES: Map<Int, String> = mapOf(
        0 to "SBC",
        1 to "AAC",
        2 to "aptX",
        3 to "aptX HD",
        4 to "LDAC",
        5 to "LC3",
        6 to "Opus",
    )

    private fun orOf(table: List<Pair<Int, String>>): Int = table.fold(0) { acc, entry -> acc or entry.first }

    fun prettifyConstant(constantName: String): String {
        val bare = constantName.removePrefix("SOURCE_CODEC_TYPE_")
        return when (bare) {
            "SBC", "AAC", "LDAC", "LC3", "CELT", "MIHC" -> bare
            "OPUS" -> "Opus"
            "APTX" -> "aptX"
            "APTX_HD" -> "aptX HD"
            "APTX_ADAPTIVE" -> "aptX Adaptive"
            "APTX_TWSP" -> "aptX TWS+"
            "LHDC_LEA" -> "LHDC LE Audio"
            else -> if (bare.startsWith("LHDCV")) "LHDC V" + bare.removePrefix("LHDCV") else bare.replace('_', ' ')
        }
    }

    // 端末から名前が取れなかったときの表示名。言語に依存しない表記に留める。
    fun fallbackName(codecType: Int): String = FALLBACK_CODEC_NAMES[codecType] ?: "Codec $codecType"

    fun label(table: List<Pair<Int, String>>, mask: Int): String =
        table.firstOrNull { it.first == mask }?.second ?: "0x${mask.toString(16)}"

    fun options(table: List<Pair<Int, String>>, capability: Int): List<Pair<Int, String>> =
        if (capability == 0) table else table.filter { it.first and capability != 0 }

    fun maskToLabels(table: List<Pair<Int, String>>, mask: Int): String =
        table.filter { it.first and mask != 0 }.joinToString(" / ") { it.second }.ifEmpty { "-" }

    fun ldacQualityLabel(value: Long): String =
        LDAC_QUALITIES.firstOrNull { it.first == value }?.second ?: value.toString()

    fun isLdac(codecName: String?): Boolean = codecName?.contains("LDAC", ignoreCase = true) == true
}
