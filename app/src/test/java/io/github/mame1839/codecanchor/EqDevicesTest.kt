package io.github.mame1839.codecanchor

import io.github.mame1839.codecanchor.core.EqDevices
import io.github.mame1839.codecanchor.core.EqDevicesOutcome
import io.github.mame1839.codecanchor.core.EqDevicesResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EqDevicesTest {

    private val a = "38:D5:18:47:31:A4"
    private val b = "01:23:45:67:89:AB"

    @Test
    fun normalizeMacUppercasesAndTrims() {
        assertEquals(a, EqDevices.normalizeMac(" 38:d5:18:47:31:a4 "))
        assertEquals(a, EqDevices.normalizeMac(a))
    }

    @Test
    fun normalizeMacRejectsOtherSpellings() {
        listOf(
            "38D5184731A4",
            "38-D5-18-47-31-A4",
            "38:D5:18:47:31",
            "38:D5:18:47:31:A4:B7",
            "38:D5:18:47:31:AG",
            "8:D5:18:47:31:A4",
            "",
        ).forEach { assertNull(it, EqDevices.normalizeMac(it)) }
    }

    @Test
    fun normalizeAllSortsAndDeduplicates() {
        assertEquals(listOf(b, a), EqDevices.normalizeAll(listOf(a, b, a.lowercase(), "junk")))
        assertEquals(emptyList<String>(), EqDevices.normalizeAll(listOf("", " ")))
    }

    @Test
    fun withDeviceAddsAndRemoves() {
        assertEquals(listOf(a), EqDevices.withDevice(emptyList(), a, registered = true))
        assertEquals(listOf(b, a), EqDevices.withDevice(listOf(a), b, registered = true))
        assertEquals(listOf(a), EqDevices.withDevice(listOf(a), a.lowercase(), registered = true))
        assertEquals(listOf(b), EqDevices.withDevice(listOf(a, b), a, registered = false))
        assertEquals(emptyList<String>(), EqDevices.withDevice(listOf(a), a, registered = false))
    }

    @Test
    fun withDeviceLeavesTheListAloneForAnUnusableMac() {
        assertEquals(listOf(a), EqDevices.withDevice(listOf(a), "not a mac", registered = true))
        assertEquals(listOf(a), EqDevices.withDevice(listOf(a), "not a mac", registered = false))
    }

    @Test
    fun onlySuccessMovesTheRecord() {
        val before = listOf(a)
        val sent = listOf(b, a)
        assertEquals(sent, EqDevices.recordAfter(before, sent, EqDevicesOutcome.OK))
        EqDevicesOutcome.entries.filterNot { it == EqDevicesOutcome.OK }.forEach { outcome ->
            assertEquals("$outcome", before, EqDevices.recordAfter(before, sent, outcome))
        }
    }

    @Test
    fun markerIsFoundOnAnyLine() {
        assertTrue(EqDevices.ranScript("${EqDevices.BEGIN_MARKER}\ndone\n"))
        assertTrue(EqDevices.ranScript("su: granted to uid 10246\n${EqDevices.BEGIN_MARKER}\n"))
        assertTrue(EqDevices.ranScript("  ${EqDevices.BEGIN_MARKER}  \n"))
    }

    @Test
    fun markerNeedsAWholeLine() {
        assertFalse(EqDevices.ranScript("echo ${EqDevices.BEGIN_MARKER} first"))
        assertFalse(EqDevices.ranScript("${EqDevices.BEGIN_MARKER}_2"))
        assertFalse(EqDevices.ranScript(""))
    }

    @Test
    fun noMarkerAtAllMeansRootWasDenied() {
        assertEquals(EqDevicesOutcome.ROOT_DENIED, EqDevices.outcomeOf("permission denied\n", 1))
        assertEquals(EqDevicesOutcome.ROOT_DENIED, EqDevices.outcomeOf("", EqDevices.EXIT_OK))
        assertEquals(EqDevicesOutcome.ROOT_DENIED, EqDevices.outcomeOf(EqDevices.BEGIN_MARKER, 0))
    }

    @Test
    fun suMarkerWithoutTheScriptMarkerMeansTheScriptNeverRan() {
        val output = "${EqDevices.SU_MARKER}\nsh: can't open '/data/adb/modules/x/eq_devices.sh'\n"
        assertEquals(EqDevicesOutcome.SCRIPT_MISSING, EqDevices.outcomeOf(output, 127))
        assertEquals(EqDevicesOutcome.SCRIPT_MISSING, EqDevices.outcomeOf(EqDevices.SU_MARKER, 0))
    }

    @Test
    fun bothMarkersAndZeroIsSuccess() {
        val output = "${EqDevices.SU_MARKER}\n${EqDevices.BEGIN_MARKER}\n"
        assertEquals(EqDevicesOutcome.OK, EqDevices.outcomeOf(output, EqDevices.EXIT_OK))
    }

    @Test
    fun exitCodesAreDistinct() {
        val codes = listOf(
            EqDevices.EXIT_BAD_INPUT,
            EqDevices.EXIT_NO_STATE,
            EqDevices.EXIT_XML_FAILED,
            EqDevices.EXIT_APPLY_FAILED,
            EqDevices.EXIT_AUDIOSERVER_TIMEOUT,
        )
        assertEquals(codes.size, codes.distinct().size)
        assertFalse(EqDevices.EXIT_OK in codes)
    }

    @Test
    fun unknownExitCodeIsStillAFailure() {
        val known = listOf(
            EqDevices.EXIT_BAD_INPUT,
            EqDevices.EXIT_NO_STATE,
            EqDevices.EXIT_XML_FAILED,
            EqDevices.EXIT_APPLY_FAILED,
            EqDevices.EXIT_AUDIOSERVER_TIMEOUT,
            99,
        )
        val output = "${EqDevices.SU_MARKER}\n${EqDevices.BEGIN_MARKER}\n"
        known.forEach { code ->
            assertEquals("code $code", EqDevicesOutcome.SCRIPT_FAILED, EqDevices.outcomeOf(output, code))
        }
    }

    @Test
    fun diagnosticsKeepTheLastLinesWithoutTheMarkers() {
        val result = EqDevicesResult(
            outcome = EqDevicesOutcome.SCRIPT_FAILED,
            exitCode = EqDevices.EXIT_XML_FAILED,
            output = "${EqDevices.SU_MARKER}\n${EqDevices.BEGIN_MARKER}\nstep 1\n\nstep 2\nstep 3\nstep 4\n",
        )
        assertEquals("step 2\nstep 3\nstep 4", result.diagnostics())
        assertEquals("step 4", result.diagnostics(limit = 1))
        assertEquals("", EqDevicesResult(EqDevicesOutcome.NO_MODULE).diagnostics())
        val bare = EqDevicesResult(
            outcome = EqDevicesOutcome.SCRIPT_MISSING,
            output = "${EqDevices.SU_MARKER}\n",
        )
        assertEquals("", bare.diagnostics())
    }
}
