package io.github.mame1839.codecanchor

import io.github.mame1839.codecanchor.bridge.EqFinderSaved
import io.github.mame1839.codecanchor.bridge.EqFinderStore
import io.github.mame1839.codecanchor.core.EqBand
import io.github.mame1839.codecanchor.core.EqBandType
import io.github.mame1839.codecanchor.core.EqMode
import io.github.mame1839.codecanchor.core.EqSettings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class EqFinderStoreTest {

    private val record = EqFinderSaved(
        mac = "AA:BB:CC:DD:EE:FF",
        uri = "content://com.android.providers.media.documents/document/audio%3A12345",
        startMs = 43_000,
        lengthMs = 20_000,
        includeMid = true,
        fineTune = true,
        done = 17,
        pcmHash = -0x5DEECE66DL,
        base = EqSettings(
            enabled = true,
            mode = EqMode.PARAMETRIC,
            bandCount = 15,
            bands = listOf(
                EqBand(freqHz = 105, q100 = 71, gainDb10 = 25, type = EqBandType.LOW_SHELF),
                EqBand(freqHz = 2_500, q100 = 71, gainDb10 = -15, type = EqBandType.HIGH_SHELF),
            ),
            preampDb10 = -42,
        ),
        session = JSONObject("""{"axis":1,"step":20,"answers":[0,2,1],"est":[40,-15]}"""),
        savedAt = 1_723_400_000_000L,
    )

    private fun assertSame(expected: EqFinderSaved, actual: EqFinderSaved?) {
        requireNotNull(actual)
        assertEquals(expected.session.toString(), actual.session.toString())
        assertEquals(expected, actual.copy(session = expected.session))
    }

    private val liveRecord = record.copy(
        live = true,
        uri = null,
        startMs = 0,
        lengthMs = 0,
        pcmHash = 0L,
    )

    @Test
    fun recordRoundTrips() {
        assertSame(record, EqFinderSaved.decode(record.encode()))
    }

    @Test
    fun liveRecordRoundTrips() {
        assertSame(liveRecord, EqFinderSaved.decode(liveRecord.encode()))
    }

    @Test
    fun aV1RecordWithoutTheMaterialKeyReadsAsLoop() {
        val legacy = JSONObject(record.encode())
        assertTrue("前提が崩れた: ループの記録に材料キーが入っている", !legacy.has("live"))
        val decoded = EqFinderSaved.decode(legacy.toString())
        requireNotNull(decoded)
        assertTrue(!decoded.live)
        assertSame(record, decoded)
    }

    @Test
    fun aLiveRecordCarriesNoSongKeysForOldBuildsToTripOn() {
        val o = JSONObject(liveRecord.encode())
        assertTrue(!o.has("uri"))
        assertTrue(!o.has("start"))
        assertTrue(!o.has("len"))
        val stray = JSONObject(liveRecord.encode()).put("uri", "content://x").toString()
        assertNull(EqFinderSaved.decode(stray)!!.uri)
    }

    @Test
    fun brokenStorageFallsBackToNothing() {
        assertNull(EqFinderSaved.decode(null))
        assertNull(EqFinderSaved.decode(""))
        assertNull(EqFinderSaved.decode("{"))
        assertNull(EqFinderSaved.decode("""{"other":1}"""))
    }

    @Test
    fun unknownVersionIsRejected() {
        val next = JSONObject(record.encode()).put("v", 2).toString()
        assertNull(EqFinderSaved.decode(next))
    }

    @Test
    fun recordsMissingTheEssentialsAreRejected() {
        for (key in listOf("mac", "uri", "session", "len", "base")) {
            val broken = JSONObject(record.encode()).apply { remove(key) }.toString()
            assertNull("$key 抜きで読めてしまった", EqFinderSaved.decode(broken))
        }
    }

    @Test
    fun storeSavesLoadsAndClears() {
        val store = EqFinderStore(RuntimeEnvironment.getApplication())
        assertNull(store.load())
        store.save(record)
        assertSame(record, store.load())
        store.clear()
        assertNull(store.load())
    }
}
