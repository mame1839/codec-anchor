package io.github.mame1839.codecanchor.core

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * 目標曲線の標本点。**プリアンプ・聴感重み・「高精度」へ送る曲線が、同じ 1 つの格子を引く。**
 *
 * ### ⚠️ 定義の出どころは C++ 側
 *
 * `app/src/main/cpp/dsp/ca_eq_curve.h` の 3 定数 (`kCurvePoints` / `kCurveMinHz` /
 * `kCurveMaxHz`) が唯一の定義で、共有メモリの枠の並び (`ca_eq_slot_t.curve_db` の長さと
 * `ca_shm_t.curve_points`) もそこから算術で決まる。**Kotlin から C++ の定数は参照できない**
 * ので、ここは写しであり、一致は `EqCurveGridTest` がヘッダの本文を読んで突き合わせている。
 * 値を動かすときは C++ 側を先に直すこと。
 *
 * ### 格子の形を 2 つのリテラルで持たない
 *
 * 「点数」と「区間数」を別々に書くと片方だけ動いて、**点数はそのままで刻みだけずれる**
 * (あるいはその逆) が起きる。刻みは必ず [POINTS] から出す。
 *
 * ### 補間の規則も契約
 *
 * 標本と標本のあいだは **(ln f, dB) の線形**、範囲外は端の値で平坦 —
 * [AutoEqParser.interpolate] と `caeq::curveDbAt` が同じ規則で、EqualizerAPO / AutoEQ /
 * JamesDSP の 3 実装とも一致している。ここで別の規則を使うと、送った曲線と鳴る曲線が
 * 静かにずれる。
 */
object EqCurveGrid {

    /** `caeq::kCurvePoints`。 */
    const val POINTS = 401

    /** `caeq::kCurveMinHz`。 */
    const val MIN_HZ = 20.0

    /** `caeq::kCurveMaxHz`。 */
    const val MAX_HZ = 20_000.0

    /**
     * `caeq::kCurveMaxAbsDb`。**この幅を 1 点でも外れると `.so` は枠の更新を丸ごと捨てる**
     * (bands まで一緒に消える) ので、送る前にここへ収める。
     */
    const val MAX_ABS_DB = 40.0

    private val LN_MIN = ln(MIN_HZ)
    private val LN_SPAN = ln(MAX_HZ / MIN_HZ)

    /** [i] 番目の標本点の周波数。両端が厳密に [MIN_HZ] / [MAX_HZ] に乗る。 */
    fun hzAt(i: Int): Double = exp(LN_MIN + LN_SPAN * i / (POINTS - 1))

    /** 全標本点。評価をくり返す側 (プリアンプ・聴感重み) はこちらを引く。 */
    val HZ: DoubleArray = DoubleArray(POINTS) { hzAt(it) }

    /** [hz] にいちばん近い標本点の番号。範囲外は端に丸める。 */
    fun nearestIndex(hz: Double): Int =
        ((ln(hz) - LN_MIN) / LN_SPAN * (POINTS - 1)).roundToInt().coerceIn(0, POINTS - 1)

    /**
     * [hz] を最寄りの標本点へ吸着させる。
     *
     * **折れ線の頂点をここへ寄せると、標本化の損失が恒等的に 0 になる** — 線分が標本点を
     * 厳密に通るので、角 (バンド中心) が標本と標本のあいだに落ちて丸まることがなくなる。
     * 丸まりは隣り合う摘みが逆向きに振れた形で効き、±12 dB のジグザグで 0.9 dB 級。
     * ずれは最大で半ステップ = 0.87 % ≈ 15 cent。**biquad のバンド中心は動かさない**
     * ([EqSolver.centerFrequencies] は ISO 266 の表で、Q の実測がその周波数に対して
     * 取ってある) ので、動くのは送る曲線の頂点の位置だけ。
     */
    fun snapHz(hz: Double): Double = hzAt(nearestIndex(hz))

    /**
     * グラフィックの摘みが表す折れ線を、格子の上で読む。**これが「高精度」へ送る曲線。**
     *
     * 頂点は (バンド中心, その中心で鳴る音量) で、後者は摘みの値そのもの
     * ([EqSolver.graphicTargetsDb10])。**biquad の合成応答ではない** — 摘みの値こそが
     * ユーザの入力で、中心と中心のあいだの起伏は biquad の都合 (eq-fir-design.md §1)。
     *
     * **プリアンプは載せない。**あれはスカラで、`.so` 側が IR とは別に掛ける
     * (載せるとプリアンプを動かすたびに FIR の組み直しが走る)。
     *
     * 範囲外 (20 Hz 未満・20 kHz 超) は端の値で平坦に延びる。値は [MAX_ABS_DB] に収める —
     * 摘みは ±12 dB なので通常は当たらないが、手書きのプリセットは当たりうる。
     */
    fun graphicCurveDb(bands: List<EqBand>, fs: Int = EqSolver.DEFAULT_FS): DoubleArray {
        if (bands.isEmpty()) return DoubleArray(POINTS)
        val knobs = EqSolver.graphicTargetsDb10(bands, fs)
        val vertices = bands.mapIndexed { i, band ->
            snapHz(band.freqHz.toDouble()) to knobs[i].toDouble() / EqUnits.GAIN_SCALE
        }.sortedBy { it.first }
        return DoubleArray(POINTS) { i ->
            AutoEqParser.interpolate(vertices, hzAt(i)).coerceIn(-MAX_ABS_DB, MAX_ABS_DB)
        }
    }

    /**
     * `caeq::curveValid` と同じ検査。**落ちる曲線を送らない** — `.so` は 1 点でも外れたら
     * 枠の更新を丸ごと捨てるので、bands まで一緒に消える。
     *
     * [graphicCurveDb] は値を丸めて返すので、ここで落ちるのは非有限が混ざったときだけ。
     * その曲線は直しようがないので、呼び手は曲線を送らずに biquad のまま鳴らすこと。
     */
    fun valid(curveDb: DoubleArray): Boolean =
        curveDb.size == POINTS && curveDb.all { it.isFinite() && it >= -MAX_ABS_DB && it <= MAX_ABS_DB }

    /**
     * `caeqset --curve` に渡すファイルの中身。**1 行 1 値の dB を [POINTS] 行。周波数は書かない**
     * (書くと格子の定義が 2 箇所になる。読み手は `ca_eq_curve_io.h`)。
     *
     * 小数点は必ず `.`。ロケール依存の書式化を通すと、端末の言語設定によっては "1,5" になり
     * `atof` がそこで読むのをやめる — **端末の言語でだけ曲線が変わる**という追えない形になる。
     */
    fun encode(curveDb: DoubleArray): String =
        buildString {
            for (v in curveDb) {
                append(EqParams.decimal(Math.round(v * CURVE_SCALE).toInt(), CURVE_SCALE))
                append('\n')
            }
        }

    /**
     * ファイルへ書き出すときの刻み。0.01 dB。
     *
     * 摘みは 0.1 dB 刻みだが、折れ線の**途中**の値は補間で細かい端数になるので、
     * 摘みと同じ刻みにすると標本点ごとに最大 0.05 dB の階段が乗る。
     * `.so` 側は f32 で持つので、この刻みで落ちる情報は無い。
     */
    const val CURVE_SCALE = 100
}
