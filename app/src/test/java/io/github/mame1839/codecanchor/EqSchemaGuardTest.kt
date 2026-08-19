package io.github.mame1839.codecanchor

import io.github.mame1839.codecanchor.core.EqBand
import io.github.mame1839.codecanchor.core.EqPrecision
import io.github.mame1839.codecanchor.core.EqSettings
import io.github.mame1839.codecanchor.core.EqSolver
import io.github.mame1839.codecanchor.core.EqSupport
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EqSchemaGuardTest {

    private val keysBySchema = mapOf(
        1 to setOf("on", "mode", "n", "pa", "pdb", "b"),
        2 to setOf("on", "mode", "n", "pa", "pdb", "prec", "b"),
        3 to setOf("on", "mode", "n", "pdb", "prec", "b"),
    )

    private fun currentKeys(): Set<String> {
        val json = EqSettings().toJson()
        return json.keys().asSequence().toSet()
    }

    @Test
    fun theKeySetMatchesTheDeclaredSchema() {
        val expected = keysBySchema[EqSupport.SCHEMA]
        assertNotNull(
            "EqSupport.SCHEMA = ${EqSupport.SCHEMA} に対応する行が表にない。" +
                "キーを増やしたなら版を上げて行を足すこと",
            expected,
        )
        assertEquals(
            "EqSettings のキーが変わっている。EqSupport.SCHEMA を上げて表に行を足すこと " +
                "(古いフックは知らないキーを落として再 encode するので hash が永久に食い違う)",
            expected,
            currentKeys(),
        )
    }

    @Test
    fun everySchemaChangesTheKeySet() {
        assertEquals("表の最大の版が宣言と食い違う", EqSupport.SCHEMA, keysBySchema.keys.max())
        keysBySchema.keys.sorted().zipWithNext { older, newer ->
            assertNotEquals(
                "版 $older と版 $newer でキーの集合が同じ",
                keysBySchema.getValue(older),
                keysBySchema.getValue(newer),
            )
        }
    }

    @Test
    fun theGuardCatchesARemovedKeyToo() {
        val declared = keysBySchema.getValue(EqSupport.SCHEMA)
        assertNotEquals("キーを 1 つ落とした集合が現在の集合と等しい", declared, declared - "pdb")
        assertNotEquals("キーを 1 つ足した集合が現在の集合と等しい", declared, declared + "pa")
    }

    @Test
    fun theAutoPreampFlagIsBakedIntoTheStoredValue() {
        val bands = EqSolver.centerFrequencies(10).map { EqBand(freqHz = it, q100 = 100, gainDb10 = 60) }
        assertEquals(-108, EqSolver.autoPreampDb10(bands))

        val old = EqSettings(enabled = true, bands = bands, preampDb10 = -30).toJson()
            .put("pa", true)
        assertEquals(-108, EqSettings.fromJson(old).preampDb10)

        val new = EqSettings(enabled = true, bands = bands, preampDb10 = -30).toJson()
        assertFalse("新形式に pa が残っている", new.has("pa"))
        assertEquals(-30, EqSettings.fromJson(new).preampDb10)

        val oldManual = EqSettings(enabled = true, bands = bands, preampDb10 = -30).toJson()
            .put("pa", false)
        assertEquals(-30, EqSettings.fromJson(oldManual).preampDb10)
    }

    @Test
    fun theMigrationStopsAtTheEdgeOfTheStorableRange() {
        val loud = EqSolver.centerFrequencies(31).map { EqBand(freqHz = it, q100 = 100, gainDb10 = 200) }
        assertTrue(
            "題材のピークが浅くてクランプ域に届いていない",
            EqSolver.autoPreampDb10(loud) < EqSettings.PREAMP_RANGE.first,
        )
        val old = EqSettings(enabled = true, bandCount = 31, bands = loud).toJson().put("pa", true)
        assertEquals(EqSettings.PREAMP_RANGE.first, EqSettings.fromJson(old).preampDb10)
    }

    @Test
    fun theDefaultPreampIsZero() {
        assertEquals(0, EqSettings().preampDb10)
        assertEquals(0, EqSettings.fromJson(JSONObject()).preampDb10)
    }

    @Test
    fun settingsWrittenBeforeThisVersionReadAsStandard() {
        val old = EqSettings(enabled = true, bands = listOf(EqBand(1_000, 141, 30))).toJson()
        old.remove("prec")
        assertEquals(EqPrecision.STANDARD, EqSettings.fromJson(old).precision)
    }

    @Test
    fun unknownPrecisionValuesFallBackToStandard() {
        assertEquals(EqPrecision.STANDARD, EqPrecision.normalize(7))
        assertEquals(EqPrecision.STANDARD, EqPrecision.normalize(-1))
        assertEquals(EqPrecision.HIGH, EqPrecision.normalize(EqPrecision.HIGH))
    }

    @Test
    fun precisionSurvivesTheRoundTrip() {
        val eq = EqSettings(enabled = true, precision = EqPrecision.HIGH)
        assertEquals(EqPrecision.HIGH, EqSettings.fromJson(eq.toJson()).precision)
    }
}
