package io.github.mame1839.codecanchor.core

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

// 目標曲線の標本点。プリアンプ・聴感重み・「高精度」へ送る曲線が、同じ1つの格子を引く。
//
// ⚠️ 定義の出どころは C++ 側 (dsp/ca_eq_curve.h の 3 定数)。Kotlin から C++ の定数は参照できない
// ので、ここは写し。一致は EqCurveGridTest がヘッダの本文を読んで突き合わせる (eq-shm-abi.md §1)。
// 値を動かすときは C++ 側を先に直すこと。刻みは必ず POINTS から出す (点数と区間数を別々に持たない)。
object EqCurveGrid {

    const val POINTS = 401
    const val MIN_HZ = 20.0
    const val MAX_HZ = 20_000.0

    // ⚠️ この幅を1点でも外れると .so は枠の更新を丸ごと捨てる (bands まで一緒に消える)。
    const val MAX_ABS_DB = 40.0

    private val LN_MIN = ln(MIN_HZ)
    private val LN_SPAN = ln(MAX_HZ / MIN_HZ)

    fun hzAt(i: Int): Double = exp(LN_MIN + LN_SPAN * i / (POINTS - 1))

    val HZ: DoubleArray = DoubleArray(POINTS) { hzAt(it) }

    fun nearestIndex(hz: Double): Int =
        ((ln(hz) - LN_MIN) / LN_SPAN * (POINTS - 1)).roundToInt().coerceIn(0, POINTS - 1)

    // 折れ線の頂点をここへ寄せると、標本化の損失が恒等的に0になる (線分が標本点を厳密に通る)。
    // biquad のバンド中心は動かさない — 動くのは送る曲線の頂点の位置だけ。
    fun snapHz(hz: Double): Double = hzAt(nearestIndex(hz))

    // グラフィックの摘みが表す折れ線を格子の上で読む。これが「高精度」へ送る曲線。
    // プリアンプは載せない (スカラで .so 側が IR とは別に掛ける。載せると動かすたびに組み直しが走る)。
    fun graphicCurveDb(bands: List<EqBand>, fs: Int = EqSolver.DEFAULT_FS): DoubleArray {
        if (bands.isEmpty()) return DoubleArray(POINTS)
        val vertices = knobPolyline(bands, fs)
        return DoubleArray(POINTS) { i ->
            AutoEqParser.interpolate(vertices, hzAt(i)).coerceIn(-MAX_ABS_DB, MAX_ABS_DB)
        }
    }

    // 摘みの折れ線の頂点 (周波数, dB)。送る側も描く側もここから引く。⚠️ 描く側も snapHz 後の
    // 値を使うこと (吸着前を描くと絵と音がずれる)。[AutoEqParser.interpolate] が昇順を要求する。
    fun knobPolyline(bands: List<EqBand>, fs: Int = EqSolver.DEFAULT_FS): List<Pair<Double, Double>> {
        val knobs = EqSolver.graphicTargetsDb10(bands, fs)
        return bands.mapIndexed { i, band ->
            snapHz(band.freqHz.toDouble()) to knobs[i].toDouble() / EqUnits.GAIN_SCALE
        }.sortedBy { it.first }
    }

    // caeq::curveValid と同じ検査。落ちる曲線を送らない (.so は1点でも外れたら bands まで
    // 一緒に捨てる)。ここで落ちるのは非有限が混ざったときだけ。
    fun valid(curveDb: DoubleArray): Boolean =
        curveDb.size == POINTS && curveDb.all { it.isFinite() && it >= -MAX_ABS_DB && it <= MAX_ABS_DB }

    // caeqset --curve に渡すファイルの中身。1 行 1 値の dB。周波数は書かない (格子の定義が
    // 2 箇所になる)。⚠️ 小数点は必ず "." — ロケール依存の書式化だと端末の言語だけで曲線が変わる。
    fun encode(curveDb: DoubleArray): String =
        buildString {
            for (v in curveDb) {
                append(EqParams.decimal(Math.round(v * CURVE_SCALE).toInt(), CURVE_SCALE))
                append('\n')
            }
        }

    // ファイルへ書き出すときの刻み。0.01 dB (摘みは 0.1 dB 刻みだが、折れ線の途中の値は
    // 補間で細かい端数になるので、摘みと同じ刻みだと標本点ごとに階段が乗る)。
    const val CURVE_SCALE = 100
}
