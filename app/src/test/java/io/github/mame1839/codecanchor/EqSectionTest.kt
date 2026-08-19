package io.github.mame1839.codecanchor

import io.github.mame1839.codecanchor.core.EqBand
import io.github.mame1839.codecanchor.core.EqMode
import io.github.mame1839.codecanchor.core.EqSettings
import io.github.mame1839.codecanchor.core.EqSolver
import io.github.mame1839.codecanchor.core.EqBandType
import io.github.mame1839.codecanchor.ui.EqScale
import io.github.mame1839.codecanchor.ui.GAIN_SCALE
import io.github.mame1839.codecanchor.ui.PREAMP_SCALE
import io.github.mame1839.codecanchor.ui.reband
import io.github.mame1839.codecanchor.ui.resetToZero
import io.github.mame1839.codecanchor.ui.toParametric
import io.github.mame1839.codecanchor.ui.withGraphicGrid
import io.github.mame1839.codecanchor.ui.withStartingBands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** 画面側のロジックのうち、Composable を通さずに確かめられるもの。 */
class EqSectionTest {

    // 画面が実際に使うスケールをそのまま見る。写しを作ると、実物だけ変わったときに
    // ここが古い値のまま通り続ける。
    private val gain = GAIN_SCALE
    private val preamp = PREAMP_SCALE
    private val frequency = EqScale.Log(20..20_000)
    private val q = EqScale.Log(10..1_000)

    // 両端の literal は仕様の固定 (±12 dB)。実物が動いたらここで気づく。
    @Test
    fun linearScaleHitsBothEnds() {
        assertEquals(-120, gain.fromPosition(0f))
        assertEquals(120, gain.fromPosition(1f))
        assertEquals(0f, gain.toPosition(-120), 1e-6f)
        assertEquals(1f, gain.toPosition(120), 1e-6f)
    }

    /**
     * **プリアンプの摘みはモデルが持てる値の全部に届く。**`EqScale.Linear.fromPosition` は
     * 自分の範囲でクランプするので、届かない値を持つ設定に触ると、摘みを動かしていなくても
     * 保存値が書き換わる (= 音が変わる)。取り込み (AutoEQ) と探索の焼き込みは
     * [EqSettings.PREAMP_RANGE] の端まで値を作るので、摘みも同じ端まで要る。
     *
     * `PREAMP_SCALE` が `PREAMP_RANGE` を参照していても、狭い範囲を書き直せばここが落ちる
     * (見ているのは参照の一致ではなく、摘みが実際に端まで届くこと)。
     */
    @Test
    fun thePreampSliderReachesEveryStorableValue() {
        assertEquals(EqSettings.PREAMP_RANGE.first, preamp.fromPosition(0f))
        assertEquals(EqSettings.PREAMP_RANGE.last, preamp.fromPosition(1f))
        assertTrue("正のプリアンプに届かない", preamp.fromPosition(1f) > 0)
    }

    // 保存は最初から 0.1 dB 単位 (gainDb10)。摘みも同じ粒度なので、どの保存値も摘みに乗せて
    // そのまま返ること。刻みが粗いと (0.5 dB のままだと)、AutoEQ 由来などの 5 の倍数でない
    // 保存値が、摘みに触れた瞬間に隣の値へ跳ぶ。
    @Test
    fun gainSlidersRoundTripEveryStoredValue() {
        for (v in -120..120) assertEquals(v, gain.fromPosition(gain.toPosition(v)))
        for (v in EqSettings.PREAMP_RANGE) assertEquals(v, preamp.fromPosition(preamp.toPosition(v)))
    }

    @Test
    fun logScaleHitsBothEnds() {
        assertEquals(20, frequency.fromPosition(0f))
        assertEquals(20_000, frequency.fromPosition(1f))
        assertEquals(10, q.fromPosition(0f))
        assertEquals(1_000, q.fromPosition(1f))
    }

    // 位置を上げたら値が下がる区間があると、摘みを右へ動かしたのに周波数が戻る。
    @Test
    fun logScaleNeverGoesBackwards() {
        var previous = 0
        for (i in 0..1_000) {
            val value = frequency.fromPosition(i / 1_000f)
            assertTrue("位置 ${i / 1_000f} で $previous -> $value", value >= previous)
            previous = value
        }
    }

    // 有効数字 3 桁。丸めないと 20 kHz 側で「12483 Hz」のような値がそのまま出る。
    @Test
    fun logScaleRoundsToThreeSignificantDigits() {
        for (i in 0..1_000) {
            val value = frequency.fromPosition(i / 1_000f)
            val unit = when {
                value < 1_000 -> 1
                value < 10_000 -> 10
                else -> 100
            }
            assertEquals("$value が $unit Hz 刻みでない", 0, value % unit)
        }
    }

    // 対数なので、低域も高域も同じ割合だけ動くこと (線形だと下 2 オクターブが潰れる)。
    @Test
    fun logScaleGivesLowFrequenciesRoom() {
        // 20 Hz 〜 200 Hz の 1 オクターブ 3 つ分が、摘みの 3 割を占める。
        val at200 = frequency.toPosition(200)
        assertEquals(1f / 3f, at200, 0.02f)
    }

    // バンド数を変えても曲線の形が残ること。捨てて 0 にすると作り込んだ音が消える。
    @Test
    fun rebandKeepsTheShapeOfTheCurve() {
        val before = EqSettings(
            enabled = true,
            bandCount = 10,
            bands = EqSolver.centerFrequencies(10).mapIndexed { i, hz ->
                EqBand(freqHz = hz, q100 = 100, gainDb10 = if (i < 5) 60 else -40)
            },
        )
        val after = reband(before, 31)
        assertEquals(31, after.bandCount)
        assertEquals(31, after.bands.size)
        // 元の曲線と、解き直した曲線を同じ周波数で比べる。
        for (hz in listOf(50.0, 100.0, 250.0, 1_000.0, 4_000.0, 12_000.0)) {
            val original = EqSolver.combinedResponseDb(before.bands, hz)
            val rebanded = EqSolver.combinedResponseDb(after.bands, hz)
            assertTrue(
                "$hz Hz で $original -> $rebanded",
                abs(original - rebanded) <= 3.0,
            )
        }
    }

    /**
     * **摘みの値はバンド数をどう切り替えても保たれること。**
     *
     * 全バンド +12 でバンド数を切り替えるのはユーザ本人の試し方 (2026-08-12 の
     * スクリーンショット 3 枚)。reband が「真の合成応答を新しい中心でサンプル」だと、
     * 解の残差 (中心間の谷) を新しい目標に焼き込む — 旧実装の 5→10 は
     * [7.8, 12.0, 12.4, 12.0, ...] になっていた。摘みの値 (目標) の折れ線を読めば、
     * どの並びでも同値のまま渡る。
     */
    @Test
    fun flatKnobsSurviveEveryBandCountSwitch() {
        var s = EqSettings(
            enabled = true,
            bandCount = 10,
            bands = EqSolver.solveBands(DoubleArray(10) { 12.0 }, EqSolver.centerFrequencies(10), EqSolver.defaultQ(10)),
        )
        for (count in listOf(5, 10, 31, 15, 5, 31, 10)) {
            s = reband(s, count)
            val knobs = EqSolver.graphicTargetsDb10(s.bands)
            assertTrue(
                "$count バンドへ切り替えたら摘みが ${knobs.toList()}",
                knobs.all { it == 120 },
            )
        }
    }

    /**
     * **パラメトリック→グラフィックは真の曲線を読むこと。**折れ線 (摘みの値の補間) に
     * 通すとシェルフで壊れる — シェルフの fc での応答はゲインの約半分 (+6 dB なら +3) で、
     * fc より上の実体 (+6) が折れ線のどの点にも現れない。
     */
    @Test
    fun parametricShapesAreReadFromTheTrueCurve() {
        val shelf = EqBand(freqHz = 1_000, q100 = 70, gainDb10 = 60, type = EqBandType.HIGH_SHELF)
        val before = EqSettings(enabled = true, mode = EqMode.GRAPHIC, bandCount = 10, bands = listOf(shelf))
        val after = reband(before, 10)
        for (hz in EqSolver.centerFrequencies(10)) {
            val original = EqSolver.combinedResponseDb(listOf(shelf), hz.toDouble())
            val rebanded = EqSolver.combinedResponseDb(after.bands, hz.toDouble())
            // 許容 0.15: 目標は 0.1 dB に丸めてから解かれる (±0.05) + 整数の詰めの遊び (±0.05)。
            // 見分けたい相手 (折れ線化の取り違え) は 3 dB 級なので、これで十分に締まっている。
            assertEquals("$hz Hz", original, rebanded, 0.15)
        }
        // 折れ線に通されたときに落ちる側: fc (+3) しか見えなければ高域が +3 に潰れる。
        assertTrue(
            "シェルフの高域が消えた: ${EqSolver.combinedResponseDb(after.bands, 16_000.0)}",
            EqSolver.combinedResponseDb(after.bands, 16_000.0) > 5.0,
        )
    }

    // 選択肢に無いバンド数は EqSettings.fromJson が 10 に書き換えるので、
    // ここで通してしまうと hash の往復が壊れる。
    @Test
    fun rebandNormalisesTheBandCount() {
        val result = reband(EqSettings(enabled = true), 7)
        assertEquals(10, result.bandCount)
        assertEquals(10, result.bands.size)
    }

    @Test
    fun rebandFromNothingIsFlat() {
        val result = reband(EqSettings(enabled = true), 15)
        assertEquals(15, result.bands.size)
        assertTrue(result.bands.all { it.gainDb10 == 0 })
        assertEquals(EqSolver.centerFrequencies(15), result.bands.map { it.freqHz })
    }

    // EQ を初めてオンにしたとき、bands は空のまま。並びを作らないとスライダーが 1 本も出ない。
    @Test
    fun graphicGridIsBuiltWhenBandsAreMissing() {
        val filled = withGraphicGrid(EqSettings(enabled = true, bandCount = 10))
        assertEquals(EqSolver.centerFrequencies(10), filled.bands.map { it.freqHz })
    }

    // 並びが合っているときに解き直すと値がわずかに動く。オンにするたびに設定が変わったことになる。
    @Test
    fun graphicGridLeavesAMatchingGridAlone() {
        val settings = EqSettings(
            enabled = true,
            bandCount = 10,
            bands = EqSolver.centerFrequencies(10).map { EqBand(freqHz = it, q100 = 100, gainDb10 = 35) },
        )
        assertEquals(settings, withGraphicGrid(settings))
    }

    // パラメトリックの並びは中心周波数と一致しないのが普通。触ると取り込んだ曲線が壊れる。
    @Test
    fun graphicGridLeavesParametricAlone() {
        val settings = EqSettings(
            enabled = true,
            mode = EqMode.PARAMETRIC,
            bands = listOf(EqBand(105, 70, -65), EqBand(3_150, 141, 25)),
        )
        assertEquals(settings, withGraphicGrid(settings))
    }

    // 作った曲線はパラメトリックへ移っても 1 つも変えない。丸めも解き直しも起きない経路。
    @Test
    fun toParametricKeepsACurveAsItIs() {
        val settings = EqSettings(
            enabled = true,
            bandCount = 10,
            bands = EqSolver.centerFrequencies(10).mapIndexed { i, hz ->
                EqBand(freqHz = hz, q100 = 100, gainDb10 = if (i < 5) 60 else -40)
            },
        )
        val after = toParametric(settings)
        assertEquals(EqMode.PARAMETRIC, after.mode)
        assertEquals(settings.bands, after.bands)
    }

    // 1 本でも動いていれば曲線。3 本に減らすと作った音が変わる。
    @Test
    fun toParametricKeepsEveryBandWhenOneIsMoved() {
        val bands = EqSolver.centerFrequencies(10).mapIndexed { i, hz ->
            EqBand(freqHz = hz, q100 = 100, gainDb10 = if (i == 3) -5 else 0)
        }
        val after = toParametric(EqSettings(enabled = true, bandCount = 10, bands = bands))
        assertEquals(bands, after.bands)
    }

    // 平らなグラフィックは「まだ何も無い」。グラフィックでユーザが決められるのはゲインだけなので、
    // 全部 0 dB なら引き継ぐ情報が 1 つも無い。0 dB の 10 本を並べずに 3 本から始める。
    @Test
    fun toParametricStartsFromThreeBandsWhenNothingWasMade() {
        val flat = withStartingBands(EqSettings(enabled = true, bandCount = 10))
        val after = toParametric(flat)
        assertEquals(listOf(100, 1_000, 10_000), after.bands.map { it.freqHz })
        assertTrue(after.bands.all { it.gainDb10 == 0 })
    }

    @Test
    fun startingBandsFillAnEmptyParametric() {
        val filled = withStartingBands(EqSettings(enabled = true, mode = EqMode.PARAMETRIC))
        assertEquals(listOf(100, 1_000, 10_000), filled.bands.map { it.freqHz })
    }

    // 「fc と Q は置いたが、ゲインはまだ 0」は実在する状態。ここを平らで判定すると、
    // EQ を切って入れ直しただけでその作業が消える。
    @Test
    fun startingBandsLeaveSilentParametricBandsAlone() {
        val settings = EqSettings(
            enabled = true,
            mode = EqMode.PARAMETRIC,
            bands = listOf(EqBand(105, 70, 0), EqBand(3_150, 141, 0)),
        )
        assertEquals(settings, withStartingBands(settings))
    }

    @Test
    fun startingBandsBuildTheGraphicGrid() {
        val filled = withStartingBands(EqSettings(enabled = true, bandCount = 15))
        assertEquals(EqSolver.centerFrequencies(15), filled.bands.map { it.freqHz })
    }

    // バンド数が違えば並びも違う。そのままだと 31 本の設定に 10 本のスライダーが出る。
    @Test
    fun graphicGridRebuildsWhenTheCountDoesNotMatch() {
        val settings = EqSettings(
            enabled = true,
            bandCount = 31,
            bands = EqSolver.centerFrequencies(10).map { EqBand(freqHz = it, q100 = 100, gainDb10 = 20) },
        )
        val fixed = withGraphicGrid(settings)
        assertNotEquals(settings, fixed)
        assertEquals(EqSolver.centerFrequencies(31), fixed.bands.map { it.freqHz })
    }

    // リセット後のグラフィックは「オンにした直後」と同じ平らなグリッド。solve() が Q を
    // 上げていても、その跡ごと作り直す。摘み (その周波数で実際に鳴る音量) も全部 0.0 になる。
    @Test
    fun resetRebuildsAFlatGraphicGrid() {
        val curved = reband(
            EqSettings(enabled = true, bandCount = 10, preampDb10 = -45),
            10,
        ).let { it.copy(bands = EqSolver.withGraphicTarget(it.bands, 3, -80)) }
        val after = resetToZero(curved)
        assertEquals(withStartingBands(EqSettings(enabled = true, bandCount = 10)).bands, after.bands)
        assertTrue(EqSolver.graphicTargetsDb10(after.bands).all { it == 0 })
        assertEquals(0, after.preampDb10)
    }

    // パラメトリックのリセットはゲインだけ。fc・Q・種別はユーザが置いたものなので消えない。
    @Test
    fun resetKeepsParametricBandsWhereTheyAre() {
        val before = EqSettings(
            enabled = true,
            mode = EqMode.PARAMETRIC,
            bands = listOf(
                EqBand(105, 70, -65, EqBandType.LOW_SHELF),
                EqBand(3_150, 141, 25),
            ),
            preampDb10 = -120,
        )
        val after = resetToZero(before)
        assertEquals(EqMode.PARAMETRIC, after.mode)
        assertTrue(after.bands.all { it.gainDb10 == 0 })
        assertEquals(before.bands.map { it.copy(gainDb10 = 0) }, after.bands)
        assertEquals(0, after.preampDb10)
    }

    // プリアンプはリセットの対象 (0 に戻さないと、平らなのに音量だけ下がった状態が残る)。
    // 「自動なら触らない」の特例は無い — 自動を廃したので、プリアンプはどの経路でも
    // ユーザが決めた 1 つの値。
    @Test
    fun resetZeroesThePreampWhicheverWayItWasSet() {
        for (preamp in listOf(-95, -400, 120)) {
            val after = resetToZero(
                EqSettings(
                    enabled = true,
                    mode = EqMode.PARAMETRIC,
                    bands = listOf(EqBand(1_000, 100, 80)),
                    preampDb10 = preamp,
                ),
            )
            assertEquals("$preamp から戻らなかった", 0, after.preampDb10)
        }
    }
}
