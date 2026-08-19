package io.github.mame1839.codecanchor

import io.github.mame1839.codecanchor.core.EqBand
import io.github.mame1839.codecanchor.core.EqBandType
import io.github.mame1839.codecanchor.core.EqMode
import io.github.mame1839.codecanchor.core.EqParams
import io.github.mame1839.codecanchor.core.EqParamsExit
import io.github.mame1839.codecanchor.core.EqParamsOutcome
import io.github.mame1839.codecanchor.core.EqPrecision
import io.github.mame1839.codecanchor.core.EqSettings
import io.github.mame1839.codecanchor.core.EqSolver
import io.github.mame1839.codecanchor.core.EqUnits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/** `caeqset` に渡す引数の組み立てと、終了コードの読み方。 */
class EqParamsTest {

    private val graphic = EqSettings(
        enabled = true,
        bandCount = 10,
        bands = EqSolver.centerFrequencies(10).mapIndexed { i, hz ->
            EqBand(freqHz = hz, q100 = 100, gainDb10 = if (i < 5) 65 else -35)
        },
    )

    // --- 単位の変換 ---------------------------------------------------------

    @Test
    fun decimalKeepsTheScale() {
        assertEquals("0.0", EqParams.decimal(0, EqUnits.GAIN_SCALE))
        assertEquals("6.5", EqParams.decimal(65, EqUnits.GAIN_SCALE))
        assertEquals("-3.5", EqParams.decimal(-35, EqUnits.GAIN_SCALE))
        assertEquals("-0.5", EqParams.decimal(-5, EqUnits.GAIN_SCALE))
        assertEquals("12.0", EqParams.decimal(120, EqUnits.GAIN_SCALE))
        assertEquals("1.41", EqParams.decimal(141, EqUnits.Q_SCALE))
        assertEquals("1.00", EqParams.decimal(100, EqUnits.Q_SCALE))
        assertEquals("0.10", EqParams.decimal(10, EqUnits.Q_SCALE))
        assertEquals("-40.0", EqParams.decimal(-400, EqUnits.GAIN_SCALE))
    }

    /**
     * **小数点がコンマになる地域で "1,41" を渡すと、`atof` がそこで読むのをやめて Q が 1 になる。**
     * 端末の言語設定でだけ音が変わるので、症状からは絶対に辿り着けない。
     */
    @Test
    fun decimalDoesNotFollowTheLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("1.41", EqParams.decimal(141, EqUnits.Q_SCALE))
            assertEquals("-3.5", EqParams.decimal(-35, EqUnits.GAIN_SCALE))
            assertTrue(EqParams.arguments(graphic).none { it.contains(',') })
        } finally {
            Locale.setDefault(original)
        }
    }

    // --- 引数の組み立て -----------------------------------------------------

    @Test
    fun disabledSendsOffAndNothingElse() {
        val args = EqParams.arguments(graphic.copy(enabled = false))
        assertEquals(listOf("--auto-slot", "--off"), args)
    }

    @Test
    fun enabledSendsEveryBand() {
        val args = EqParams.arguments(graphic)
        assertEquals(graphic.bands.size, args.count { it == "--band" })
        assertTrue(args.contains("--auto-slot"))
        assertTrue(args.contains("--on"))
        assertEquals("32:1.00:6.5:pk", args[args.indexOf("--band") + 1])
    }

    /**
     * **`bands` は既に解いた後の値。**`reband()` が `EqSolver.solveBands` の結果を保存しているので、
     * ここで解き直すと曲線が変わる。渡した値がそのまま出ることを固定する。
     */
    @Test
    fun bandsAreSentVerbatim() {
        val sent = EqParams.arguments(graphic).bandTokens()
        val expected = graphic.bands.map { band ->
            listOf(
                band.freqHz.toString(),
                EqParams.decimal(band.q100, EqUnits.Q_SCALE),
                EqParams.decimal(band.gainDb10, EqUnits.GAIN_SCALE),
                "pk",
            ).joinToString(":")
        }
        assertEquals(expected, sent)
    }

    @Test
    fun bandTypesUseTheirOwnToken() {
        val settings = EqSettings(
            enabled = true,
            bands = listOf(
                EqBand(105, 70, -65, EqBandType.LOW_SHELF),
                EqBand(3_150, 141, 25, EqBandType.PEAKING),
                EqBand(10_000, 70, 30, EqBandType.HIGH_SHELF),
            ),
        )
        assertEquals(
            listOf("105:0.70:-6.5:ls", "3150:1.41:2.5:pk", "10000:0.70:3.0:hs"),
            EqParams.arguments(settings).bandTokens(),
        )
    }

    @Test
    fun manualPreampIsSentAsStored() {
        val args = EqParams.arguments(graphic.copy(preampDb10 = -75))
        assertEquals("-7.5", args.preamp())
    }

    /**
     * **曲線を変えてもプリアンプは動かない。**ここで `EqSolver.autoPreampDb10` のような
     * 「バンドから解き直す」を足すと、摘みを 1 本動かすたびに音量が動く
     * (ユーザの訴え「音量差がかなり出るから耳に悪い」)。
     *
     * `graphic` は 5 本を +6.5 dB 上げていて `autoPreampDb10` なら 0 以外を返す形。
     * 送られるのは保存値 0.0 dB のまま。
     */
    @Test
    fun theStoredPreampSurvivesALoudCurve() {
        val loud = graphic.copy(preampDb10 = 0)
        assertTrue("題材が弱い", EqSolver.autoPreampDb10(loud.bands) < 0)
        assertEquals("0.0", EqParams.arguments(loud).preamp())
        // バンドを 1 本だけ動かしても値は同じ
        val moved = loud.copy(bands = loud.bands.mapIndexed { i, b -> if (i == 0) b.copy(gainDb10 = 120) else b })
        assertEquals("0.0", EqParams.arguments(moved).preamp())
    }

    @Test
    fun emptyBandsStillSendThePreamp() {
        val args = EqParams.arguments(EqSettings(enabled = true, bands = emptyList()))
        assertEquals(0, args.count { it == "--band" })
        assertEquals("0.0", args.preamp())
    }

    @Test
    fun bandsBeyondTheLimitAreDropped() {
        val settings = EqSettings(
            enabled = true,
            bands = (0 until EqSettings.MAX_BANDS + 5).map { EqBand(1_000 + it, 100, 10) },
        )
        assertEquals(EqSettings.MAX_BANDS, EqParams.arguments(settings).count { it == "--band" })
    }

    /** 引数はクォートせずに `su -c` の 1 本の文字列へ入る。区切りになる文字が混じったら前提が崩れる。 */
    @Test
    fun everyArgumentIsShellSafe() {
        val settings = graphic.copy(preampDb10 = -400)
        for (arg in EqParams.arguments(settings)) {
            assertTrue("引数 \"$arg\"", EqParams.SAFE_ARGUMENT.matches(arg))
        }
    }

    @Test
    fun commandQuotesTheExecutablePath() {
        val command = EqParams.command(REAL_DIR, graphic)
        assertTrue(command, command.startsWith("'$REAL_DIR/libcaeqset.so' "))
        assertTrue(command.contains("--auto-slot"))
    }

    // --- 処理方式と目標曲線 -------------------------------------------------

    /**
     * **方式は毎回送る。**`caeqset` の既定は標準なので、送らない回があるとそこで
     * 高精度が黙って外れる。
     */
    @Test
    fun theProcessingModeIsAlwaysSent() {
        assertTrue(EqParams.arguments(graphic).contains("--std"))
        assertTrue(EqParams.arguments(graphic.copy(precision = EqPrecision.HIGH)).contains("--hp"))
        // どちらか一方だけ。
        assertEquals(1, EqParams.arguments(graphic).count { it == "--std" || it == "--hp" })
    }

    /**
     * パラメトリックでは高精度を要求しない。biquad が定義どおりの厳密値なので、
     * FIR にしても近似が入るだけ。**選択そのものは保存に残る**ので、グラフィックへ
     * 戻せば `--hp` が復活する。
     */
    @Test
    fun parametricNeverAsksForHighPrecision() {
        val parametric = graphic.copy(mode = EqMode.PARAMETRIC, precision = EqPrecision.HIGH)
        assertTrue(EqParams.arguments(parametric).contains("--std"))
        assertEquals(EqPrecision.HIGH, parametric.precision)
        assertFalse(parametric.firRequested)
    }

    @Test
    fun theCurvePathIsQuotedAndOnlySentForHighPrecision() {
        val path = "/data/user/0/io.github.mame1839.codecanchor/cache/eq_curve.txt"
        val high = EqParams.command(REAL_DIR, graphic.copy(precision = EqPrecision.HIGH), path)
        assertTrue(high, high.endsWith(" --curve '$path'"))

        // 標準のときは付けない — 読まれない曲線で世代だけが動き、FIR が組み直される。
        assertFalse(EqParams.command(REAL_DIR, graphic, path).contains("--curve"))
        // 渡さなければ当然付かない。
        assertFalse(
            EqParams.command(REAL_DIR, graphic.copy(precision = EqPrecision.HIGH)).contains("--curve"),
        )
    }

    /** 曲線のパスが変な形なら、**曲線だけ落として bands は送る** (音まで止めない)。 */
    @Test
    fun anOddCurvePathDropsOnlyTheCurve() {
        assertTrue(EqParams.isSafePath("/data/user/0/io.github.mame1839.codecanchor/cache/eq_curve.txt"))
        assertFalse(EqParams.isSafePath("/data/user/0/pkg/cache/x'; rm -rf /; '"))
        assertFalse(EqParams.isSafePath("/data/user/0/pkg/cache/with space"))
    }

    // --- nativeLibraryDir の検査 -------------------------------------------

    /**
     * **実機の値をそのまま通すこと。**Android 12 以降は `~~` と `==` が必ず入るので、
     * 「英数字と `/` と `.` だけ」に絞ると全部の端末で弾かれ、症状は
     * 「EQ が一切効かない」になる。
     */
    @Test
    fun realNativeLibraryDirsAreAccepted() {
        assertTrue(REAL_DIR, EqParams.isSafeDirectory(REAL_DIR))
        assertTrue(EqParams.isSafeDirectory("/data/app/io.github.mame1839.codecanchor-1/lib/arm64"))
    }

    @Test
    fun oddNativeLibraryDirsAreRefused() {
        // クォートの中に入るので、閉じられる文字と展開される文字が入っていたら実行前に落とす。
        assertFalse(EqParams.isSafeDirectory("/data/app/x'; rm -rf /; '"))
        assertFalse(EqParams.isSafeDirectory("/data/app/`id`"))
        assertFalse(EqParams.isSafeDirectory("/data/app/\$HOME"))
        assertFalse(EqParams.isSafeDirectory("/data/app/with space"))
        assertFalse(EqParams.isSafeDirectory("/data/app/../../system/bin"))
        assertFalse(EqParams.isSafeDirectory("data/app/lib"))
        assertFalse(EqParams.isSafeDirectory(""))
    }

    @Test
    fun refusedDirectoryNeverRunsAnything() {
        val result = EqParams.apply("/data/app/`id`", graphic)
        assertEquals(EqParamsOutcome.BAD_INPUT, result.outcome)
        assertEquals(EqParams.NO_EXIT_CODE, result.exitCode)
    }

    // --- 終了コード ---------------------------------------------------------

    @Test
    fun everyDocumentedExitCodeHasAMeaning() {
        assertEquals(EqParamsOutcome.APPLIED, EqParams.outcomeOf(EqParamsExit.OK))
        assertEquals(EqParamsOutcome.BAD_INPUT, EqParams.outcomeOf(EqParamsExit.BAD_INPUT))
        assertEquals(EqParamsOutcome.NO_SHM, EqParams.outcomeOf(EqParamsExit.NO_SHM))
        assertEquals(EqParamsOutcome.VERSION_MISMATCH, EqParams.outcomeOf(EqParamsExit.SHM_MISMATCH))
        assertEquals(EqParamsOutcome.NO_LIVE_SLOT, EqParams.outcomeOf(EqParamsExit.NO_LIVE_SLOT))
        assertEquals(EqParamsOutcome.AMBIGUOUS_SLOT, EqParams.outcomeOf(EqParamsExit.AMBIGUOUS_SLOT))
        assertEquals(EqParamsOutcome.REJECTED, EqParams.outcomeOf(EqParamsExit.REJECTED))
    }

    /** 表に無い値を黙って「成功」にも「原因不明」にもしない。数値は結果に残る。 */
    @Test
    fun unknownExitCodesStayUnknown() {
        for (code in listOf(1, 2, 9, 16, 99, 127, -1)) {
            assertEquals("コード $code", EqParamsOutcome.UNKNOWN, EqParams.outcomeOf(code))
        }
    }

    @Test
    fun exitCodesAreDistinct() {
        val codes = listOf(
            EqParamsExit.OK,
            EqParamsExit.BAD_INPUT,
            EqParamsExit.NO_SHM,
            EqParamsExit.SHM_MISMATCH,
            EqParamsExit.NO_LIVE_SLOT,
            EqParamsExit.AMBIGUOUS_SLOT,
            EqParamsExit.REJECTED,
        )
        assertEquals(codes.size, codes.toSet().size)
    }

    // --- ビルド設定 ---------------------------------------------------------

    /**
     * **`jniLibs.useLegacyPackaging = true` が消えていないこと。**
     *
     * ⚠️ **このテストを「gradle を読む変なテスト」と判断して消さないこと。これだけが気づく手段。**
     *
     * AGP の既定は `false` で、マニフェストに `android:extractNativeLibs="false"` が入る。
     * そうなると `.so` は APK の中に置かれたままで **`nativeLibraryDir` にファイルが 1 つも
     * 現れず、[EqParams.apply] の `su` からの exec が「No such file or directory」で死ぬ。**
     *
     * **外してもビルドもテストも通る。**実機に入れて初めて「EQ の値が届かない」として出るうえ、
     * その症状から `build.gradle.kts` に辿り着くのはほぼ不可能。将来 AGP の既定に合わせようと
     * した人がここで止まる。
     */
    @Test
    fun nativeExecutablesAreExtractedOnInstall() {
        val file = appBuildFile()
        val declared = file.readText()
            .lineSequence()
            .map { it.substringBefore("//").trim() }
            .any { Regex("""jniLibs\.useLegacyPackaging\s*=\s*true""").containsMatchIn(it) }
        assertTrue(
            "${file.absolutePath} に jniLibs.useLegacyPackaging = true が無い。" +
                "これが無いと libcaeqset.so が nativeLibraryDir に現れず、su から起動できない",
            declared,
        )
    }

    /**
     * **バンド数の上限が Kotlin と C++ で一致していること。**
     *
     * ⚠️ **同じ値が 2 箇所にある。**`EqSettings.MAX_BANDS` と `ca_eq_shm.h` の `CA_EQ_MAX_BANDS` で、
     * **git は片方だけの変更を衝突と報告しない。**
     *
     * Kotlin 側が大きいと `caeqset` が「バンドが多すぎる」で 10 を返し、画面には
     * **「アプリ側の不具合です」**が出る (原因は上限の食い違いなので、そこを見ても何も分からない)。
     * C++ 側が大きいと、ユーザは払った容量を使えないまま気づかない。**どちらも無言で壊れる。**
     */
    @Test
    fun theBandLimitMatchesTheNativeHeader() {
        val header = repoFile("app/src/main/cpp/ca_eq_shm.h")
        val native = Regex("""#define\s+CA_EQ_MAX_BANDS\s+(\d+)""")
            .find(header.readText())
            ?.groupValues
            ?.get(1)
            ?.toInt()
        assertEquals(
            "${header.absolutePath} の CA_EQ_MAX_BANDS を読めなかった。" +
                "書き方を変えたなら、この検査の読み方も直すこと",
            true,
            native != null,
        )
        assertEquals(
            "EqSettings.MAX_BANDS と CA_EQ_MAX_BANDS が違う。caeqset が 10 (使い方が不正) を返し、" +
                "画面には「アプリ側の不具合」が出る",
            native,
            EqSettings.MAX_BANDS,
        )
    }

    /**
     * リポジトリ内のファイルを探す。単体テストの作業ディレクトリは Gradle の設定で
     * `app/` にもリポジトリ直下にもなる。
     *
     * **見つからなければ失敗させる。**「読めなかったので一致とみなす」にすると、
     * 配置が変わった日に上の検査が黙って何も見なくなる。
     */
    private fun repoFile(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val here = File(dir, relative)
            if (here.isFile) return here
            dir = dir.parentFile
        }
        throw AssertionError(
            "$relative が見つからない (作業ディレクトリ ${File("").absolutePath})。" +
                "移動したなら、この検査の探し方も直すこと",
        )
    }

    private fun appBuildFile(): File = repoFile("app/build.gradle.kts")

    private fun List<String>.bandTokens(): List<String> =
        mapIndexedNotNull { i, arg -> if (i > 0 && this[i - 1] == "--band") arg else null }

    private fun List<String>.preamp(): String = this[indexOf("--preamp") + 1]

    private companion object {
        /** 実機の nativeLibraryDir の形 (Android 12 以降)。 */
        const val REAL_DIR =
            "/data/app/~~4RtIAxRHBFLQzT9SFhqvIA==/" +
                "io.github.mame1839.codecanchor-tSHBbwYwHKw6RhPBP0T-Yg==/lib/arm64"
    }
}
