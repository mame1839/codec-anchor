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
        // 既定値 (false) のままだと、decode がキーを取りこぼしても等値で素通りする。
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
            preampAuto = false,
            preampDb10 = -42,
        ),
        session = JSONObject("""{"axis":1,"step":20,"answers":[0,2,1],"est":[40,-15]}"""),
        savedAt = 1_723_400_000_000L,
    )

    // session (エンジンの JSON) は中身を解釈しない約束なので、文字列で突き合わせる。
    // data class の equals は JSONObject では参照比較になり、同値でも一致しない。
    // 残りのフィールドは session を同じ参照に差し替えたうえで equals で見る。
    private fun assertSame(expected: EqFinderSaved, actual: EqFinderSaved?) {
        requireNotNull(actual)
        assertEquals(expected.session.toString(), actual.session.toString())
        assertEquals(expected, actual.copy(session = expected.session))
    }

    @Test
    fun recordRoundTrips() {
        assertSame(record, EqFinderSaved.decode(record.encode()))
    }

    @Test
    fun brokenStorageFallsBackToNothing() {
        assertNull(EqFinderSaved.decode(null))
        assertNull(EqFinderSaved.decode(""))
        assertNull(EqFinderSaved.decode("{"))
        assertNull(EqFinderSaved.decode("""{"other":1}"""))
    }

    // 将来の版が形を変えたら、古いアプリは読まずに「保存なし」へ倒す。
    @Test
    fun unknownVersionIsRejected() {
        val next = JSONObject(record.encode()).put("v", 2).toString()
        assertNull(EqFinderSaved.decode(next))
    }

    // セッション本体・機器・曲のどれが欠けても再開はできない。部分的に読んで
    // 別の機器や別の曲で「続きから」を出すほうが害が大きい。
    @Test
    fun recordsMissingTheEssentialsAreRejected() {
        for (key in listOf("mac", "uri", "session", "len")) {
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
