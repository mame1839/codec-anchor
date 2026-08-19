package io.github.mame1839.codecanchor.core

import org.json.JSONArray
import org.json.JSONObject

// 値を整数で持つのは表示の都合ではなく同期の契約。
// AppConfig.hash() は encode() の文字列の hashCode で、アプリとフックが同じ文字列を作れることが
// 「設定が届いた」の判定になっている。Float / Double を入れると JSON の表記が処理系や
// 経路で揺れて往復が崩れ、再現しにくい「届いていません」になる。
// 既存の DeviceProfile の 17 フィールドに浮動小数が 1 つも無いのも同じ理由。
object EqUnits {
    const val GAIN_SCALE = 10 // gainDb10  : dB * 10
    const val Q_SCALE = 100 // q100      : Q  * 100
}

object EqBandType {
    const val PEAKING = 0
    const val LOW_SHELF = 1
    const val HIGH_SHELF = 2

    fun normalize(value: Int): Int = if (value in PEAKING..HIGH_SHELF) value else PEAKING
}

object EqMode {
    const val GRAPHIC = 0
    const val PARAMETRIC = 1

    fun normalize(value: Int): Int = if (value == PARAMETRIC) PARAMETRIC else GRAPHIC
}

/**
 * 曲線をどう鳴らすか。**音の内容ではなく作り方の選択**なので、バンドとは別の欄に持つ。
 *
 * - [STANDARD] … バンドを biquad のカスケードで鳴らす (これまでの唯一の方式)
 * - [HIGH] … 摘みの折れ線そのものを目標にした最小位相 FIR。中心と中心のあいだの
 *   ずれ (biquad の裾の重なりで出る) が消える
 *
 * **既定は [STANDARD]。**追加遅延はどちらも 0 で、差は曲線の再現精度だけ
 * (eq-fir-design.md §0)。
 */
object EqPrecision {
    const val STANDARD = 0
    const val HIGH = 1

    fun normalize(value: Int): Int = if (value == HIGH) HIGH else STANDARD
}

data class EqBand(
    val freqHz: Int,
    val q100: Int,
    val gainDb10: Int,
    val type: Int = EqBandType.PEAKING,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("f", freqHz)
        put("q", q100)
        put("g", gainDb10)
        put("t", type)
    }

    companion object {
        val FREQ_RANGE = 1..192_000
        val Q_RANGE = 10..4_000 // 0.10 〜 40.00
        val GAIN_RANGE = -400..400 // -40.0 〜 +40.0 dB

        fun fromJson(o: JSONObject): EqBand = EqBand(
            freqHz = o.optInt("f", 1_000).coerceIn(FREQ_RANGE),
            q100 = o.optInt("q", 141).coerceIn(Q_RANGE),
            gainDb10 = o.optInt("g", 0).coerceIn(GAIN_RANGE),
            type = EqBandType.normalize(o.optInt("t", EqBandType.PEAKING)),
        )
    }
}

/**
 * 音響処理の設定。
 *
 * **[preampDb10] はユーザが決めた 1 つの値。**アプリが勝手に動かさない — 曲線を変えるたびに
 * 音量が動くと、耳が「良くなった」ではなく「大きくなった」を拾う。動かしてよいのは
 * ユーザ自身と、音量を揃えることが目的の操作 (取り込み・探索) だけ。
 */
data class EqSettings(
    val enabled: Boolean = false,
    val mode: Int = EqMode.GRAPHIC,
    val bandCount: Int = 10,
    val bands: List<EqBand> = emptyList(),
    val preampDb10: Int = 0,
    val precision: Int = EqPrecision.STANDARD,
) {
    /**
     * 「高精度」を実際に要求するか。**画面に方式の行を出す条件と、`caeqset` へ `--hp` を
     * 渡す条件は、この 1 つの述語。**分けると「切り替えたのに送っていない」が作れる。
     *
     * グラフィックのときだけ。パラメトリックは fc と Q をユーザが決めていて、biquad が
     * 定義どおりの厳密値なので、FIR にしても近似が入るだけ (eq-fir-design.md §0)。
     * **パラメトリックへ移っても選択は消さない** — 戻ったときに選び直させるほうが煩わしい。
     */
    val firRequested: Boolean
        get() = enabled && mode == EqMode.GRAPHIC && precision == EqPrecision.HIGH

    // put は必ず全部呼ぶ。「既定値なら省略」をやるとアプリとフックでキーの数が変わって
    // hash が食い違う。**キーを増やしても減らしても EqSupport.SCHEMA を上げること**
    // (古いフックは知らないキーを落とし、消したキーは書き足すので、どちらでも hash が
    //  永久に食い違う。EqSchemaGuardTest がこの 2 つを一緒に動かすよう縛っている)。
    fun toJson(): JSONObject = JSONObject().apply {
        put("on", enabled)
        put("mode", mode)
        put("n", bandCount)
        put("pdb", preampDb10)
        put("prec", precision)
        put("b", JSONArray().also { a -> bands.forEach { a.put(it.toJson()) } })
    }

    companion object {
        val BAND_COUNTS = listOf(5, 10, 15, 31)
        const val MAX_BANDS = 31
        val PREAMP_RANGE = -400..120 // -40.0 〜 +12.0 dB

        fun fromJson(o: JSONObject?): EqSettings {
            if (o == null) return EqSettings()
            val bands = o.optJSONArray("b")?.let { a ->
                (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { EqBand.fromJson(it) } }
            }.orEmpty().take(MAX_BANDS)
            return EqSettings(
                enabled = o.optBoolean("on", false),
                mode = EqMode.normalize(o.optInt("mode", EqMode.GRAPHIC)),
                bandCount = normalizeBandCount(o.optInt("n", 10)),
                bands = bands,
                preampDb10 = preampFrom(o, bands),
                precision = EqPrecision.normalize(o.optInt("prec", EqPrecision.STANDARD)),
            )
        }

        /**
         * 自動プリアンプ (`pa`) を持っていた版からの移行。**スロットもプリセットも探索の
         * 保存も全部 [fromJson] を通る**ので、置き場はここ 1 箇所。
         *
         * `pa` が真だったとき実際に鳴っていたプリアンプは、適用の直前に
         * [EqSolver.autoPreampDb10] が解いた値 (`pdb` は使われていなかった)。同じ関数を
         * 通すので、**旧版が焼いていた値をそのまま再現する** (クリップを防ぐための計算ではない
         * — [EqSolver.autoPreampDb10] 参照)。
         *
         * **`has("pa")` で古い版の JSON かを切る。**旧版の `toJson` は既定値でも必ず `pa` を
         * 書いていたので、これは信頼できる判別。ここを `optBoolean("pa", true)` にすると、
         * `pa` を書かなくなった新形式でも真と読んで、**ユーザが手で決めた値が往復のたびに
         * 上書きされる**。
         *
         * **クランプは [EqSolver.autoPreampDb10] の結果にも掛ける。**掛けないと、アプリが
         * [PREAMP_RANGE] の外の値を持ったまま `toJson` で書き、それを読んだフックが
         * ここでクランプして再 encode するので、`AppConfig.hash()` が永久に食い違う。
         * 代償として、合成ピークが 40 dB を超える曲線は移行の前後で音が変わる
         * (EqSchemaGuardTest がその限界を固定している)。
         */
        private fun preampFrom(o: JSONObject, bands: List<EqBand>): Int =
            if (o.has("pa") && o.optBoolean("pa", false)) {
                EqSolver.autoPreampDb10(bands).coerceIn(PREAMP_RANGE)
            } else {
                o.optInt("pdb", 0).coerceIn(PREAMP_RANGE)
            }

        // bandCount に BAND_COUNTS 以外を入れると fromJson がここで 10 に書き換えるので、
        // アプリの encode とフックの再 encode が食い違って hash の往復が永久に壊れる。
        // 値を作る側 (取り込み・バンド数の変更) は必ずここを通す。
        // バンドの本数そのものは bands.size であって、この値ではない
        // (パラメトリックのときは無関係な本数になる)。
        fun normalizeBandCount(value: Int): Int =
            if (value in BAND_COUNTS) value else 10
    }
}
