package io.github.mame1839.codecanchor

import io.github.mame1839.codecanchor.core.AppConfig
import io.github.mame1839.codecanchor.core.EqBand
import io.github.mame1839.codecanchor.core.EqBandType
import io.github.mame1839.codecanchor.core.EqMode
import io.github.mame1839.codecanchor.core.EqPreset
import io.github.mame1839.codecanchor.core.EqPresetBook
import io.github.mame1839.codecanchor.core.EqSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EqPresetTest {

    private val settings = EqSettings(
        enabled = true,
        mode = EqMode.PARAMETRIC,
        bandCount = 15,
        bands = listOf(
            EqBand(freqHz = 105, q100 = 70, gainDb10 = 7, type = EqBandType.LOW_SHELF),
            EqBand(freqHz = 6_204, q100 = 242, gainDb10 = 64),
        ),
        preampDb10 = -62,
    )

    @Test
    fun singlePresetRoundTrips() {
        val preset = EqPreset("バスブースト", settings)
        assertEquals(preset, EqPreset.decodeSingle(EqPreset.encodeSingle(preset)))
    }

    @Test
    fun otherJsonIsRejected() {
        assertNull(EqPreset.decodeSingle(AppConfig().encode()))
        assertNull(EqPreset.decodeSingle("not json"))
        assertNull(EqPreset.decodeSingle("""{"format":"codec-anchor-eq-preset"}"""))
    }

    @Test
    fun blankNameIsRejected() {
        assertNull(EqPreset.decodeSingle(EqPreset.encodeSingle(EqPreset("   ", settings))))
    }

    @Test
    fun bookReplacesByName() {
        val book = EqPresetBook()
            .with(EqPreset("A", settings))
            .with(EqPreset("B", EqSettings()))
            .with(EqPreset("A", EqSettings(enabled = true)))
        assertEquals(listOf("B", "A"), book.presets.map { it.name })
        assertEquals(EqSettings(enabled = true), book.presets.last().settings)
    }

    @Test
    fun bookRoundTrips() {
        val book = EqPresetBook()
            .with(EqPreset("A", settings))
            .with(EqPreset("B", EqSettings()))
        assertEquals(book, EqPresetBook.decode(book.encode()))
        assertEquals(listOf("A"), book.without("B").presets.map { it.name })
    }

    @Test
    fun brokenStorageFallsBackToEmpty() {
        assertEquals(EqPresetBook(), EqPresetBook.decode(null))
        assertEquals(EqPresetBook(), EqPresetBook.decode(""))
        assertEquals(EqPresetBook(), EqPresetBook.decode("{"))
        assertEquals(EqPresetBook(), EqPresetBook.decode("""{"other":1}"""))
    }
}
