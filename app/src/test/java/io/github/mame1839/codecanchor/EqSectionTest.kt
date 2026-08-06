package io.github.mame1839.codecanchor

import io.github.mame1839.codecanchor.core.EqBand
import io.github.mame1839.codecanchor.core.EqMode
import io.github.mame1839.codecanchor.core.EqSettings
import io.github.mame1839.codecanchor.core.EqSolver
import io.github.mame1839.codecanchor.ui.EqScale
import io.github.mame1839.codecanchor.ui.reband
import io.github.mame1839.codecanchor.ui.withGraphicGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** 画面側のロジックのうち、Composable を通さずに確かめられるもの。 */
class EqSectionTest {

    private val gain = EqScale.Linear(-120..120, 5)
    private val preamp = EqScale.Linear(-300..0, 5)
    private val frequency = EqScale.Log(20..20_000)
    private val q = EqScale.Log(10..1_000)

    @Test
    fun linearScaleHitsBothEnds() {
        assertEquals(-120, gain.fromPosition(0f))
        assertEquals(120, gain.fromPosition(1f))
        assertEquals(0f, gain.toPosition(-120), 1e-6f)
        assertEquals(1f, gain.toPosition(120), 1e-6f)
        assertEquals(-300, preamp.fromPosition(0f))
        assertEquals(0, preamp.fromPosition(1f))
    }

    // 刻みから外れた値を返すと、保存されるゲインが 0.5 dB 単位でなくなる。
    @Test
    fun linearScaleSnapsToTheStep() {
        for (i in 0..200) {
            val value = gain.fromPosition(i / 200f)
            assertEquals("位置 ${i / 200f} で $value", 0, value % 5)
            assertTrue(value in -120..120)
        }
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
}
