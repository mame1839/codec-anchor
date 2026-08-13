package io.github.mame1839.codecanchor

import io.github.mame1839.codecanchor.core.AutoEqParser
import io.github.mame1839.codecanchor.core.EqBand
import io.github.mame1839.codecanchor.core.EqCurveGrid
import io.github.mame1839.codecanchor.core.EqPrecision
import io.github.mame1839.codecanchor.core.EqSolver
import io.github.mame1839.codecanchor.core.EqUnits
import io.github.mame1839.codecanchor.ui.centreGainsDb10
import io.github.mame1839.codecanchor.ui.graphicResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * 目標曲線の格子。**Kotlin ⇄ C++ ⇄ 共有メモリの 3 者が同じ格子を見ていること**が主題。
 *
 * ### なぜヘッダの本文を読むのか
 *
 * 格子の定義は `dsp/ca_eq_curve.h` の 3 定数ただ 1 箇所にあり、共有メモリの枠の並びも
 * そこから算術で決まる。**Kotlin から C++ の定数は参照できない**ので、言語をまたぐ辺は
 * テキストを読んで突き合わせるしかない。ここが無いと、C++ 側だけを動かしたときに
 * `:app:testDebugUnitTest` は 1 件も落ちない (native を組まないため。2026-08-13 に実測)。
 *
 * Kotlin **内部**の格子の形 (点数と刻みが食い違わないこと) は
 * `EqLoudnessTest.gridIsLogSpacedFrom20HzTo20kHz` が既に見張っている。ここはその重複ではなく、
 * **C++ 側との一致だけ**を見る。
 */
class EqCurveGridTest {

    // --- 言語をまたぐ辺 -----------------------------------------------------

    @Test
    fun theGridMatchesTheCppHeader() {
        val header = readCppFile("app/src/main/cpp/dsp/ca_eq_curve.h")
        assertEquals(
            "kCurvePoints が Kotlin と食い違う",
            EqCurveGrid.POINTS,
            constantInt(header, "kCurvePoints"),
        )
        assertEquals(
            "kCurveMinHz が Kotlin と食い違う",
            EqCurveGrid.MIN_HZ,
            constantDouble(header, "kCurveMinHz"),
            0.0,
        )
        assertEquals(
            "kCurveMaxHz が Kotlin と食い違う",
            EqCurveGrid.MAX_HZ,
            constantDouble(header, "kCurveMaxHz"),
            0.0,
        )
        assertEquals(
            "kCurveMaxAbsDb が Kotlin と食い違う",
            EqCurveGrid.MAX_ABS_DB,
            constantDouble(header, "kCurveMaxAbsDb"),
            0.0,
        )
    }

    /**
     * 共有メモリの枠が [EqCurveGrid.POINTS] 点ぶんの場所を持っていること。
     *
     * **枠の pad は固定長で書いてある** — 式にすると点数を動かしても sizeof が変わらず、
     * C++ 側の static_assert が 1 本も発火しない (2026-08-13 の検分で見つかった穴)。
     * ここでは同じ算術を Kotlin 側から独立に組み直して、その固定長が点数と辻褄が合うことを見る。
     * **点数だけを動かすと、この式が真っ先に破れる。**
     */
    @Test
    fun theSharedMemorySlotHasRoomForExactlyThisManyPoints() {
        val shm = readCppFile("app/src/main/cpp/ca_eq_shm.h")

        // 曲線の長さは格子の定数から引いていること (数を書き写していないこと)。
        assertTrue(
            "curve_db の長さが caeq::kCurvePoints から引かれていない (点数が 2 箇所になる)",
            Regex("""float\s+curve_db\[\s*caeq::kCurvePoints\s*]""").containsMatchIn(shm),
        )
        // ca_shm_t のヘッダが公表する点数も、同じ定数から書かれていること。
        assertTrue(
            "ca_shm_t.curve_points が caeq::kCurvePoints から書かれていない",
            Regex("""curve_points\s*=\s*static_cast<uint32_t>\(caeq::kCurvePoints\)""")
                .containsMatchIn(readCppFile("app/src/main/cpp/ca_eq.cpp")),
        )

        val slotBytes = defineInt(shm, "CA_EQ_PARAM_SLOT_BYTES")
        val pad = Regex("""uint8_t\s+pad\[(\d+)];\s*\n}\s*ca_eq_slot_t;""")
            .find(shm)?.groupValues?.get(1)?.toInt()
        assertNotNull("ca_eq_slot_t の pad が固定長で見つからない", pad)
        val curveGenOffset = Regex("""offsetof\(ca_eq_slot_t,\s*curve_gen\)\s*==\s*(\d+)""")
            .find(shm)?.groupValues?.get(1)?.toInt()
        assertNotNull("curve_gen のオフセットの釘が見つからない", curveGenOffset)

        // curve_gen (4 B) + curve_db (4 B * 点数) + pad が、枠の残りにちょうど収まること。
        assertEquals(
            "枠の並びと格子の点数が食い違う",
            slotBytes,
            curveGenOffset!! + 4 + 4 * EqCurveGrid.POINTS + pad!!,
        )
    }

    /** `caeqset --curve` は「1 行 1 値を [EqCurveGrid.POINTS] 行」しか受け取らない。 */
    @Test
    fun theCurveFileHasOneLinePerGridPoint() {
        val lines = EqCurveGrid.encode(EqCurveGrid.graphicCurveDb(tilted())).trimEnd('\n').split("\n")
        assertEquals(EqCurveGrid.POINTS, lines.size)
        assertTrue("行に周波数を書かない", lines.all { Regex("""^-?\d+\.\d\d$""").matches(it) })
    }

    /** 地域設定で小数点がコンマになる端末で `atof` が途中で読むのをやめる。 */
    @Test
    fun theCurveFileDoesNotFollowTheLocale() {
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertFalse(EqCurveGrid.encode(EqCurveGrid.graphicCurveDb(tilted())).contains(','))
        } finally {
            java.util.Locale.setDefault(original)
        }
    }

    // --- 格子そのもの -------------------------------------------------------

    @Test
    fun theEndsSitExactlyOnTheGrid() {
        assertEquals(EqCurveGrid.MIN_HZ, EqCurveGrid.hzAt(0), 1e-9)
        assertEquals(EqCurveGrid.MAX_HZ, EqCurveGrid.hzAt(EqCurveGrid.POINTS - 1), 1e-6)
        assertEquals(EqCurveGrid.POINTS, EqCurveGrid.HZ.size)
    }

    @Test
    fun nearestIndexRoundsToTheClosestPoint() {
        for (i in listOf(0, 1, 200, EqCurveGrid.POINTS - 1)) {
            assertEquals(i, EqCurveGrid.nearestIndex(EqCurveGrid.hzAt(i)))
        }
        // 範囲外は端へ。NaN の心配は無い (呼び手は必ず正の周波数を渡す)。
        assertEquals(0, EqCurveGrid.nearestIndex(1.0))
        assertEquals(EqCurveGrid.POINTS - 1, EqCurveGrid.nearestIndex(96_000.0))
    }

    /** 吸着のずれは半ステップまで。0.87 % ≈ 15 cent で、聴感には関係しない大きさ。 */
    @Test
    fun snappingMovesAVertexByAtMostHalfAStep() {
        val step = ln(EqCurveGrid.MAX_HZ / EqCurveGrid.MIN_HZ) / (EqCurveGrid.POINTS - 1)
        for (hz in EqSolver.centerFrequencies(31).map { it.toDouble() }) {
            val moved = abs(ln(EqCurveGrid.snapHz(hz) / hz))
            assertTrue("$hz Hz のずれ $moved", moved <= step / 2 + 1e-12)
        }
    }

    // --- 折れ線の標本化 -----------------------------------------------------

    /**
     * **吸着の効き目。**摘みの中心が標本点のあいだに落ちると角が丸まる。頂点を標本点へ
     * 寄せてあるので、格子の上で読んだ曲線はバンド中心で摘みの値に厳密に一致する。
     */
    @Test
    fun theCurvePassesThroughEveryKnobExactly() {
        val bands = tilted()
        val curve = EqCurveGrid.graphicCurveDb(bands)
        val knobs = EqSolver.graphicTargetsDb10(bands)
        bands.forEachIndexed { i, band ->
            val at = curve[EqCurveGrid.nearestIndex(band.freqHz.toDouble())]
            assertEquals(
                "${band.freqHz} Hz の摘み",
                knobs[i].toDouble() / EqUnits.GAIN_SCALE,
                at,
                1e-9,
            )
        }
    }

    /**
     * 吸着しなかったら実際に丸まることの確認。**「効いている」を測るには、効かない形を
     * 並べないと分からない** — 通っているだけのテストは、吸着を外しても通りうる。
     */
    @Test
    fun withoutSnappingTheZigzagPeaksWouldBeCutOff() {
        val bands = zigzag()
        val knobs = EqSolver.graphicTargetsDb10(bands)
        val raw = bands.mapIndexed { i, b ->
            b.freqHz.toDouble() to knobs[i].toDouble() / EqUnits.GAIN_SCALE
        }
        var worst = 0.0
        bands.forEachIndexed { i, band ->
            val sampled = AutoEqParser.interpolate(
                raw,
                EqCurveGrid.hzAt(EqCurveGrid.nearestIndex(band.freqHz.toDouble())),
            )
            worst = maxOf(worst, abs(sampled - knobs[i].toDouble() / EqUnits.GAIN_SCALE))
        }
        assertTrue("吸着なしの丸まりが $worst dB しかない (この形では効き目が測れない)", worst > 0.5)

        // 同じ形を吸着ありで読むと、丸まりはゼロ。
        val curve = EqCurveGrid.graphicCurveDb(bands)
        bands.forEachIndexed { i, band ->
            assertEquals(
                knobs[i].toDouble() / EqUnits.GAIN_SCALE,
                curve[EqCurveGrid.nearestIndex(band.freqHz.toDouble())],
                1e-9,
            )
        }
    }

    /** 範囲外は端の値で平坦。`caeq::curveDbAt` と同じ規約。 */
    @Test
    fun theCurveIsFlatOutsideTheBandRange() {
        val bands = tilted()
        val curve = EqCurveGrid.graphicCurveDb(bands)
        val knobs = EqSolver.graphicTargetsDb10(bands)
        val lowest = EqCurveGrid.nearestIndex(bands.first().freqHz.toDouble())
        val highest = EqCurveGrid.nearestIndex(bands.last().freqHz.toDouble())
        for (i in 0..lowest) {
            assertEquals(knobs.first().toDouble() / EqUnits.GAIN_SCALE, curve[i], 1e-9)
        }
        for (i in highest until EqCurveGrid.POINTS) {
            assertEquals(knobs.last().toDouble() / EqUnits.GAIN_SCALE, curve[i], 1e-9)
        }
    }

    @Test
    fun everyCurveWeSendPassesTheSameCheckAsTheSo() {
        assertTrue(EqCurveGrid.valid(EqCurveGrid.graphicCurveDb(tilted())))
        assertTrue(EqCurveGrid.valid(EqCurveGrid.graphicCurveDb(zigzag())))
        assertTrue(EqCurveGrid.valid(EqCurveGrid.graphicCurveDb(emptyList())))
        // 検査そのものが本当に落ちること。
        assertFalse(EqCurveGrid.valid(DoubleArray(EqCurveGrid.POINTS) { Double.NaN }))
        assertFalse(EqCurveGrid.valid(DoubleArray(EqCurveGrid.POINTS - 1)))
        assertFalse(
            EqCurveGrid.valid(
                DoubleArray(EqCurveGrid.POINTS).also { it[7] = EqCurveGrid.MAX_ABS_DB + 0.1 },
            ),
        )
    }

    /** 手書きのプリセットで摘みが上限を越えても、送る曲線は検査を通る形に収める。 */
    @Test
    fun extremeBandsAreClampedIntoTheAllowedRange() {
        val loud = EqSolver.centerFrequencies(31).map { EqBand(freqHz = it, q100 = 100, gainDb10 = 400) }
        val curve = EqCurveGrid.graphicCurveDb(loud)
        assertTrue(EqCurveGrid.valid(curve))
        assertTrue("上限まで持ち上がっている", curve.max() >= EqCurveGrid.MAX_ABS_DB - 1e-9)
    }

    // --- 絵と音 -------------------------------------------------------------

    /**
     * **高精度のとき、絵は送るものと同じ折れ線を描く。**規則が同じだけでは足りない —
     * 頂点の吸着まで含めて同じでないと、絵と音が「半ステップ × 傾き」ずれる。
     */
    @Test
    fun theHighPrecisionPlotDrawsExactlyWhatWeSend() {
        for (bands in listOf(tilted(), zigzag())) {
            val drawn = graphicResponse(bands, EqPrecision.HIGH)
            val vertices = EqCurveGrid.knobPolyline(bands)
            // 絵の標本は「吸着後の中心を等間隔に並べた軸」の上にある。同じ軸で折れ線を読めば一致する。
            val last = bands.size - 1
            val perBand = (drawn.size - 1) / last
            for (s in drawn.indices) {
                val i = (s / perBand).coerceAtMost(last - 1)
                val t = (s - i * perBand).toDouble() / perBand
                val hz = exp(ln(vertices[i].first) * (1 - t) + ln(vertices[i + 1].first) * t)
                assertEquals("標本 $s", AutoEqParser.interpolate(vertices, hz), drawn[s], 1e-12)
            }
        }
    }

    /**
     * **摘みの点は方式で動かない。**「摘みの値 = そのバンド中心で実際に鳴る音量」は
     * 既存の契約で、方式の切り替えで動くと**摘みと絵の不一致が別の形で戻ってくる。**
     */
    @Test
    fun theKnobDotsDoNotMoveBetweenTheTwoModes() {
        for (bands in listOf(tilted(), zigzag())) {
            val standard = centreGainsDb10(graphicResponse(bands, EqPrecision.STANDARD), bands.size)
            val high = centreGainsDb10(graphicResponse(bands, EqPrecision.HIGH), bands.size)
            assertEquals(EqSolver.graphicTargetsDb10(bands).toList(), standard.toList())
            assertEquals(standard.toList(), high.toList())
        }
    }

    /** 標準は今までどおり biquad の合成応答 (退行が無いこと)。 */
    @Test
    fun theStandardPlotStillDrawsTheBiquadResponse() {
        val bands = tilted()
        val drawn = graphicResponse(bands, EqPrecision.STANDARD)
        val last = bands.size - 1
        val perBand = (drawn.size - 1) / last
        val lnFreqs = bands.map { ln(it.freqHz.toDouble()) }
        for (s in drawn.indices) {
            val i = (s / perBand).coerceAtMost(last - 1)
            val t = (s - i * perBand).toDouble() / perBand
            val hz = exp(lnFreqs[i] * (1 - t) + lnFreqs[i + 1] * t)
            assertEquals("標本 $s", EqSolver.combinedResponseDb(bands, hz), drawn[s], 1e-12)
        }
        // 既定の引数は標準。既存の呼び出し (テストを含む) が黙って高精度に変わらないこと。
        assertEquals(drawn.toList(), graphicResponse(bands).toList())
    }

    // --- 材料 ---------------------------------------------------------------

    private fun tilted(): List<EqBand> {
        val freqs = EqSolver.centerFrequencies(10)
        val target = DoubleArray(freqs.size) { -6.0 + 12.0 * it / (freqs.size - 1) }
        return EqSolver.solveBands(target, freqs, EqSolver.defaultQ(freqs.size))
    }

    /** 隣り合う摘みが逆向きに振れる形。標本化の損失がいちばん大きく出る。 */
    private fun zigzag(): List<EqBand> {
        val freqs = EqSolver.centerFrequencies(31)
        val target = DoubleArray(freqs.size) { if (it % 2 == 0) 12.0 else -12.0 }
        return EqSolver.solveBands(target, freqs, EqSolver.defaultQ(freqs.size))
    }

    private companion object {

        /**
         * リポジトリの中のファイルを読む。**見つからなければ落とす** — 黙って読み飛ばすと
         * 「通っているのに何も見ていないテスト」になり、言語をまたぐ辺が無いのと同じになる。
         */
        fun readCppFile(relative: String): String {
            var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
            while (dir != null) {
                val f = File(dir, relative)
                if (f.isFile) return f.readText()
                dir = dir.parentFile
            }
            throw AssertionError("$relative が見つからない (user.dir=${System.getProperty("user.dir")})")
        }

        fun constantInt(src: String, name: String): Int =
            Regex("""\b$name\s*=\s*(-?\d+)\s*;""").find(src)?.groupValues?.get(1)?.toInt()
                ?: throw AssertionError("$name が読めない")

        fun constantDouble(src: String, name: String): Double =
            Regex("""\b$name\s*=\s*(-?[\d.]+)f?\s*;""").find(src)?.groupValues?.get(1)?.toDouble()
                ?: throw AssertionError("$name が読めない")

        fun defineInt(src: String, name: String): Int =
            Regex("""#define\s+$name\s+(\d+)""").find(src)?.groupValues?.get(1)?.toInt()
                ?: throw AssertionError("$name が読めない")
    }
}
