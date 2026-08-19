package io.github.mame1839.codecanchor.core

import kotlin.math.ln

sealed interface AutoEqResult {
    data class Ok(val settings: EqSettings) : AutoEqResult

    data class Error(val reason: Reason) : AutoEqResult

    enum class Reason {
        NOT_AUTOEQ,
        CORNER_SHELF,
        NO_BANDS,
    }
}

object AutoEqParser {

    private val FILTER_RE = Regex(
        """^Filter\s+\d+:\s*ON\s+(\w+)\s+Fc\s+([\d.]+)\s*Hz\s+Gain\s+(-?[\d.]+)\s*dB\s+Q\s+([\d.]+)""",
        RegexOption.IGNORE_CASE,
    )
    private val PREAMP_RE = Regex("""^Preamp:\s*(-?[\d.]+)\s*dB""", RegexOption.IGNORE_CASE)
    private val GRAPHIC_RE = Regex("""^GraphicEQ:\s*(.+)$""", RegexOption.IGNORE_CASE)

    private const val SHELF_MAX_Q = 0.7

    fun parse(text: String, bandCount: Int = 10): AutoEqResult {
        val count = EqSettings.normalizeBandCount(bandCount)
        text.lineSequence().forEach { line ->
            GRAPHIC_RE.find(line.trim())?.let { return parseGraphic(it.groupValues[1], count) }
        }
        return parseParametric(text, count)
    }

    private fun parseParametric(text: String, bandCount: Int): AutoEqResult {
        var preampDb: Double? = null
        val bands = mutableListOf<EqBand>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            PREAMP_RE.find(line)?.let { preampDb = it.groupValues[1].toDoubleOrNull() }
            val m = FILTER_RE.find(line) ?: return@forEach
            val typeText = m.groupValues[1].uppercase()
            val type = when (typeText) {
                "PK", "PEQ" -> EqBandType.PEAKING
                "LSC" -> EqBandType.LOW_SHELF
                "HSC" -> EqBandType.HIGH_SHELF
                "LS", "HS" -> return AutoEqResult.Error(AutoEqResult.Reason.CORNER_SHELF)
                else -> return@forEach
            }
            val fc = m.groupValues[2].toDoubleOrNull() ?: return@forEach
            val gain = m.groupValues[3].toDoubleOrNull() ?: return@forEach
            var q = m.groupValues[4].toDoubleOrNull() ?: return@forEach
            if (type != EqBandType.PEAKING) q = q.coerceAtMost(SHELF_MAX_Q)
            bands += EqBand(
                freqHz = fc.toInt().coerceIn(EqBand.FREQ_RANGE),
                q100 = (q * EqUnits.Q_SCALE).toInt().coerceIn(EqBand.Q_RANGE),
                gainDb10 = (gain * EqUnits.GAIN_SCALE).toInt().coerceIn(EqBand.GAIN_RANGE),
                type = type,
            )
        }
        if (bands.isEmpty()) {
            return AutoEqResult.Error(
                if (preampDb == null) AutoEqResult.Reason.NOT_AUTOEQ else AutoEqResult.Reason.NO_BANDS,
            )
        }
        val preamp = preampDb ?: 0.0
        return AutoEqResult.Ok(
            EqSettings(
                enabled = true,
                mode = EqMode.PARAMETRIC,
                bandCount = bandCount,
                bands = bands.take(EqSettings.MAX_BANDS),
                preampDb10 = (preamp * EqUnits.GAIN_SCALE).toInt()
                    .coerceIn(EqSettings.PREAMP_RANGE),
            ),
        )
    }

    private fun parseGraphic(body: String, bandCount: Int): AutoEqResult {
        val points = body.split(';').mapNotNull { entry ->
            val parts = entry.trim().split(Regex("\\s+"))
            if (parts.size < 2) return@mapNotNull null
            val hz = parts[0].toDoubleOrNull() ?: return@mapNotNull null
            val db = parts[1].toDoubleOrNull() ?: return@mapNotNull null
            hz to db
        }.sortedBy { it.first }
        if (points.isEmpty()) return AutoEqResult.Error(AutoEqResult.Reason.NOT_AUTOEQ)

        val freqs = EqSolver.centerFrequencies(bandCount)
        val fit = EqSolver.fitCurve(
            { hz -> interpolate(points, hz) },
            freqs,
            EqSolver.defaultQ(bandCount),
        )
        return AutoEqResult.Ok(
            EqSettings(
                enabled = true,
                mode = EqMode.GRAPHIC,
                bandCount = bandCount,
                bands = fit.bands,
                preampDb10 = Math.round(fit.offsetDb * EqUnits.GAIN_SCALE).toInt()
                    .coerceIn(EqSettings.PREAMP_RANGE),
            ),
        )
    }

    fun interpolate(points: List<Pair<Double, Double>>, hz: Double): Double {
        if (hz <= points.first().first) return points.first().second
        if (hz >= points.last().first) return points.last().second
        val i = points.indexOfFirst { it.first >= hz }
        val (f0, d0) = points[i - 1]
        val (f1, d1) = points[i]
        if (f1 == f0) return d1
        val t = (ln(hz) - ln(f0)) / (ln(f1) - ln(f0))
        return d0 + (d1 - d0) * t
    }
}
