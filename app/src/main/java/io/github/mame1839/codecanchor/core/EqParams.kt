package io.github.mame1839.codecanchor.core

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * `caeqset` の終了コード。
 *
 * **`app/src/main/cpp/caeqset.cpp` の先頭の表と同じ値を、1 行 1 定数・数値は literal で持つ。**
 * 式で組み立てると `grep` で突き合わせられなくなり、片方だけ変わったことに気づけない。
 *
 * 番号はこのコマンドのもので、`EqDevices` (`module/eq_devices.sh`) の表とは別。
 * 0 と 10 だけ意味を揃えてある。
 */
object EqParamsExit {
    const val OK = 0
    const val BAD_INPUT = 10
    const val NO_SHM = 11
    const val SHM_MISMATCH = 12
    const val NO_LIVE_SLOT = 13
    const val AMBIGUOUS_SLOT = 14
    const val REJECTED = 15
}

/**
 * 値を書きに行った結果。
 *
 * **[NO_LIVE_SLOT] を失敗として出さないこと。**イヤホンが繋がっていないだけで、
 * 設定は共有メモリに残らず次の接続で書き直すのが正しい姿。ここを赤く出すと、
 * 繋いでいないあいだスライダーを触るたびにエラーが出る画面になる。
 */
enum class EqParamsOutcome {
    /** 書けた。 */
    APPLIED,

    /** イヤホン側の生きた枠が無い。**異常ではない** (上の注意を参照)。 */
    NO_LIVE_SLOT,

    /**
     * イヤホン側の生きた枠が複数。2 台同時接続。
     *
     * `.so` は自分がどのイヤホンの枠か知らない (`deviceId` は SW の device effect では常に 0、
     * `EFFECT_CMD_SET_DEVICE` は MAC を運ばない) ので、**枠と MAC の対応は 1 台のときだけ確実。**
     * 推測で 1 つ選ぶと「たまに別のイヤホンの設定になる」を踏むため、`caeqset` は断る。
     */
    AMBIGUOUS_SLOT,

    /** 値が `.so` の検査に落ちた。範囲の表は演算層にしかないので、アプリ側では判定しない。 */
    REJECTED,

    /** 共有メモリが無い / 開けない。モジュールが動いていない。 */
    NO_SHM,

    /** 共有メモリの版・大きさが合わない。**アプリとモジュールの版ずれ。** */
    VERSION_MISMATCH,

    /** 引数の組み立てを間違えている。通常は出ない。 */
    BAD_INPUT,

    /** プロセスは動いたが、印が出ていない。root マネージャに拒否された。 */
    ROOT_DENIED,

    /** `su` を起動できない。root が無い端末。 */
    NO_SU,

    /** 時間内に終わらなかった。 */
    TIMEOUT,

    /** `caeqset` が表に無い値を返した。**数値ごと [EqParamsResult.exitCode] に残す。** */
    UNKNOWN,
}

/** 実行した結果。診断のために stdout / stderr の全文を持つ (画面に出すのは呼び出し側の判断)。 */
data class EqParamsResult(
    val outcome: EqParamsOutcome,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    /**
     * 失敗の理由を示す行だけを拾う。**`su` が拒否した理由はここにしか出ない。**
     *
     * `caeqset` は理由を stderr に書き、`su` 自身の拒否も stderr に出る。stdout は成功の内訳
     * (書いた枠とバンド) なので、失敗の説明には要らない。**印は判定に使ったもので読ませる内容では
     * ない**ので落とす。
     */
    fun diagnostics(limit: Int = 3): String =
        (stderr.lineSequence() + stdout.lineSequence())
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != EqParams.BEGIN_MARKER }
            .take(limit)
            .joinToString("\n")
}

/**
 * EQ の値を共有メモリのパラメータ枠へ書く経路。
 *
 * ```
 * アプリ ──(su)──> <nativeLibraryDir>/libcaeqset.so --auto-slot --preamp … --band …
 *                    └ 生きているイヤホン側の枠を自分で選んで seqlock で書く
 * ```
 *
 * **`caeqset` は APK の中にある。**アプリは自分の `applicationInfo.nativeLibraryDir` から
 * 絶対パスを組めるので、モジュール側に配らない。APK 側にあればアプリと必ず同じ版になり、
 * モジュールとの版ずれは黙って壊れない (`caeqset` が版を突き合わせて [VERSION_MISMATCH] で落ちる)。
 *
 * **枠の選択は `caeqset` の中でやる。ここでテキストを解析しない。**判定の材料 (`in_use` /
 * `pid` の生死 / `session_id`) は共有メモリの中にあり、Kotlin から見えるのは終了コードだけ。
 *
 * ### ⚠️ 登録と値の更新は別の経路。混ぜないこと
 *
 * | | 重さ |
 * |---|---|
 * | **登録** (`EqDevices`) | **重い。** XML の書き換えと audioserver の再起動。**音が一瞬切れる** |
 * | **値の更新** (ここ) | **軽い。** 共有メモリだけ。**音は切れない** |
 *
 * 混ぜるとスライダーを動かすたびに音が切れる。呼ぶのはスライダーを離したとき (`onCommit`) だけで、
 * ドラッグ中に呼ぶと `su` を毎フレーム叩くことになる。
 */
object EqParams {

    /** APK に載る書き手。実体は実行ファイルだが、AGP に運ばせるため `lib*.so` を名乗っている。 */
    const val EXECUTABLE = "libcaeqset.so"

    /**
     * `caeqset` が引数の検査より前に stdout へ出す印。
     *
     * **「プロセスが実際に走った」ことの唯一の証拠。**`su` は拒否されたときに自分の終了コードを
     * 返すので、終了コードだけでは「拒否された」と「スクリプトが失敗した」を区別できない。
     * **位置は契約に含めない** — root マネージャが実行前に 1 行出す端末で誤判定するため。
     */
    const val BEGIN_MARKER = "CA_EQ_SET_BEGIN"

    /** プロセスを起こせなかった / 終了コードが無いときの値。 */
    const val NO_EXIT_CODE = -1

    /**
     * 待つ上限。
     *
     * **やっている仕事は共有メモリへの数十バイトの書き込みだけ**で、`EqDevices` と違って
     * XML も audioserver も触らない。長く待つ意味があるのは root マネージャが許可を聞く
     * ダイアログを出したときだけで、そこは登録 (先に必ず通る重い操作) で済んでいる。
     */
    private const val TIMEOUT_MS = 5_000L

    /** 出力を読み切るスレッドの待ち。プロセスは終わっているので、ここで待つのは一瞬。 */
    private const val DRAIN_JOIN_MS = 1_000L

    /**
     * `nativeLibraryDir` として受け付ける形。
     *
     * `su -c` に渡す 1 本の文字列の中でクォートするので、**変な値が来ると症状が
     * 「なぜか失敗する」になる。**先に弾いて [EqParamsOutcome.BAD_INPUT] にする。
     *
     * **`~` と `=` を落とさないこと。**Android 12 以降の実際の値は
     * `/data/app/~~4RtIAxRHBFLQzT9SFhqvIA==/<pkg>-tSHBbwYwHKw6RhPBP0T-Yg==/lib/arm64` の形で、
     * ここを狭めると**全部の端末で必ず弾かれる。**単引用符の中では `'` 以外は素の文字なので、
     * 危ないのは `'` だけ。
     */
    private val SAFE_DIR = Regex("""^/[A-Za-z0-9._~=+/-]+$""")

    /** 組み立てた引数がこの形から外れたら、クォートしていない前提が崩れている。 */
    internal val SAFE_ARGUMENT = Regex("""^[A-Za-z0-9.:_+-]+$""")

    fun isSafeDirectory(dir: String): Boolean = SAFE_DIR.matches(dir) && !dir.contains("..")

    /**
     * 設定を `caeqset` の引数へ。**ここが単体テストの主対象。**
     *
     * ⚠️ **[EqSettings.bands] は「解いた後」の値。**バンド間干渉の補正は `ui/EqSection.kt` の
     * `reband()` が `EqSolver.solveBands` の結果を保存する形で済んでいるので、
     * **ここで解き直すと曲線が変わる。**そのままの値を渡すこと。
     *
     * プリアンプは `preampAuto` のとき [EqSolver.autoPreampDb10] で解く。**値を保存しない**のが
     * 既存の設計 (`preampDb10` は手動で決めた値の置き場) なので、出どころを 2 つにしない。
     */
    fun arguments(eq: EqSettings): List<String> {
        if (!eq.enabled) return listOf("--auto-slot", "--off")
        val preamp = if (eq.preampAuto) EqSolver.autoPreampDb10(eq.bands) else eq.preampDb10
        val args = mutableListOf("--auto-slot", "--on", "--preamp", decimal(preamp, EqUnits.GAIN_SCALE))
        eq.bands.take(EqSettings.MAX_BANDS).forEach { band ->
            args += "--band"
            args += listOf(
                band.freqHz.toString(),
                decimal(band.q100, EqUnits.Q_SCALE),
                decimal(band.gainDb10, EqUnits.GAIN_SCALE),
                typeToken(band.type),
            ).joinToString(":")
        }
        return args
    }

    /** `su -c` に渡す 1 本の文字列。実行ファイルの絶対パスだけクォートする。 */
    fun command(nativeLibraryDir: String, eq: EqSettings): String =
        (listOf("'$nativeLibraryDir/$EXECUTABLE'") + arguments(eq)).joinToString(" ")

    /**
     * 書きに行く。**同期で走るので、呼び出し側がワーカースレッドに置くこと。**
     */
    fun apply(nativeLibraryDir: String, eq: EqSettings): EqParamsResult {
        if (!isSafeDirectory(nativeLibraryDir)) {
            return EqParamsResult(
                outcome = EqParamsOutcome.BAD_INPUT,
                exitCode = NO_EXIT_CODE,
                stdout = "",
                stderr = "nativeLibraryDir が受け付けられない形: $nativeLibraryDir",
            )
        }
        return execute(command(nativeLibraryDir, eq))
    }

    /**
     * 終了コードを分類する。
     *
     * **`else` を必ず持ち、未知の値は数値ごと残す** ([EqParamsOutcome.UNKNOWN])。
     * 黙って「成功」にも「原因不明」にもしないこと。
     */
    fun outcomeOf(exitCode: Int): EqParamsOutcome = when (exitCode) {
        EqParamsExit.OK -> EqParamsOutcome.APPLIED
        EqParamsExit.BAD_INPUT -> EqParamsOutcome.BAD_INPUT
        EqParamsExit.NO_SHM -> EqParamsOutcome.NO_SHM
        EqParamsExit.SHM_MISMATCH -> EqParamsOutcome.VERSION_MISMATCH
        EqParamsExit.NO_LIVE_SLOT -> EqParamsOutcome.NO_LIVE_SLOT
        EqParamsExit.AMBIGUOUS_SLOT -> EqParamsOutcome.AMBIGUOUS_SLOT
        EqParamsExit.REJECTED -> EqParamsOutcome.REJECTED
        else -> EqParamsOutcome.UNKNOWN
    }

    /**
     * 整数で持っている値を `caeqset` が読む 10 進表記へ。
     *
     * **`String.format` を使わない。**既定のロケールが小数点にコンマを使う端末で "1,41" になり、
     * `atof` がそこで読むのをやめて Q が 1 になる — **端末の言語設定でだけ音が変わる**という
     * 追えない形の不具合になる。整数の割り算で作れば地域に依らない。
     */
    internal fun decimal(value: Int, scale: Int): String {
        val digits = scale.toString().length - 1
        val magnitude = abs(value.toLong())
        val sign = if (value < 0) "-" else ""
        return "$sign${magnitude / scale}.${(magnitude % scale).toString().padStart(digits, '0')}"
    }

    /** `caeqset` の `--band` が受け取る種別の綴り。`ca_eq_shm.h` の `CA_EQ_BAND_*` と同じ順。 */
    internal fun typeToken(type: Int): String = when (type) {
        EqBandType.LOW_SHELF -> "ls"
        EqBandType.HIGH_SHELF -> "hs"
        else -> "pk"
    }

    private fun execute(command: String): EqParamsResult {
        val process = try {
            ProcessBuilder("su", "-c", command).start()
        } catch (e: IOException) {
            return EqParamsResult(
                outcome = EqParamsOutcome.NO_SU,
                exitCode = NO_EXIT_CODE,
                stdout = "",
                stderr = e.message.orEmpty(),
            )
        }
        val out = StringBuilder()
        val err = StringBuilder()
        // **stdout と stderr を別スレッドで読み切る。**片方だけ読むと、もう片方のパイプが
        // 埋まった時点で子プロセスが書き込みで止まり、waitFor が偽のタイムアウトを出す。
        val outDrain = drain(process.inputStream, out)
        val errDrain = drain(process.errorStream, err)
        val finished = try {
            process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) {
            process.destroy()
            outDrain.join(DRAIN_JOIN_MS)
            errDrain.join(DRAIN_JOIN_MS)
            return EqParamsResult(
                outcome = EqParamsOutcome.TIMEOUT,
                exitCode = NO_EXIT_CODE,
                stdout = out.toString(),
                stderr = err.toString(),
            )
        }
        outDrain.join(DRAIN_JOIN_MS)
        errDrain.join(DRAIN_JOIN_MS)
        val code = process.exitValue()
        val stdout = out.toString()
        // 印が出ていなければ caeqset は走っていない。**終了コードは su 自身のもの**なので、
        // 表と突き合わせてはいけない (拒否を「検査に落ちた」と読むことになる)。
        val outcome = if (stdout.contains(BEGIN_MARKER)) outcomeOf(code) else EqParamsOutcome.ROOT_DENIED
        return EqParamsResult(outcome, code, stdout, err.toString())
    }

    private fun drain(stream: InputStream, into: StringBuilder): Thread =
        Thread {
            runCatching { stream.bufferedReader().use { into.append(it.readText()) } }
        }.also { it.isDaemon = true; it.start() }
}
