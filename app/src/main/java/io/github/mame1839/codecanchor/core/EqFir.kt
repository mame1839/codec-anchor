package io.github.mame1839.codecanchor.core

/**
 * 「高精度を頼んだのに標準 (biquad) で鳴っている」理由。**`caeqset` の stdout から読む。**
 *
 * ```
 * CA_EQ_FIR why=block_unfit rate=44100 block=896 frames=1834496 age_ms=18
 * ```
 *
 * 接頭辞 + 空白区切りの `key=value` で、**`why` だけが判断材料**。`rate` / `block` /
 * `frames` / `age_ms` は診断の材料で、ここでは読まない。
 *
 * ### ⚠️ 綴りの出どころは `app/src/main/cpp/ca_eq_pick.h` の `firWhyToken`
 *
 * **同じ綴りが 2 箇所にある** (あちらの `switch` と、ここの定数)。片方だけ変わっても
 * git は衝突と報告しないので、`EqFirTest` がヘッダを読んで一致を見張っている。
 * 食い違うと**警告が二度と出なくなる**だけで、例外もログも出ない。
 *
 * **知らない綴りは黙って無視する。**あちらは値が増える前提で書かれている
 * (`default:` を置いていないのは、増やしたときに C++ 側でコンパイルを止めるため)。
 * ここで「知らない = 異常」に倒すと、ネイティブ側が 1 語足しただけでアプリが嘘の警告を出す。
 *
 * ### この行が出るのは終了コード 0 のときだけ
 *
 * `caeqset` の `reportFir` の呼び出しは 2 箇所 (`--dry-run` と書き込み後) で、どちらも
 * そのまま `kExitOk` へ抜ける。**押し込みが失敗した回にこの行は無い**ので、
 * 「値が届かなかった」と「精度だけ違う」が同時に画面へ出ることはない。
 */
object EqFir {

    /** 行の先頭の語。**ちょうどこの 1 語**で始まる行だけを読む (`CA_EQ_FIRX` を拾わない)。 */
    const val LINE_TAG = "CA_EQ_FIR"

    /** 判断に使う唯一のキー。 */
    const val WHY_KEY = "why"

    /** 退路経路のインスタンス。FIR を掛ける先が無い。 */
    const val NOT_ADDRESSABLE = "not_addressable"

    /** 3ch 以上か、作業領域の確保に失敗した。 */
    const val NO_ARENA = "no_arena"

    /** ブロック長が FIR に向かない。**44.1 kHz で踏む** (LDAC はこの fs で繋がることが多い)。 */
    const val BLOCK_UNFIT = "block_unfit"

    /**
     * **画面に出してよい綴り。ここに 4 つ目を足す前に、下の条件を確かめること。**
     *
     * 条件は「**そのスレッドとインスタンスの性質**であること」— 再生が止まっても古くならない
     * ものだけを入れる。押し込みの結果は次の押し込みまで画面に残るので、古くなる値を入れると
     * 「もう直っているのに警告が出たまま」になる。
     *
     * 入れていないものと、その理由:
     *
     * | 綴り | なぜ出さないか |
     * |---|---|
     * | `running` | 高精度が鳴っている。そもそも異常ではない |
     * | `not_requested` | 標準モード。ユーザが選んだとおり |
     * | `no_audio` | まだ 1 ブロックも来ていない。**判定できない** |
     * | `no_curve` | 曲線が届いていない。**アプリ側の不具合**で、ユーザにできることが無い |
     * | `design_failed` | 設計器が止まった。一過性 |
     * | `warming` / `almost` | 移行の途中。放っておけば解ける |
     *
     * `running` と `not_requested` は `fir_state` と `CA_FIR_F_REQUESTED` から決まり、
     * **どちらも `process()` のたびに書き換わるので古くなる。**
     */
    val REPORTABLE = setOf(NOT_ADDRESSABLE, NO_ARENA, BLOCK_UNFIT)

    /**
     * 出力から `why` を拾う。**行が無い / 壊れている / キーが欠けているときは null。**
     *
     * 1 回の実行につき高々 1 行だが、複数あれば**後の行**を採る。
     */
    fun whyOf(stdout: String): String? {
        var why: String? = null
        for (raw in stdout.lineSequence()) {
            val tokens = raw.trim().split(WHITESPACE)
            if (tokens.firstOrNull() != LINE_TAG) continue
            why = fieldsOf(tokens)[WHY_KEY]
        }
        return why
    }

    /** 「高精度を頼んだのに標準で鳴っている」と画面に出してよいか。 */
    fun fellBackToStandard(stdout: String): Boolean = whyOf(stdout) in REPORTABLE

    /**
     * 先頭の語を落として `key=value` を集める。**キー名で引くので並び順に依存しない。**
     * `=` の無いトークンと、キーが空のトークンは落とす。同じキーが 2 度来たら後が勝つ。
     */
    private fun fieldsOf(tokens: List<String>): Map<String, String> =
        tokens.asSequence().drop(1).mapNotNull { field ->
            val cut = field.indexOf('=')
            if (cut <= 0) null else field.substring(0, cut) to field.substring(cut + 1)
        }.toMap()

    private val WHITESPACE = Regex("""\s+""")
}
