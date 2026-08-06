package io.github.mame1839.codecanchor.core

import java.util.concurrent.TimeUnit

/**
 * 「このイヤホンで音響処理を使う」の実行。
 *
 * 音響処理は `audio_effects.xml` の静的な `<deviceEffects>` にイヤホンの MAC が書かれていることが
 * 前提で、そこはアプリから書けない (`/data/adb` も `/vendor` も読めない)。**アプリは su を同期で
 * 呼び、モジュール同梱のスクリプトに登録する MAC を渡す。**
 *
 * ```
 * su -c "sh '<module_dir>/eq_devices.sh' apply"     # stdin に MAC を 1 行 1 つ
 * ```
 *
 * **今トグルした 1 台ではなく、登録済みの全部を毎回流す。**root 側は受け取った一覧をそのまま真と
 * するので、アプリ側の記録が何であれ 1 回の成功で XML が記録に合う。空の入力 = 全解除で、これも正当。
 *
 * ⚠️ **このファイルのパスと名前を変えないこと。**モジュール側のテストがここを読んで終了コードの
 * 綴りと値を突き合わせる。**数値は式で組み立てず literal で書く** (シェルから grep で拾うため)。
 */
object EqDevices {

    /** モジュールが自分の置き場を出すプロパティ。**空なら su を呼ばない** (呼ぶ前に「無い」と分かる)。 */
    const val PROPERTY_DIR = "ro.codecanchor.module_dir"

    const val SCRIPT_NAME = "eq_devices.sh"
    const val ARG_APPLY = "apply"

    /**
     * スクリプトが**引数の検査より前に**出す印。「スクリプトが実際に走った」ことの唯一の証拠で、
     * これが無ければ su そのものが通っていない。
     *
     * **位置は契約に含めない** — root マネージャが実行の前に自前の 1 行 (警告・MOTD) を出すことが
     * あり、「最初の行」に固定するとその端末で必ず誤判定する。
     */
    const val BEGIN_MARKER = "CA_EQ_DEVICES_BEGIN"

    // 終了コードはスクリプト側と 1 対 1。
    const val EXIT_OK = 0
    const val EXIT_BAD_INPUT = 10
    const val EXIT_NO_STATE = 11
    const val EXIT_XML_FAILED = 12
    const val EXIT_APPLY_FAILED = 13
    const val EXIT_AUDIOSERVER_TIMEOUT = 14

    /**
     * audioserver の再起動を含むので数秒かかる。**戻ってこないときに画面を固めないための上限。**
     * 超えたらプロセスを落とす。
     */
    const val TIMEOUT_MS = 30_000L

    private const val SU = "su"

    /** プロセスを落としたあと、読み取りスレッドが畳まれるのを待つ上限。 */
    private const val READER_JOIN_MS = 1_000L

    /** 診断表示に回す出力の上限。スクリプトが暴走しても画面とメモリを守る。 */
    private const val OUTPUT_LIMIT = 8_192

    private val MAC_PATTERN = Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")

    /**
     * プロパティの値をシェルのコマンド文字列に埋めるので、埋める前に形を確かめる。
     * 通らなければ「モジュールが入っていない」に倒す。
     */
    private val DIR_PATTERN = Regex("^/[0-9A-Za-z._/-]+$")

    /** `AA:BB:CC:DD:EE:FF` (英大文字・コロン区切り) に揃える。揃わない値は null。 */
    fun normalizeMac(raw: String): String? {
        val text = raw.trim().uppercase()
        return if (MAC_PATTERN.matches(text)) text else null
    }

    /**
     * 送る一覧を作る。**並びを決定的にする**ので、同じ集合なら毎回同じバイト列が流れる
     * (root 側の出力を比べるときに差分が意味を持つ)。
     */
    fun normalizeAll(macs: Collection<String>): List<String> =
        macs.mapNotNull(::normalizeMac).distinct().sorted()

    /**
     * 1 台を足した / 外した後の一覧。**トグルした 1 台ではなく、これを丸ごと流す。**
     *
     * 正規化できない MAC では現在の一覧をそのまま返す。落とすほうへ倒すと、その機器を
     * 「登録済み」と記録したまま XML から消すことになる。
     */
    fun withDevice(current: Collection<String>, mac: String, registered: Boolean): List<String> {
        val key = normalizeMac(mac) ?: return normalizeAll(current)
        return normalizeAll(if (registered) current + key else current - key)
    }

    /**
     * 依頼のあとにアプリが持つべき記録。**成功したときだけ送った一覧を採る。**
     *
     * 失敗で記録を進めると、XML に無いものを「登録済み」と出すことになる。
     * **[EqDevicesOutcome.TIMEOUT] も採らない** — 打ち切っただけでスクリプトは走り切ったかもしれないが、
     * 「たぶん成功した」で記録を進めると、外れたときに直す手立てが無くなる。
     * 記録が遅れたときは、次の依頼で一覧を丸ごと送り直すので必ず追いつく。
     */
    fun recordAfter(
        current: List<String>,
        sent: List<String>,
        outcome: EqDevicesOutcome,
    ): List<String> = if (outcome == EqDevicesOutcome.OK) sent else current

    /** 印が 1 行でもあれば、スクリプトは走っている。行の**どれか**が印と完全一致すればよい。 */
    fun ranScript(output: String): Boolean =
        output.lineSequence().any { it.trim() == BEGIN_MARKER }

    /**
     * 走ったかどうかと終了コードから結果を決める。**3 分類の判定はここ 1 箇所。**
     *
     * 走っていないのに終了コードで判定すると、su が拒否したときの終了コード (実装ごとに違う) を
     * スクリプトの失敗として読んでしまう。
     */
    fun outcomeOf(output: String, exitCode: Int): EqDevicesOutcome = when {
        !ranScript(output) -> EqDevicesOutcome.ROOT_DENIED
        exitCode == EXIT_OK -> EqDevicesOutcome.OK
        else -> EqDevicesOutcome.SCRIPT_FAILED
    }

    /**
     * 登録する MAC の一覧を root へ渡す。**呼び出しは同期で、最長 [TIMEOUT_MS] 掛かる。**
     * オーディオが止まるので UI スレッドから呼ばないこと。
     *
     * 渡すのは [normalizeAll] を通した値。ここでは検査し直さない — 落とすと「送ったつもりの一覧」と
     * 「送った一覧」が食い違い、成功したときに嘘の記録が残る。
     */
    fun apply(macs: List<String>): EqDevicesResult {
        val dir = ModuleVersion.read(PROPERTY_DIR).trim()
        if (!DIR_PATTERN.matches(dir)) return EqDevicesResult(EqDevicesOutcome.NO_MODULE)
        return runCatching { run(dir, macs) }
            // su が無い端末では ProcessBuilder.start() が IOException で落ちる。
            // 走らなかったことに変わりはないので、拒否と同じ扱いにする。
            .getOrElse { EqDevicesResult(EqDevicesOutcome.ROOT_DENIED, output = it.message.orEmpty()) }
    }

    private fun run(dir: String, macs: List<String>): EqDevicesResult {
        val process = ProcessBuilder(SU, "-c", "sh '$dir/$SCRIPT_NAME' $ARG_APPLY")
            // su の拒否理由は stderr に出ることが多い。診断に要るので混ぜて取る。
            .redirectErrorStream(true)
            .start()
        // 途中で例外が飛んでも su のプロセスを置き去りにしない (正常に終わったあとは何もしない)。
        return try {
            drive(process, macs)
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun drive(process: Process, macs: List<String>): EqDevicesResult {
        // 読み取りを別スレッドに出す。同じスレッドで読み切ってから waitFor すると、
        // 黙って固まったプロセスに上限が効かない。逆に読まずに待つと、パイプが埋まった時点で
        // 相手が書き込みで止まり、偽のタイムアウトになる。
        val output = StringBuilder()
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(output) {
                        if (output.length < OUTPUT_LIMIT) output.append(line).append('\n')
                    }
                }
            }
        }
        reader.isDaemon = true
        reader.start()

        // 空の入力 = 全解除。close だけして渡す。
        runCatching {
            process.outputStream.bufferedWriter().use { writer ->
                macs.forEach { writer.append(it).append('\n') }
            }
        }

        val finished = process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!finished) process.destroyForcibly()
        reader.join(READER_JOIN_MS)
        val text = synchronized(output) { output.toString() }

        // 落としたあとの終了コードには意味が無いので、走ったかどうかも見ない。
        if (!finished) return EqDevicesResult(EqDevicesOutcome.TIMEOUT, output = text)

        val code = process.exitValue()
        return EqDevicesResult(outcomeOf(text, code), exitCode = code, output = text)
    }
}

/**
 * 失敗の 3 分類。**区別して出す。**
 *
 * | | 見分け方 |
 * |---|---|
 * | [NO_MODULE] | プロパティが空。**su を呼ぶ前に分かる** |
 * | [ROOT_DENIED] | プロセスは動いたが、印が 1 行も出てこない |
 * | [SCRIPT_FAILED] | 印が出たうえで終了コードが非 0 |
 */
enum class EqDevicesOutcome {
    OK,
    NO_MODULE,
    ROOT_DENIED,

    /** 時間内に終わらなかった。走ったかどうかも分からないので、記録は変えない。 */
    TIMEOUT,
    SCRIPT_FAILED,
}

/**
 * [exitCode] を読むのは [EqDevicesOutcome.SCRIPT_FAILED] のときだけ。ほかの結果では意味を持たない。
 * [output] は診断表示のためだけに持つ — **パースしない** (stdout は契約ではない)。
 */
data class EqDevicesResult(
    val outcome: EqDevicesOutcome,
    val exitCode: Int = -1,
    val output: String = "",
) {
    /** 失敗の原因を示す行だけを拾う。全文はスクリプトの進行ログで埋まっていることがある。 */
    fun diagnostics(limit: Int = 3): String =
        output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != EqDevices.BEGIN_MARKER }
            .toList()
            .takeLast(limit)
            .joinToString("\n")
}
