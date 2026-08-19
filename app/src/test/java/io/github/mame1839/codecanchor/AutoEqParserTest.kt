package io.github.mame1839.codecanchor

import io.github.mame1839.codecanchor.core.AutoEqParser
import io.github.mame1839.codecanchor.core.AutoEqResult
import io.github.mame1839.codecanchor.core.EqBandType
import io.github.mame1839.codecanchor.core.EqMode
import io.github.mame1839.codecanchor.core.EqSettings
import io.github.mame1839.codecanchor.core.EqSolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoEqParserTest {

    private val parametric = """
        Preamp: -6.2 dB
        Filter 1: ON LSC Fc 105 Hz Gain 0.7 dB Q 0.70
        Filter 2: ON PK Fc 6204 Hz Gain 6.4 dB Q 2.42
        Filter 3: ON HSC Fc 10000 Hz Gain -1.5 dB Q 0.70
    """.trimIndent()

    @Test
    fun parametricUsesPreampLineVerbatim() {
        val r = AutoEqParser.parse(parametric) as AutoEqResult.Ok
        assertEquals(EqMode.PARAMETRIC, r.settings.mode)
        assertEquals(-62, r.settings.preampDb10) // -6.2 dB
        assertEquals(3, r.settings.bands.size)
    }

    @Test
    fun parametricMapsFilterTypes() {
        val r = AutoEqParser.parse(parametric) as AutoEqResult.Ok
        assertEquals(EqBandType.LOW_SHELF, r.settings.bands[0].type)
        assertEquals(EqBandType.PEAKING, r.settings.bands[1].type)
        assertEquals(EqBandType.HIGH_SHELF, r.settings.bands[2].type)
        assertEquals(6204, r.settings.bands[1].freqHz)
        assertEquals(64, r.settings.bands[1].gainDb10)
        assertEquals(242, r.settings.bands[1].q100)
    }

    // bandCount はグラフィックの選択肢 (5 / 10 / 15 / 31) しか取れない。
    // フィルタの本数をここに入れると EqSettings.fromJson が 10 に書き換え、
    // アプリの encode とフックの再 encode が食い違って hash の往復が永久に壊れる。
    @Test
    fun parametricKeepsBandCountAmongTheGraphicChoices() {
        val r = AutoEqParser.parse(parametric, bandCount = 15) as AutoEqResult.Ok
        assertEquals(3, r.settings.bands.size)
        assertTrue(r.settings.bandCount in EqSettings.BAND_COUNTS)
        assertEquals(15, r.settings.bandCount)
    }

    // LS / HS は EqualizerAPO では corner frequency 扱いで周波数がシフトする。
    // 「シェルフだから同じ」で通すと取り込んだ曲線がずれるので、明示的に弾く。
    @Test
    fun cornerShelvesAreRejected() {
        val text = "Preamp: -6.0 dB\nFilter 1: ON LS Fc 105 Hz Gain 0.7 dB Q 0.70"
        val r = AutoEqParser.parse(text)
        assertEquals(AutoEqResult.Error(AutoEqResult.Reason.CORNER_SHELF), r)
    }

    @Test
    fun shelfQIsCappedAt070() {
        val text = "Preamp: 0.0 dB\nFilter 1: ON LSC Fc 105 Hz Gain 3.0 dB Q 1.40"
        val r = AutoEqParser.parse(text) as AutoEqResult.Ok
        assertEquals(70, r.settings.bands[0].q100)
    }

    @Test
    fun preampWithoutFiltersIsNotBands() {
        val r = AutoEqParser.parse("Preamp: -6.0 dB")
        assertEquals(AutoEqResult.Error(AutoEqResult.Reason.NO_BANDS), r)
    }

    @Test
    fun graphicIsDetectedAndDoesNotRecomputePreamp() {
        val text = "GraphicEQ: 20 -6.0; 100 -5.0; 1000 -7.0; 10000 -8.0; 20000 -9.0"
        val r = AutoEqParser.parse(text, bandCount = 10) as AutoEqResult.Ok
        assertEquals(EqMode.GRAPHIC, r.settings.mode)
        assertEquals(10, r.settings.bands.size)
    }

    // GraphicEQ.txt はプリアンプが曲線に焼き込んである。プリアンプに移すのは
    // 「曲線全体のオフセット」だけで、ヘッドルームを別途足すと二重に掛かって全体が沈む。
    // この曲線の平均は -6.6 dB 前後なので、プリアンプはその近傍に出る (ヘッドルームが
    // 混ざると、さらにバンドの最大ゲイン分 ≈2 dB 下がるので区別できる)。
    @Test
    fun graphicMovesOnlyTheCurveOffsetIntoPreamp() {
        val text = "GraphicEQ: 20 -6.0; 100 -5.0; 1000 -7.0; 10000 -8.0; 20000 -9.0"
        val r = AutoEqParser.parse(text, bandCount = 10) as AutoEqResult.Ok
        assertTrue("preampDb10=${r.settings.preampDb10}", r.settings.preampDb10 in -75..-58)
        // 形だけが残るのでバンドのゲインは小さい。自動プリアンプを掛け直したときの
        // 値 (ヘッドルーム) と混ざっていないこと。
        assertTrue(r.settings.bands.all { it.gainDb10 in -30..30 })
        assertEquals((EqSolver.defaultQ(10) * 100).toInt(), r.settings.bands[0].q100)
    }

    // 対数周波数上の線形補間。100 Hz と 1000 Hz の中点は幾何平均の 316 Hz。
    @Test
    fun interpolationIsLogFrequencyLinearDb() {
        val points = listOf(100.0 to 0.0, 1000.0 to 10.0)
        assertEquals(5.0, AutoEqParser.interpolate(points, 316.23), 0.05)
    }

    @Test
    fun outsideNodeRangeIsFlat() {
        val points = listOf(100.0 to 3.0, 1000.0 to 9.0)
        assertEquals(3.0, AutoEqParser.interpolate(points, 20.0), 1e-9)
        assertEquals(9.0, AutoEqParser.interpolate(points, 20_000.0), 1e-9)
    }

    @Test
    fun garbageIsRejected() {
        assertEquals(
            AutoEqResult.Error(AutoEqResult.Reason.NOT_AUTOEQ),
            AutoEqParser.parse("hello world"),
        )
    }
}
