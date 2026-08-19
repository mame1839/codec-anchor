package io.github.mame1839.codecanchor.core

import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * 目隠し A/B (好みの EQ 探索) の音量等価 (eq-finder-design.md §1)。
 *
 * わずかに大きい方が良く聞こえるバイアスは 0.1〜0.25 dB 差でも効くので、候補ごとに
 * プリアンプを「聴感で同じ大きさになる値」に揃える。[EqSolver.autoPreampDb10] は
 * ピーク基準 (クリップ防止が目的) なのでここには使えない — 低域シェルフ +6 dB の候補は
 * 中域が丸ごと −6 dB され、好みと無関係にフラット側が勝ち続ける。
 *
 * ### 揃える先は「土台の音量」で、絶対的な静けさではない
 *
 * preamp_c = [baseLevelDb] − L_c。[baseLevelDb] は探索を始めた設定が普段鳴っている
 * 聴感レベルなので、**オーバーレイが全部 0 の候補は普段ちょうどそのままの音量**になる。
 * 引く量が全候補共通の定数だけ ([baseLevelDb]) 動く形なので、候補どうしの公平性は変わらない。
 *
 * **クリップ余地を確保するための共通トリムは持たない。**常用経路で自動プリアンプを外した
 * (= クリップを保証しないと決めた) 以上、探索だけがその保証のために約 12 dB 払うのは
 * 筋が通らない。聴感レベルは土台のままなので音量は変わらず、動くのはピークだけ。
 *
 * 全部ランタイムの純関数で、設定 (Config / DeviceProfile) に入るのは
 * [preampDb10] が返す db10 整数だけ。
 */
object EqLoudness {

    /**
     * 評価グリッド。**定義は [EqCurveGrid] ただ 1 箇所** — プリアンプ
     * ([EqSolver.autoPreampDb10]) も「高精度」へ送る曲線も同じ格子を引く。
     * ここで作り直すと、同じ格子が 3 箇所に散る。
     */
    val GRID_HZ: DoubleArray = EqCurveGrid.HZ

    /** スペクトルの床。最大値からこれより下と非有限は床に置き換え、ゼロ和・NaN を作らない。 */
    private const val SPECTRUM_FLOOR_DOWN_DB = 100.0

    // ITU-R BS.1770-4 の K 特性 2 段の係数 (fs = 48 kHz、a0 = 1 に正規化済みの表の値)。
    //   段 1: プリフィルタ (頭部の音響効果を模した高域シェルフ、高域で約 +4 dB)
    //   段 2: RLB ハイパス (低域の聴感減衰)
    // 題材の fs に依らず常にこの 48 kHz の形で評価する。相対重みの用途では
    // 44.1/48 kHz の形の差は無視できる大きさで、fs ごとの係数再設計に見合わない。
    private const val K_FS = 48_000.0
    private const val PRE_B0 = 1.53512485958697
    private const val PRE_B1 = -2.69169618940638
    private const val PRE_B2 = 1.19839281085285
    private const val PRE_A1 = -1.69065929318241
    private const val PRE_A2 = 0.73248077421585
    private const val RLB_B0 = 1.0
    private const val RLB_B1 = -2.0
    private const val RLB_B2 = 1.0
    private const val RLB_A1 = -1.99004745483398
    private const val RLB_A2 = 0.99007225036621

    // 1 kHz 正規化の基準。生の K ゲインは 1 kHz で +0.698 dB (LKFS 定義の −0.691 定数に対応)。
    private val kRawDbAt1kHz = kRawDb(1_000.0)

    /** K 特性 (ITU-R BS.1770) の相対重み dB。1 kHz で 0 に正規化。定義域は 0 < hz < 24 kHz。 */
    fun kWeightingDb(hz: Double): Double = kRawDb(hz) - kRawDbAt1kHz

    // ⚠️ object の val は宣言順に初期化される。この 2 つは kRawDbAt1kHz / GRID_HZ より後に置く。
    private val kDbOnGrid = DoubleArray(GRID_HZ.size) { kWeightingDb(GRID_HZ[it]) }
    private val pinkPsdDb = DoubleArray(GRID_HZ.size) { -10.0 * log10(GRID_HZ[it]) }

    /**
     * 題材が無いときの既定重み: ピンクノイズ (パワー密度 ∝ 1/f) × K 特性。線形パワー。
     *
     * 実装はピンクの PSD を [weightsFromSpectrumDb] に通すだけ (重みの作り方を 1 本に保つ)。
     * 対数グリッドでは帯域幅補正 ×f と密度 1/f が打ち消し合うので、結果は
     * 10^(K/10) × 定数 (= 等オクターブエネルギーに K を掛けた形) になる。
     */
    fun defaultWeights(): DoubleArray = weightsFromSpectrumDb(pinkPsdDb)

    /**
     * 実測平均パワースペクトル ([GRID_HZ] 上、dB) に K 特性を掛けた線形パワーの重み。
     *
     * [spectrumDb] は**パワースペクトル密度 (per Hz) の dB**。基準は任意 —
     * 全点に同じ定数を足しても結果は変わらない ([perceivedGainDb] は重みの比しか見ない)。
     *
     * ### ×GRID_HZ (帯域幅補正) を消さないこと
     *
     * 近似したいのは K 重み付きパワーの積分 ∫ PSD·10^(K/10)·10^(R/10) df。対数等間隔
     * グリッドでは 1 点が受け持つ帯域幅が f に比例するので、密度 × f が点の重みになる。
     * ここを消すと低域が帯域幅の分だけ過大評価され、仕様 (eq-finder-design.md §1) の例
     * 「低域シェルフ +6 dB の聴感は +0.7 dB 程度」が +3.5 dB に化けて例と矛盾する
     * (EqLoudnessTest が数値で固定している)。
     *
     * 床 (最大値 −100 dB) より下と非有限 (NaN / ±Inf) は床に置き換えるので、無音の帯域が
     * あってもゼロ和や NaN にならない。全点が非有限なら題材なしと同じ扱いで [defaultWeights]。
     */
    fun weightsFromSpectrumDb(spectrumDb: DoubleArray): DoubleArray {
        require(spectrumDb.size == GRID_HZ.size) {
            "スペクトルは GRID_HZ と同じ ${GRID_HZ.size} 点であること (実際: ${spectrumDb.size})"
        }
        var maxDb = Double.NEGATIVE_INFINITY
        for (s in spectrumDb) if (s.isFinite() && s > maxDb) maxDb = s
        if (maxDb == Double.NEGATIVE_INFINITY) return defaultWeights()
        val floorDb = maxDb - SPECTRUM_FLOOR_DOWN_DB
        return DoubleArray(GRID_HZ.size) { i ->
            val s = spectrumDb[i]
            val clamped = if (s.isFinite()) max(s, floorDb) else floorDb
            10.0.pow((clamped - maxDb + kDbOnGrid[i]) / 10.0) * GRID_HZ[i]
        }
    }

    /**
     * 聴感ゲイン L_c = 10·log10( Σ w·10^(R/10) / Σ w )。R は [EqSolver.combinedResponseDb]。
     *
     * 候補のプリアンプを −L_c に揃えると、重みが表す題材で聴感上同じ大きさになる。
     * 全バンド 0 dB なら厳密に 0.0 を返す。
     */
    fun perceivedGainDb(
        bands: List<EqBand>,
        weights: DoubleArray,
        fs: Int = EqSolver.DEFAULT_FS,
    ): Double {
        require(weights.size == GRID_HZ.size) {
            "重みは GRID_HZ と同じ ${GRID_HZ.size} 点であること (実際: ${weights.size})"
        }
        var num = 0.0
        var den = 0.0
        for (i in GRID_HZ.indices) {
            num += weights[i] * 10.0.pow(EqSolver.combinedResponseDb(bands, GRID_HZ[i], fs) / 10.0)
            den += weights[i]
        }
        require(den > 0.0 && den.isFinite()) { "重みの合計が正の有限値であること (実際: $den)" }
        return 10.0 * log10(num / den)
    }

    /**
     * 土台 (探索を始めた設定) がいま鳴っている聴感レベル (dB) = プリアンプ + 聴感ゲイン。
     * 候補はこの高さに揃える。
     *
     * **`enabled` の分岐はここ 1 箇所に閉じる。**セッションの土台のバンドが
     * 「enabled なら bands、切ってあれば素の音」(`EqFinderController.begin` /
     * [EqFinderMaterialize.bake]) なのと同じ規則で、プリアンプも切ってあれば 0 —
     * 揃えないと **EQ を切った状態から始めた探索だけ音量がずれる**。
     */
    fun baseLevelDb(
        base: EqSettings,
        weights: DoubleArray,
        fs: Int = EqSolver.DEFAULT_FS,
    ): Double {
        if (!base.enabled) return 0.0
        return base.preampDb10.toDouble() / EqUnits.GAIN_SCALE +
            perceivedGainDb(base.bands, weights, fs)
    }

    /**
     * 候補 1 つの等価プリアンプ (db10 整数)。round(([baseLevelDb] − L_c) × 10) を
     * [EqSettings.PREAMP_RANGE] にクランプ。
     *
     * [baseLevelDb] は [EqLoudness.baseLevelDb] が返す値 — セッション中は定数なので、
     * 候補どうしの差は −L_c の差だけ (= 聴感等価) になる。オーバーレイが全部 0 の候補では
     * L_c が土台の聴感ゲインと一致し、プリアンプは土台の値そのものに戻る。
     *
     * クランプが働くのは差が −40〜+12 dB を出る極端な形だけで、そこでは音量等価は成立しない。
     * セッションの候補域で働かないことは EqLoudnessTest が端の組で確かめている。
     */
    fun preampDb10(
        bands: List<EqBand>,
        weights: DoubleArray,
        baseLevelDb: Double,
        fs: Int = EqSolver.DEFAULT_FS,
    ): Int {
        val perceived = perceivedGainDb(bands, weights, fs)
        return Math.round((baseLevelDb - perceived) * EqUnits.GAIN_SCALE).toInt()
            .coerceIn(EqSettings.PREAMP_RANGE)
    }

    private fun kRawDb(hz: Double): Double =
        biquadMagnitudeDb(PRE_B0, PRE_B1, PRE_B2, PRE_A1, PRE_A2, hz) +
            biquadMagnitudeDb(RLB_B0, RLB_B1, RLB_B2, RLB_A1, RLB_A2, hz)

    // EqSolver.biquadMagnitudeDb と同型だが、あちらは private でフィルタの組み立てと対。
    // こちらは a0 = 1 正規化済みの固定係数 (K 特性、fs = K_FS) 専用。
    private fun biquadMagnitudeDb(
        b0: Double,
        b1: Double,
        b2: Double,
        a1: Double,
        a2: Double,
        hz: Double,
    ): Double {
        val w = 2.0 * Math.PI * hz / K_FS
        val cosW = cos(w)
        val sinW = sin(w)
        val cos2W = cos(2 * w)
        val sin2W = sin(2 * w)
        val numRe = b0 + b1 * cosW + b2 * cos2W
        val numIm = -(b1 * sinW + b2 * sin2W)
        val denRe = 1.0 + a1 * cosW + a2 * cos2W
        val denIm = -(a1 * sinW + a2 * sin2W)
        val den = denRe * denRe + denIm * denIm
        if (den == 0.0) return 0.0
        return 10.0 * log10((numRe * numRe + numIm * numIm) / den)
    }
}
