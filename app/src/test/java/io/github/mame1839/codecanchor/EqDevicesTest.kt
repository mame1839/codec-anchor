package io.github.mame1839.codecanchor

import io.github.mame1839.codecanchor.core.EqDevices
import io.github.mame1839.codecanchor.core.EqDevicesOutcome
import io.github.mame1839.codecanchor.core.EqDevicesResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * root へ渡す一覧の作り方と、3 分類の判定。
 *
 * ここが間違うと、症状は「登録したのに効かない」か「別のイヤホンの登録が消える」になる。
 * どちらも実機でしか出ず、原因が MAC の 1 文字なので追いにくい。
 */
class EqDevicesTest {

    private val a = "38:D5:18:47:31:A4"
    private val b = "01:23:45:67:89:AB"

    @Test
    fun normalizeMacUppercasesAndTrims() {
        assertEquals(a, EqDevices.normalizeMac(" 38:d5:18:47:31:a4 "))
        assertEquals(a, EqDevices.normalizeMac(a))
    }

    // コロン無し・区切り違い・桁数違いは受けない。root 側はそのまま XML の address に書くので、
    // 通してしまうと「書けたのに一致しない」行が入り、原因が XML を読むまで分からない。
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

    // 同じ集合なら毎回同じバイト列が流れること。並びが揺れると root 側の出力の差分が読めなくなる。
    @Test
    fun normalizeAllSortsAndDeduplicates() {
        assertEquals(listOf(b, a), EqDevices.normalizeAll(listOf(a, b, a.lowercase(), "junk")))
        assertEquals(emptyList<String>(), EqDevices.normalizeAll(listOf("", " ")))
    }

    @Test
    fun withDeviceAddsAndRemoves() {
        assertEquals(listOf(a), EqDevices.withDevice(emptyList(), a, registered = true))
        assertEquals(listOf(b, a), EqDevices.withDevice(listOf(a), b, registered = true))
        // 二重に足しても増えない。
        assertEquals(listOf(a), EqDevices.withDevice(listOf(a), a.lowercase(), registered = true))
        assertEquals(listOf(b), EqDevices.withDevice(listOf(a, b), a, registered = false))
        // 空の入力 = 全解除。これも正当な依頼。
        assertEquals(emptyList<String>(), EqDevices.withDevice(listOf(a), a, registered = false))
    }

    // 落とすほうへ倒すと、その機器を「登録済み」と記録したまま XML から消すことになる。
    @Test
    fun withDeviceLeavesTheListAloneForAnUnusableMac() {
        assertEquals(listOf(a), EqDevices.withDevice(listOf(a), "not a mac", registered = true))
        assertEquals(listOf(a), EqDevices.withDevice(listOf(a), "not a mac", registered = false))
    }

    // 記録を進めてよいのは成功したときだけ。失敗して進めると、XML に無いものを「登録済み」と出す。
    // TIMEOUT が一番間違えやすい — 打ち切っただけでスクリプトは走り切ったかもしれないが、
    // 「たぶん成功した」で進めると、外れたときに直す手立てが無くなる。
    @Test
    fun onlySuccessMovesTheRecord() {
        val before = listOf(a)
        val sent = listOf(b, a)
        assertEquals(sent, EqDevices.recordAfter(before, sent, EqDevicesOutcome.OK))
        EqDevicesOutcome.entries.filterNot { it == EqDevicesOutcome.OK }.forEach { outcome ->
            assertEquals("$outcome", before, EqDevices.recordAfter(before, sent, outcome))
        }
    }

    // 印の位置は契約に含めない。root マネージャが先に自前の 1 行を出す端末がある。
    @Test
    fun markerIsFoundOnAnyLine() {
        assertTrue(EqDevices.ranScript("${EqDevices.BEGIN_MARKER}\ndone\n"))
        assertTrue(EqDevices.ranScript("su: granted to uid 10246\n${EqDevices.BEGIN_MARKER}\n"))
        assertTrue(EqDevices.ranScript("  ${EqDevices.BEGIN_MARKER}  \n"))
    }

    // 部分一致で通すと、印に触れただけのログ行でも「走った」ことになる。
    @Test
    fun markerNeedsAWholeLine() {
        assertFalse(EqDevices.ranScript("echo ${EqDevices.BEGIN_MARKER} first"))
        assertFalse(EqDevices.ranScript("${EqDevices.BEGIN_MARKER}_2"))
        assertFalse(EqDevices.ranScript(""))
    }

    // su の印が無ければ、終了コードが何であれ su が通っていない。
    @Test
    fun noMarkerAtAllMeansRootWasDenied() {
        assertEquals(EqDevicesOutcome.ROOT_DENIED, EqDevices.outcomeOf("permission denied\n", 1))
        assertEquals(EqDevicesOutcome.ROOT_DENIED, EqDevices.outcomeOf("", EqDevices.EXIT_OK))
        // スクリプトの印だけがあることは起こらないが、su を通った証拠にはしない。
        assertEquals(EqDevicesOutcome.ROOT_DENIED, EqDevices.outcomeOf(EqDevices.BEGIN_MARKER, 0))
    }

    // ここを ROOT_DENIED と混ぜると、スクリプトが置かれていないだけのときに
    // ユーザを root マネージャへ行かせることになる。そこには原因が無い。
    @Test
    fun suMarkerWithoutTheScriptMarkerMeansTheScriptNeverRan() {
        val output = "${EqDevices.SU_MARKER}\nsh: can't open '/data/adb/modules/x/eq_devices.sh'\n"
        assertEquals(EqDevicesOutcome.SCRIPT_MISSING, EqDevices.outcomeOf(output, 127))
        // 終了コードが 0 でも、走っていないなら成功にしない。
        assertEquals(EqDevicesOutcome.SCRIPT_MISSING, EqDevices.outcomeOf(EqDevices.SU_MARKER, 0))
    }

    @Test
    fun bothMarkersAndZeroIsSuccess() {
        val output = "${EqDevices.SU_MARKER}\n${EqDevices.BEGIN_MARKER}\n"
        assertEquals(EqDevicesOutcome.OK, EqDevices.outcomeOf(output, EqDevices.EXIT_OK))
    }

    // 終了コードは 0 とも互いとも重ならないこと。重なると別の失敗が同じ文言になる。
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

    // 未知のコードも SCRIPT_FAILED。ここで OK に倒すと、後からコードが増えた日に
    // 「失敗したのに成功と出る」になる。画面側の else はこの経路で効く。
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

    // 診断は最後の数行。印は判定に使ったもので読ませる内容ではないので、残ると邪魔をする
    // (スクリプトが 1 行も出さずに落ちると、印だけが「原因」として表示されてしまう)。
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
        // 印しか出ていないなら、添える行は無い。
        val bare = EqDevicesResult(
            outcome = EqDevicesOutcome.SCRIPT_MISSING,
            output = "${EqDevices.SU_MARKER}\n",
        )
        assertEquals("", bare.diagnostics())
    }
}
