package io.github.mame1839.codecanchor

import io.github.mame1839.codecanchor.audio.EqStat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EqStatTest {

    private val sample = """
        version=1 slots=4 slot_size=256
        slot ctx                io     frames     ch   rate    block  age       pid    gain_mB in_dBFS  out_dBFS state
        0    0x7b2f001122334455 5      1234567    2    48000   960    0.02s     3210   -300    -12.3    -9.3     enabled configured
             buf: in=0x7000 out=0x7000 (in-place)  frames in/out=960/960 samples=1920 cfg_fmt=1
             peak: float in=0.242 out=0.342 / int32 in=0 out=0
             session=-2 (DEVICE = <deviceEffects> 経由 = イヤホン側)
             param: 枠=0 適用済み gen=3 / 共有メモリ gen=3 bands=5
        1    0x7b2f00aabbccddee 7      42         2    48000   960    never     3210   0       silent   silent   enabled configured
             buf: in=0x8000 out=0x8000 (in-place)  frames in/out=0/0 samples=0 cfg_fmt=1
             peak: float in=0 out=0 / int32 in=0 out=0
             session=0 (DEVICE でない = postprocess 等。イヤホンの設定を書く先ではない)

        age = 最後に process() が回ってからの経過。読み方:
          never          このインスタンスで process() が 1 度も呼ばれていない。
        frames は 2 回実行して差を見ること。age だけでは「いま回っているか」しか分からない。
    """.trimIndent()

    @Test
    fun parsesSlotsWithLevelsAndSession() {
        val slots = EqStat.parse(sample)
        assertEquals(2, slots.size)

        val device = slots[0]
        assertEquals(0, device.slot)
        assertEquals(1_234_567L, device.frames)
        assertEquals("0.02s", device.age)
        assertNotNull(device.inPeakDbfs)
        assertEquals(-12.3, device.inPeakDbfs!!, 1e-9)
        assertEquals(-9.3, device.outPeakDbfs!!, 1e-9)
        assertTrue(device.enabled)
        assertTrue("session=-2 の行が deviceSession に反映されていない", device.deviceSession)

        val post = slots[1]
        assertEquals(1, post.slot)
        assertEquals(42L, post.frames)
        assertEquals("never", post.age)
        assertNull("silent が null に読めていない", post.inPeakDbfs)
        assertNull(post.outPeakDbfs)
        assertTrue(post.enabled)
        assertEquals(false, post.deviceSession)
    }

    @Test
    fun irrelevantTextParsesToNothing() {
        assertEquals(0, EqStat.parse("").size)
        assertEquals(0, EqStat.parse("magic=0x00000000 — まだエフェクトのインスタンスが 1 つも作られていない").size)
        assertEquals(0, EqStat.parse(sample.lineSequence().filterNot { it.trimStart().firstOrNull()?.isDigit() == true }.joinToString("\n")).size)
    }

    @Test
    fun deviceSessionMatchesTheNativeHeader() {
        val header = repoFile("app/src/main/cpp/ca_eq_shm.h")
        val native = Regex("""#define\s+CA_AUDIO_SESSION_DEVICE\s+\((-?\d+)\)""")
            .find(header.readText())
            ?.groupValues
            ?.get(1)
            ?.toInt()
        assertEquals(
            "${header.absolutePath} の CA_AUDIO_SESSION_DEVICE を読めなかった。" +
                "書き方を変えたなら、この検査の読み方も直すこと",
            true,
            native != null,
        )
        assertEquals(native, EqStat.DEVICE_SESSION)
    }

    private fun repoFile(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val here = File(dir, relative)
            if (here.isFile) return here
            dir = dir.parentFile
        }
        throw AssertionError("$relative が見つからない (作業ディレクトリ ${File("").absolutePath})")
    }
}
