package io.github.mame1839.codecanchor

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.github.mame1839.codecanchor.core.EqDelivery
import io.github.mame1839.codecanchor.core.EqMode
import io.github.mame1839.codecanchor.core.EqParamsOutcome
import io.github.mame1839.codecanchor.core.EqParamsResult
import io.github.mame1839.codecanchor.core.EqPrecision
import io.github.mame1839.codecanchor.core.EqSettings
import io.github.mame1839.codecanchor.ui.EqParamsReport
import io.github.mame1839.codecanchor.ui.EqPrecisionFallbackNotice
import io.github.mame1839.codecanchor.ui.deliveryMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * 押し込みの結果を**出すか黙るか**。文言そのものではなく判断を見る。
 *
 * [deliveryMessage] を直接呼ぶのは、状態の置き場 (`MainViewModel.eqParamsReport`) が
 * 外から書けないため — 経路ごと通すには実機の `su` が要る。
 */
@RunWith(RobolectricTestRunner::class)
class EqNoticeTest {

    @get:Rule
    val compose = createComposeRule()

    private val mac = "AA:BB:CC:DD:EE:11"

    private val app: Application get() = RuntimeEnvironment.getApplication()

    private fun string(id: Int, vararg args: Any): String = app.getString(id, *args)

    private fun result(outcome: EqParamsOutcome, exitCode: Int, stdout: String = "CA_EQ_SET_BEGIN\n") =
        EqParamsResult(outcome = outcome, exitCode = exitCode, stdout = stdout, stderr = "")

    // --- deliveryMessage: 出す / 黙る ---------------------------------------

    @Test
    fun slotsFullSpeaksAndNoLiveSlotStaysSilent() {
        val messages = mutableMapOf<EqParamsOutcome, String?>()
        compose.setContent {
            MaterialTheme {
                for ((outcome, code) in listOf(
                    EqParamsOutcome.APPLIED to 0,
                    EqParamsOutcome.NO_LIVE_SLOT to 13,
                    EqParamsOutcome.SLOTS_FULL to 16,
                )) {
                    messages[outcome] = deliveryMessage(EqParamsReport(mac, result(outcome, code)))
                }
            }
        }
        compose.waitForIdle()

        assertNull(messages[EqParamsOutcome.APPLIED])
        // 13 は黙る (2026-08-19 のリーダ決定)。理由は EqNotice.kt の分岐のコメント。
        assertNull(messages[EqParamsOutcome.NO_LIVE_SLOT])
        // 16 は出す。以前は 13 に丸められて「まだ届いていない (再生しろ)」と嘘を言っていた。
        assertEquals(string(R.string.eq_delivery_slots_full), messages[EqParamsOutcome.SLOTS_FULL])
    }

    @Test
    fun unknownExitCodesKeepTheNumberVisible() {
        var message: String? = null
        compose.setContent {
            MaterialTheme {
                message = deliveryMessage(EqParamsReport(mac, result(EqParamsOutcome.UNKNOWN, 42)))
            }
        }
        compose.waitForIdle()
        assertEquals(string(R.string.eq_delivery_failed_unknown, 42), message)
        assertTrue("数値が残っていない: $message", message.orEmpty().contains("42"))
    }

    // --- EqPrecisionFallbackNotice: 4 つの門を 1 つずつ閉じる -----------------

    private data class NoticeState(
        val eq: EqSettings,
        val delivery: EqDelivery,
        val report: EqParamsReport?,
    )

    private fun highPrecision(mode: Int = EqMode.GRAPHIC, precision: Int = EqPrecision.HIGH) =
        EqSettings(enabled = true, mode = mode, precision = precision)

    private fun report(why: String?, mac: String = this.mac): EqParamsReport {
        val line = if (why == null) "" else "CA_EQ_FIR why=$why rate=44100 block=896 frames=10 age_ms=5\n"
        return EqParamsReport(mac, result(EqParamsOutcome.APPLIED, 0, "CA_EQ_SET_BEGIN\n$line"))
    }

    @Test
    fun theNoticeShowsOnlyWhileEveryGateIsOpen() {
        var state by mutableStateOf(
            NoticeState(eq = highPrecision(), delivery = EqDelivery.LIVE, report = report("block_unfit")),
        )
        compose.setContent {
            MaterialTheme {
                EqPrecisionFallbackNotice(
                    eq = state.eq,
                    delivery = state.delivery,
                    report = state.report,
                    mac = mac,
                )
            }
        }
        val text = string(R.string.eq_precision_fallback)
        fun assertShown(shown: Boolean, label: String) {
            compose.waitForIdle()
            val node = compose.onNodeWithText(text)
            if (shown) node.assertExists(label) else node.assertDoesNotExist()
        }
        val shown = state
        assertShown(true, "全部の門が開いているのに出ない")

        // 標準を選んでいる (要求していないものは「落ちて」いない)
        state = shown.copy(eq = highPrecision(precision = EqPrecision.STANDARD))
        assertShown(false, "標準なのに出た")

        // パラメトリック (行そのものが出ない画面だが、判断も同じ側に倒す)
        state = shown.copy(eq = highPrecision(mode = EqMode.PARAMETRIC))
        assertShown(false, "パラメトリックなのに出た")

        // この機器が枠の持ち主ではない
        for (delivery in listOf(EqDelivery.IDLE, EqDelivery.OTHER, EqDelivery.AMBIGUOUS)) {
            state = shown.copy(delivery = delivery)
            assertShown(false, "$delivery なのに出た")
        }

        // 別の機器への押し込みの結果は、この機器の画面に映さない
        state = shown.copy(report = report("block_unfit", mac = "AA:BB:CC:DD:EE:99"))
        assertShown(false, "別の機器の報告で出た")

        // 報告そのものが無い
        state = shown.copy(report = null)
        assertShown(false, "報告が無いのに出た")

        // FIR が鳴っている / 行が無い / 未知の綴り — どれも出さない
        for (why in listOf("running", "warming", "some_future_reason", null)) {
            state = shown.copy(report = report(why))
            assertShown(false, "why=$why なのに出た")
        }

        // 3 つは全部出る
        for (why in listOf("not_addressable", "no_arena", "block_unfit")) {
            state = shown.copy(report = report(why))
            assertShown(true, "why=$why で出ない")
        }
    }

    // --- 文言のロケール漏れ --------------------------------------------------

    /**
     * **新しいキーが 18 ロケール全部にあること。**抜けた言語は既定の英語に落ちるだけで
     * 画面は壊れないので、ここで見ないと気づけない (EqPrecisionRowTest と同じ形)。
     * `eq_delivery_not_live` の削除も同じループで見張る — 統合で片側だけ復活しても
     * git は衝突と報告しない。
     */
    @Test
    fun theNewStringExistsInEveryLocaleAndTheOldOneIsGone() {
        val res = repoDir().resolve("app/src/main/res")
        val dirs = res.listFiles()
            ?.filter { it.isDirectory && File(it, "strings.xml").isFile }
            ?.filter { it.name == "values" || it.name.startsWith("values-") }
            ?.filterNot { it.name == "values-night" }
            .orEmpty()
        assertTrue("ロケールが 18 未満しか見つからない (${dirs.size})", dirs.size >= 18)

        for (dir in dirs) {
            val xml = File(dir, "strings.xml").readText()
            assertTrue("${dir.name} に eq_delivery_slots_full が無い", xml.contains("\"eq_delivery_slots_full\""))
            assertFalse("${dir.name} に eq_delivery_not_live が残っている", xml.contains("\"eq_delivery_not_live\""))
        }
    }

    private fun repoDir(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "app/src/main/res/values/strings.xml").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("res が見つからない (user.dir=${System.getProperty("user.dir")})")
    }
}
