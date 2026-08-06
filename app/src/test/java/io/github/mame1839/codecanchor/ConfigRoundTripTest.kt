package io.github.mame1839.codecanchor

import io.github.mame1839.codecanchor.core.AppConfig
import io.github.mame1839.codecanchor.core.DeviceProfile
import io.github.mame1839.codecanchor.core.EqBand
import io.github.mame1839.codecanchor.core.EqBandType
import io.github.mame1839.codecanchor.core.EqMode
import io.github.mame1839.codecanchor.core.EqSettings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConfigRoundTripTest {

    // Android の JSONObject は LinkedHashMap なので put の順序がそのまま出る。
    // ここが崩れると hash() の往復一致が成り立たない。
    @Test
    fun jsonKeepsInsertionOrder() {
        val o = JSONObject()
        o.put("b", 1)
        o.put("a", 2)
        assertEquals("""{"b":1,"a":2}""", o.toString())
    }

    // フックがやることと同じ: 受け取った JSON を decode して再 encode する。
    private fun reencode(config: AppConfig): String =
        AppConfig.decode(config.encode())!!.encode()

    @Test
    fun emptyEqRoundTrips() {
        val config = AppConfig().withProfile(DeviceProfile(mac = "AA:BB:CC:DD:EE:FF"))
        assertEquals(config.encode(), reencode(config))
        assertEquals(config.hash(), AppConfig.decode(config.encode())!!.hash())
    }

    @Test
    fun populatedEqRoundTrips() {
        val bands = listOf(
            EqBand(freqHz = 31, q100 = 141, gainDb10 = -65, type = EqBandType.LOW_SHELF),
            EqBand(freqHz = 1_000, q100 = 200, gainDb10 = 0),
            EqBand(freqHz = 16_000, q100 = 70, gainDb10 = 120, type = EqBandType.HIGH_SHELF),
        )
        val profile = DeviceProfile(
            mac = "AA:BB:CC:DD:EE:FF",
            eq = EqSettings(
                enabled = true,
                mode = EqMode.PARAMETRIC,
                bandCount = 31,
                bands = bands,
                preampAuto = false,
                preampDb10 = -85,
            ),
        )
        val config = AppConfig().withProfile(profile)
        assertEquals(config.encode(), reencode(config))
        assertEquals(config.hash(), AppConfig.decode(config.encode())!!.hash())
    }

    @Test
    fun maxBandsRoundTrips() {
        val bands = (0 until EqSettings.MAX_BANDS).map {
            EqBand(freqHz = 20 + it * 600, q100 = 200, gainDb10 = it - 15)
        }
        val config = AppConfig().withProfile(
            DeviceProfile(mac = "11:22:33:44:55:66", eq = EqSettings(enabled = true, bands = bands)),
        )
        assertEquals(config.encode(), reencode(config))
    }

    // 2 往復させても変わらないこと。1 往復目で正規化が起きても、そこから先が安定していれば
    // 「一度だけ食い違ってあとは一致」になり、恒久的な不整合にはならない。
    @Test
    fun secondRoundTripIsStable() {
        val config = AppConfig().withProfile(
            DeviceProfile(
                mac = "11:22:33:44:55:66",
                eq = EqSettings(enabled = true, bands = listOf(EqBand(100, 141, 30))),
            ),
        )
        val once = reencode(config)
        val twice = AppConfig.decode(once)!!.encode()
        assertEquals(once, twice)
    }

    // VERSION を上げてはいけないことの見張り。上げると古いフックとの hash が永久に食い違う
    // (fromJson が "v" を読まないので、フックは自分の VERSION を書き戻す)。
    @Test
    fun versionIsPinned() {
        assertEquals(1, AppConfig.VERSION)
    }
}
