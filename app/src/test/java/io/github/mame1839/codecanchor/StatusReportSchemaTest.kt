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

    @Test
    fun theHookStampsItsSchema() {
        assertEquals(EqSupport.SCHEMA, report().eqSchema)
    }

    @Test
    fun theSchemaSurvivesTheRoundTrip() {
        val decoded = StatusReport.decode(report().encode())
        assertEquals(EqSupport.SCHEMA, decoded?.eqSchema)
    }

    @Test
    fun aReportWithoutTheFieldStillReadsAsAnOldHook() {
        val old = StatusReport.decode("""{"moduleVersion":"0.2.1","hostPackage":"com.android.bluetooth"}""")
        assertEquals(0, old?.eqSchema)
        assertTrue("0 が「古い」でなくなると判定そのものが無意味になる", 0 < EqSupport.SCHEMA)
    }

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
