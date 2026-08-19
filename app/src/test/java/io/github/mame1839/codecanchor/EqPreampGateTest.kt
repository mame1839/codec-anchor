package io.github.mame1839.codecanchor

import io.github.mame1839.codecanchor.core.AutoEqParser
import io.github.mame1839.codecanchor.core.AutoEqResult
import io.github.mame1839.codecanchor.core.EqBand
import io.github.mame1839.codecanchor.core.EqParams
import io.github.mame1839.codecanchor.core.EqSettings
import io.github.mame1839.codecanchor.core.EqSolver
import io.github.mame1839.codecanchor.core.EqUnits
import io.github.mame1839.codecanchor.ui.PREAMP_SCALE
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EqPreampGateTest {

    @Test
    fun theApplyPathPassesThePreampThroughWithoutClamping() {
        fun sent(preampDb10: Int): String {
            val args = EqParams.arguments(
                EqSettings(enabled = true, bands = listOf(EqBand(1_000, 141, 0)), preampDb10 = preampDb10),
            )
            return args[args.indexOf("--preamp") + 1]
        }
        assertEquals("-40.0", sent(EqSettings.PREAMP_RANGE.first))
        assertEquals("12.0", sent(EqSettings.PREAMP_RANGE.last))
        assertEquals("-47.9", sent(-479))
        assertEquals("99.9", sent(999))
    }

    @Test
    fun everyPreampProducerClampsToTheSavedRange() {
        val lo = EqSettings.PREAMP_RANGE.first
        val hi = EqSettings.PREAMP_RANGE.last

        val migrated = EqSettings.fromJson(
            JSONObject(
                """{"on":true,"mode":1,"pa":true,"b":[""" +
                    List(4) { """{"f":1000,"q":141,"g":120,"t":0}""" }.joinToString(",") +
                    """]}""",
            ),
        )
        assertEquals(-479, EqSolver.autoPreampDb10(migrated.bands))
        assertEquals("producer 1: 旧版からの移行 (pa=true)", lo, migrated.preampDb10)

        assertEquals(
            "producer 2: 保存された手動値 (下端)",
            lo,
            EqSettings.fromJson(JSONObject("""{"on":true,"pdb":-9999}""")).preampDb10,
        )
        assertEquals(
            "producer 2: 保存された手動値 (上端)",
            hi,
            EqSettings.fromJson(JSONObject("""{"on":true,"pdb":9999}""")).preampDb10,
        )

        val imported = AutoEqParser.parse(
            "Preamp: -99.9 dB\n" +
                "Filter 1: ON PK Fc 1000 Hz Gain -3.0 dB Q 1.41\n",
        )
        assertTrue("取り込みが成功していること (実際: $imported)", imported is AutoEqResult.Ok)
        assertEquals(
            "producer 3: ParametricEQ の取り込み (AutoEqParser の Preamp 行側)",
            lo,
            (imported as AutoEqResult.Ok).settings.preampDb10,
        )

        val graphic = AutoEqParser.parse(
            "GraphicEQ: 20 -60; 200 -60; 2000 -60; 20000 -60\n",
        )
        assertTrue("取り込みが成功していること (実際: $graphic)", graphic is AutoEqResult.Ok)
        assertEquals(
            "producer 4: GraphicEQ の取り込み (AutoEqParser の広帯域オフセット側)",
            lo,
            (graphic as AutoEqResult.Ok).settings.preampDb10,
        )

        assertEquals("producer 5: 画面の摘み (下端)", lo, PREAMP_SCALE.fromPosition(-1f))
        assertEquals("producer 5: 画面の摘み (上端)", hi, PREAMP_SCALE.fromPosition(2f))
    }

    @Test
    fun theSavedRangeEndsExactlyWhereTheNativeGateOpens() {
        assertEquals(-400, EqSettings.PREAMP_RANGE.first)
        assertEquals(120, EqSettings.PREAMP_RANGE.last)
        assertEquals("-40.0", EqParams.decimal(EqSettings.PREAMP_RANGE.first, EqUnits.GAIN_SCALE))
        assertEquals("12.0", EqParams.decimal(EqSettings.PREAMP_RANGE.last, EqUnits.GAIN_SCALE))
    }
}
