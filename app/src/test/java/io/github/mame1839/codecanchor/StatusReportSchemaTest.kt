package io.github.mame1839.codecanchor

import io.github.mame1839.codecanchor.core.EqSupport
import io.github.mame1839.codecanchor.core.StatusReport
import io.github.mame1839.codecanchor.xposed.A2dpHook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Modifier

/**
 * 音響処理の版 (`StatusReport.eqSchema`) の書き手が居ること、そしてその定数が `const` のままで
 * あること。
 *
 * **どちらも一度壊れている / 壊れうる形で、症状が実機にしか出ない:**
 *
 * - 書き手を忘れると既定の 0 が乗り、`0 < EqSupport.SCHEMA` なので**アプリが常に
 *   「フックが古い」を出す。**器 (フィールド + toJson + fromJson) と読み手だけ足して
 *   送らせる手順が抜けていた、という形で実際に起きた
 * - `const` を外すと Bluetooth プロセスが `EqSupport` を読み込み、`AudioEffect` に触る。
 *   **コンパイルもテストも通り、実機のフックだけが落ちる**
 */
@RunWith(RobolectricTestRunner::class)
class StatusReportSchemaTest {

    private fun report(): StatusReport = A2dpHook.buildReport(
        hostPackage = "com.android.bluetooth",
        configHash = 1234,
        configLoaded = true,
        a2dpOffloadEnabled = false,
        devices = emptyList(),
        codecNames = emptyMap(),
        timestamp = 1_700_000_000_000L,
    )

    // フックが組み立てた報告に版が乗ること。乗らないと全ビルドで「フックが古い」になる。
    @Test
    fun theHookStampsItsSchema() {
        assertEquals(EqSupport.SCHEMA, report().eqSchema)
    }

    // 押していても JSON に出ていなければアプリには届かない。アプリが読むのは往復の後。
    @Test
    fun theSchemaSurvivesTheRoundTrip() {
        val decoded = StatusReport.decode(report().encode())
        assertEquals(EqSupport.SCHEMA, decoded?.eqSchema)
    }

    // 版を知らない古いフックの報告は 0 に落ちること。ここが SCHEMA に落ちると、
    // 古いフックを新しいと誤認して「設定が届いていません」が出続ける。
    @Test
    fun aReportWithoutTheFieldStillReadsAsAnOldHook() {
        val old = StatusReport.decode("""{"moduleVersion":"0.2.1","hostPackage":"com.android.bluetooth"}""")
        assertEquals(0, old?.eqSchema)
        assertTrue("0 が「古い」でなくなると判定そのものが無意味になる", 0 < EqSupport.SCHEMA)
    }

    /**
     * `SCHEMA` が `const val` であること。
     *
     * `object` の `const val` は **public static final** のフィールドになり、**getter を持たない**
     * (参照側はリテラルに畳まれる)。`const` を外すと backing field は **private** になり、
     * **`getSCHEMA()` が生える** — フックがそれを呼ぶために `EqSupport` を読み込むようになる。
     * 見分けはこの 2 点で付く。
     */
    @Test
    fun theSchemaIsACompileTimeConstant() {
        val field = EqSupport::class.java.getDeclaredField("SCHEMA")
        val modifiers = field.modifiers
        assertTrue("static でない", Modifier.isStatic(modifiers))
        assertTrue("final でない", Modifier.isFinal(modifiers))
        assertTrue("public でない = const が外れている", Modifier.isPublic(modifiers))
        assertNull(
            "getSCHEMA() が生えている = const が外れている。フックが EqSupport を読み込むようになる",
            EqSupport::class.java.methods.firstOrNull { it.name == "getSCHEMA" },
        )
    }
}
