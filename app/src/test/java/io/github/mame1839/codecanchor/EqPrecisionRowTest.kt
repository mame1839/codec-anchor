package io.github.mame1839.codecanchor

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import io.github.mame1839.codecanchor.core.EqBand
import io.github.mame1839.codecanchor.core.EqMode
import io.github.mame1839.codecanchor.core.EqPrecision
import io.github.mame1839.codecanchor.core.EqSettings
import io.github.mame1839.codecanchor.core.EqSolver
import io.github.mame1839.codecanchor.ui.EqSection
import io.github.mame1839.codecanchor.ui.MainViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * 曲線の再現 (標準 / 高精度) の行を、**出す場所と出さない場所**。
 *
 * - グラフィックにだけ出す。パラメトリックは biquad が定義どおりの厳密値なので、
 *   切り替えても何も変わらないトグルになる
 * - フラットのスロットでは編集の行が全部消えるので、ここも出ない
 */
@RunWith(RobolectricTestRunner::class)
class EqPrecisionRowTest {

    @get:Rule
    val compose = createComposeRule()

    private val mac = "AA:BB:CC:DD:EE:07"

    private val app: Application get() = RuntimeEnvironment.getApplication()

    private fun string(id: Int, vararg args: Any): String = app.getString(id, *args)

    private fun show(vm: MainViewModel) {
        compose.setContent {
            MaterialTheme {
                Column { EqSection(vm = vm, mac = mac, profile = vm.config.profileFor(mac)!!) }
            }
        }
    }

    private fun tap(text: String) {
        compose.onNode(hasText(text) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun viewModel(mode: Int = EqMode.GRAPHIC): MainViewModel = MainViewModel(app).also {
        it.ensureProfile(mac)
        it.updateEq(mac) {
            EqSettings(
                enabled = true,
                mode = mode,
                bandCount = 10,
                bands = EqSolver.centerFrequencies(10).map { hz ->
                    EqBand(freqHz = hz, q100 = 100, gainDb10 = 30)
                },
            )
        }
    }

    @Test
    fun graphicShowsTheRowNextToTheBandCount() {
        show(viewModel())
        compose.onNodeWithText(string(R.string.eq_precision)).assertExists()
        compose.onNodeWithText(string(R.string.eq_band_count)).assertExists()
        // 既定は標準。行の下に選択中の値が出る。
        compose.onNodeWithText(string(R.string.eq_precision_standard)).assertExists()
    }

    @Test
    fun parametricDoesNotShowTheRow() {
        show(viewModel(EqMode.PARAMETRIC))
        compose.onNodeWithText(string(R.string.eq_mode)).assertExists()
        compose.onNodeWithText(string(R.string.eq_precision)).assertDoesNotExist()
    }

    @Test
    fun flatDoesNotShowTheRow() {
        show(viewModel())
        compose.onNodeWithText(string(R.string.eq_precision)).assertExists()
        tap(string(R.string.eq_slot_flat))
        compose.onNodeWithText(string(R.string.eq_precision)).assertDoesNotExist()
    }

    /** 選ぶと設定に残ること。**残らないと、押しても何も起きない行になる。** */
    @Test
    fun pickingHighAccuracyIsStored() {
        val vm = viewModel()
        show(vm)
        assertEquals(EqPrecision.STANDARD, vm.config.profileFor(mac)!!.eq.precision)

        tap(string(R.string.eq_precision))
        tap(string(R.string.eq_precision_high))

        assertEquals(EqPrecision.HIGH, vm.config.profileFor(mac)!!.eq.precision)
        compose.onNodeWithText(string(R.string.eq_precision_high)).assertExists()
    }

    /**
     * 文言の縛り (eq-fir-design.md §0 と段 3 の指示)。**18 ロケール全部を見る** —
     * `getString` は既定ロケールしか返さないので、それだけだと 17 言語が無検査になる。
     *
     * 言語に依らず言えることだけを見張る:
     * - **数字を書かない** — 遅延 (0 ms) も精度も、こちらから数を約束しない。
     *   20〜32 Hz には物理の床があり、「0 ms」はこちらから言うと無かった心配を作る
     * - **5 つのキーがどのロケールにも揃っている** — 抜けた言語は既定の英語に落ちるだけで、
     *   画面は壊れないので気づけない
     *
     * 言語ごとの言い回し (「高速」「高音質」など) は、既定の英語と日本語だけ名指しで見る。
     */
    @Test
    fun theWordingMakesNoPromiseAboutDelayOrNumbers() {
        val keys = listOf(
            "eq_precision",
            "eq_precision_standard",
            "eq_precision_standard_desc",
            "eq_precision_high",
            "eq_precision_high_desc",
        )
        val res = repoDir().resolve("app/src/main/res")
        val dirs = res.listFiles()
            ?.filter { it.isDirectory && File(it, "strings.xml").isFile }
            ?.filter { it.name == "values" || it.name.startsWith("values-") }
            ?.filterNot { it.name == "values-night" }
            .orEmpty()
        assertTrue("ロケールが 18 未満しか見つからない (${dirs.size})", dirs.size >= 18)

        for (dir in dirs) {
            val xml = File(dir, "strings.xml").readText()
            for (key in keys) {
                val value = Regex("""<string name="$key">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                    .find(xml)?.groupValues?.get(1)
                assertNotNull("${dir.name} に $key が無い", value)
                assertFalse(
                    "${dir.name} の $key が数字を書いている: $value",
                    Regex("""\d""").containsMatchIn(value!!),
                )
            }
        }

        val en = File(res, "values/strings.xml").readText()
        for (word in listOf("delay", "latency", "faster", "sound quality")) {
            assertFalse("英語の文言に \"$word\"", precisionStrings(en, keys).any { it.lowercase().contains(word) })
        }
        val ja = File(res, "values-ja/strings.xml").readText()
        for (word in listOf("遅延", "高速", "高音質")) {
            assertFalse("日本語の文言に \"$word\"", precisionStrings(ja, keys).any { it.contains(word) })
        }
    }

    private fun precisionStrings(xml: String, keys: List<String>): List<String> = keys.mapNotNull { key ->
        Regex("""<string name="$key">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)
    }

    /** リポジトリの根。**見つからなければ落とす** (黙って読み飛ばすと何も見ていないテストになる)。 */
    private fun repoDir(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "app/src/main/res/values/strings.xml").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("res が見つからない (user.dir=${System.getProperty("user.dir")})")
    }
}
