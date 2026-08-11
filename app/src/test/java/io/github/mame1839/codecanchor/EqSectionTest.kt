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

    // 両端の literal は仕様の固定 (±12 dB / プリアンプは -30〜0 dB)。実物が動いたらここで気づく。
    @Test
    fun linearScaleHitsBothEnds() {
        assertEquals(-120, gain.fromPosition(0f))
        assertEquals(120, gain.fromPosition(1f))
        assertEquals(0f, gain.toPosition(-120), 1e-6f)
        assertEquals(1f, gain.toPosition(120), 1e-6f)
        assertEquals(-300, preamp.fromPosition(0f))
        assertEquals(0, preamp.fromPosition(1f))
    }

    // 保存は最初から 0.1 dB 単位 (gainDb10)。摘みも同じ粒度なので、どの保存値も摘みに乗せて
    // そのまま返ること。刻みが粗いと (0.5 dB のままだと)、AutoEQ 由来などの 5 の倍数でない
    // 保存値が、摘みに触れた瞬間に隣の値へ跳ぶ。
    @Test
    fun gainSlidersRoundTripEveryStoredValue() {
        for (v in -120..120) assertEquals(v, gain.fromPosition(gain.toPosition(v)))
        for (v in -300..0) assertEquals(v, preamp.fromPosition(preamp.toPosition(v)))
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
            EqSettings(enabled = true, bandCount = 10, preampAuto = false, preampDb10 = -45),
            10,
        ).let { it.copy(bands = EqSolver.withGraphicTarget(it.bands, 3, -80)) }
        val after = resetToZero(curved)
        assertEquals(withStartingBands(EqSettings(enabled = true, bandCount = 10)).bands, after.bands)
        assertTrue(EqSolver.graphicTargetsDb10(after.bands).all { it == 0 })
        assertEquals(0, after.preampDb10)
        assertEquals(false, after.preampAuto)
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
            preampAuto = false,
            preampDb10 = -120,
        )
        val after = resetToZero(before)
        assertEquals(EqMode.PARAMETRIC, after.mode)
        assertTrue(after.bands.all { it.gainDb10 == 0 })
        assertEquals(before.bands.map { it.copy(gainDb10 = 0) }, after.bands)
        assertEquals(0, after.preampDb10)
        assertEquals(false, after.preampAuto)
    }

    // 手動プリアンプはリセットの対象 (0 に戻さないと、平らなのに音量だけ下がった状態が残る)。
    // 自動のスイッチは方式の選択なので触らない。自動側は平らな曲線から 0 dB を導く。
    @Test
    fun resetZeroesTheManualPreampAndDerivesZeroForAuto() {
        val after = resetToZero(
            EqSettings(
                enabled = true,
                mode = EqMode.PARAMETRIC,
                bands = listOf(EqBand(1_000, 100, 80)),
                preampAuto = true,
                preampDb10 = -95,
            ),
        )
        assertEquals(true, after.preampAuto)
        assertEquals(0, after.preampDb10)
        assertEquals(0, EqSolver.autoPreampDb10(after.bands))
    }
}
