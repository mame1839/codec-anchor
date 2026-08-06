package io.github.mame1839.codecanchor.core

import kotlin.math.ln

sealed interface AutoEqResult {
    data class Ok(val settings: EqSettings) : AutoEqResult

    /** reason は文字列リソースの ID ではなく列挙。文言はアプリ側で組み立てる。 */
    data class Error(val reason: Reason) : AutoEqResult

    enum class Reason {
        NOT_AUTOEQ, // どちらの形式にも読めない
        CORNER_SHELF, // LS / HS (corner frequency 扱い) は受け付けない
        NO_BANDS, // 形式は合っているがフィルタが 0 本
    }
}

object AutoEqParser {

    // AutoEQ が吐くのは LSC / PK / HSC。
    // LS / HS は EqualizerAPO では corner frequency 扱いで周波数がシフトするので別物。
    // 判定は BiQuadFilterFactory.cpp:197-198 の
    //   if (typeString[typeString.length() - 1] != L'C') isCornerFreq = true;
    // 末尾が C でないほうが corner。直感と逆なので、同一視すると取り込んだ曲線がずれる。
    private val FILTER_RE = Regex(
        """^Filter\s+\d+:\s*ON\s+(\w+)\s+Fc\s+([\d.]+)\s*Hz\s+Gain\s+(-?[\d.]+)\s*dB\s+Q\s+([\d.]+)""",
        RegexOption.IGNORE_CASE,
    )
    private val PREAMP_RE = Regex("""^Preamp:\s*(-?[\d.]+)\s*dB""", RegexOption.IGNORE_CASE)
    private val GRAPHIC_RE = Regex("""^GraphicEQ:\s*(.+)$""", RegexOption.IGNORE_CASE)

    // シェルフの Q の上限は AutoEQ の最適化の制約 (autoeq/constants.py:55 の
    // DEFAULT_SHELF_FILTER_MAX_Q = 0.7 "Shelf filters start to overshoot above 0.7")。
    // EqualizerAPO 側に上限は無いので、これは我々が守る側の規約。
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
        // Preamp: 行の値をそのまま使う。フィルタから再計算しない。
        // AutoEQ 自身が README.md 側では -(max_gain + 0.1) を出していて 0.1 dB 食い違うが、
        // 機械可読な ParametricEQ.txt の値を採る。
        val preamp = preampDb ?: 0.0
        return AutoEqResult.Ok(
            EqSettings(
                enabled = true,
                mode = EqMode.PARAMETRIC,
                // フィルタの本数はここに入れない。bandCount はグラフィックの選択肢
                // (5 / 10 / 15 / 31) しか取れず、外れた値は fromJson が 10 に書き換えて
                // hash の往復が壊れる。パラメトリックの本数は bands.size が持つ。
                bandCount = bandCount,
                bands = bands.take(EqSettings.MAX_BANDS),
                preampAuto = false,
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
        val target = DoubleArray(freqs.size) { interpolate(points, freqs[it].toDouble()) }

        // peaking は DC と Nyquist で必ず 0 dB になるので、広帯域のオフセットを作れない。
        // AutoEQ の目標カーブは全体が下にずれている (実例で平均 -6.89 dB) ので、
        // そのまま当てると最大誤差 9〜12 dB、バンドゲインが 28 dB まで暴れる。
        // オフセットはプリアンプに分離して、形だけをバンドに当てる。
        val offset = target.average()
        for (i in target.indices) target[i] -= offset

        val bands = EqSolver.solveBands(target, freqs, EqSolver.defaultQ(bandCount))
        // GraphicEQ.txt はプリアンプが曲線に焼き込んであるので、自動計算を掛けない。
        // 分離したオフセットだけをプリアンプに移す。
        return AutoEqResult.Ok(
            EqSettings(
                enabled = true,
                mode = EqMode.GRAPHIC,
                bandCount = bandCount,
                bands = bands,
                preampAuto = false,
                preampDb10 = (offset * EqUnits.GAIN_SCALE).toInt().coerceIn(EqSettings.PREAMP_RANGE),
            ),
        )
    }

    /**
     * 対数周波数上の線形補間、dB 線形。
     * EqualizerAPO (helpers/GainIterator.cpp の gainAt())、AutoEQ
     * (frequency_response.py の interpolate()、pol_order 既定 1)、JamesDSP
     * (jdsp/generalDSP/ArbFIRGen.c の gainAtLogGrid()) の 3 実装で一致している。
     * ノード範囲外は端の値を保持 (フラット) — これも 3 実装で一致。
     */
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
