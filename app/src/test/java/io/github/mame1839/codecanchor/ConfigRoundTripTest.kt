package io.github.mame1839.codecanchor

import io.github.mame1839.codecanchor.core.AppConfig
import io.github.mame1839.codecanchor.core.AutoEqParser
import io.github.mame1839.codecanchor.core.AutoEqResult
import io.github.mame1839.codecanchor.core.DeviceProfile
import io.github.mame1839.codecanchor.core.EqBand
import io.github.mame1839.codecanchor.core.EqBandType
import io.github.mame1839.codecanchor.core.EqMode
import io.github.mame1839.codecanchor.core.EqSettings
import io.github.mame1839.codecanchor.core.EqSolver
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConfigRoundTripTest {

    @Test
    fun jsonKeepsInsertionOrder() {
        val o = JSONObject()
        o.put("b", 1)
        o.put("a", 2)
        assertEquals("""{"b":1,"a":2}""", o.toString())
    }

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

    @Test
    fun importedAutoEqRoundTrips() {
        val sources = listOf(
            """
            Preamp: -6.2 dB
            Filter 1: ON LSC Fc 105 Hz Gain 0.7 dB Q 0.70
            Filter 2: ON PK Fc 6204 Hz Gain 6.4 dB Q 2.42
            Filter 3: ON HSC Fc 10000 Hz Gain -1.5 dB Q 0.70
            """.trimIndent(),
            "GraphicEQ: 20 -6.0; 100 -5.0; 1000 -7.0; 10000 -8.0; 20000 -9.0",
        )
        for (count in EqSettings.BAND_COUNTS) {
            for (text in sources) {
                val parsed = AutoEqParser.parse(text, count) as AutoEqResult.Ok
                val config = AppConfig().withProfile(
                    DeviceProfile(mac = "AA:BB:CC:DD:EE:FF", eq = parsed.settings),
                )
                assertEquals("バンド数 $count", config.encode(), reencode(config))
            }
        }
    }

    @Test
    fun solvedBandsRoundTrip() {
        for (count in EqSettings.BAND_COUNTS) {
            val freqs = EqSolver.centerFrequencies(count)
            val target = DoubleArray(count) { if (it % 2 == 0) 9.0 else -9.0 }
            val bands = EqSolver.solveBands(target, freqs, EqSolver.defaultQ(count))
            val config = AppConfig().withProfile(
                DeviceProfile(
                    mac = "AA:BB:CC:DD:EE:FF",
                    eq = EqSettings(enabled = true, bandCount = count, bands = bands),
                ),
            )
            assertEquals("バンド数 $count", config.encode(), reencode(config))
        }
    }

    @Test
    fun versionIsPinned() {
        assertEquals(1, AppConfig.VERSION)
    }
}
