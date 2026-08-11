package io.github.mame1839.codecanchor.core

/**
 * この機器の EQ の値が、いま音に届いているか。
 *
 * **[EqAvailability] とは別の軸。**あちらは「音響処理が使えるか」(端末とモジュールの状態)、
 * こちらは「いまこの瞬間、書いた値がこの機器の音になるか」(接続の状態)。
 * 混ぜると、繋いでいないあいだ一覧の全機器が「使えません」になる。
 */
enum class EqDelivery {
    /** この機器が枠の持ち主。書けば音が変わる。 */
    LIVE,

    /**
     * 音の出口になっている登録済みの機器が 1 台も無い。
     *
     * **異常ではないので画面には出さない。**イヤホンを繋いでいないだけで、設定は保存されていて
     * 次に繋いだときに押し直される。ここを赤く出すと、繋いでいないあいだスライダーを触るたびに
     * エラーが出る画面になる。
     */
    IDLE,

    /**
     * 枠の持ち主は別の機器。**画面に出す。**
     *
     * ここは「次に何をすればよいか」が書ける唯一の場面 (この機器を繋ぐ)。黙ると、
     * ユーザから見た症状が「値を変えたのに音が変わらない」そのものになる。
     */
    OTHER,

    /**
     * 登録済みの出口が 2 台以上ある。**画面に出す。**
     *
     * `.so` は自分がどのイヤホンの枠か知らない (`deviceId` は SW の device effect で常に 0、
     * `EFFECT_CMD_SET_DEVICE` は MAC を運ばない) ので、**どちらの枠がどちらの機器かを決められない。**
     * 推測で 1 つ選ぶと、片方のイヤホンにもう片方の曲線が掛かる — 音が「なんか変」になるだけで
     * 終了コードもログも正常なので、**原因に辿り着く手掛かりが 1 つも残らない。**
     */
    AMBIGUOUS,
}

/**
 * 共有メモリの枠の持ち主を決める。**[EqParams] を呼ぶ前に必ずここを通す。**
 *
 * ### なぜアプリ側で決めるのか
 *
 * `caeqset --auto-slot` とアプリは、**互いに独立で相補的な半分ずつ**しか持っていない。
 *
 * | | 分かること | 分からないこと |
 * |---|---|---|
 * | `caeqset` | 生きた枠が 0 / 1 / 2 以上か (pid の生死と `session_id` まで見る) | **どの MAC のものか** |
 * | アプリ | どの MAC が登録済みで、いま音の出口か | 枠が実際に立っているか |
 *
 * **片方だけでは足りない。**登録 2 台・接続 1 台で、繋がっていないほうの画面で値を動かすと、
 * 生きた枠は 1 つしか無いので **`caeqset` は 14 を返さず、黙って繋がっているほうに書く。**
 * だから「誰の値を送るか」はアプリが決め、「枠が本当に 1 つか」は `caeqset` が見る。
 *
 * ### 出口の集合の出どころ
 *
 * **`AudioManager.getDevices(GET_DEVICES_OUTPUTS)` の A2DP。**フックの報告 (`DeviceStatus.connected`)
 * は使わない。理由は 2 つ:
 *
 * - **EQ はモジュールと audioserver だけで成立している。**フックが止まっていても効くのが
 *   いまの作りの強みで、ここで依存を足すと LSPosed を切っただけで EQ が届かなくなる。
 *   しかも**症状が「値が届かない」なので、原因がフックだと分からない**
 * - **古い `true` が残りうる。**報告はマージするだけで期限が無いので、切断の報告を取り逃すと
 *   `connected` が立ったまま残る。**その古さは「別のイヤホンに書く」側へ倒れる**
 */
object EqRoute {

    /**
     * 値を書く相手。**決まらなければ null で、そのときは書かない。**
     *
     * 枠を持つのは「登録済み」かつ「音の出口」の機器だけなので、交差がそのまま候補になる。
     * 候補が 1 台のときだけ名指しできる。
     */
    fun owner(outputs: Collection<String>, registered: Collection<String>): String? =
        candidates(outputs, registered).singleOrNull()

    /** この機器から見た状態。[owner] と同じ材料から決めるので、2 つが食い違うことはない。 */
    fun deliveryOf(
        mac: String,
        outputs: Collection<String>,
        registered: Collection<String>,
    ): EqDelivery {
        val found = candidates(outputs, registered)
        return when {
            found.size > 1 -> EqDelivery.AMBIGUOUS
            found.isEmpty() -> EqDelivery.IDLE
            found.single() == EqDevices.normalizeMac(mac) -> EqDelivery.LIVE
            else -> EqDelivery.OTHER
        }
    }

    /**
     * ⚠️ **両方を [EqDevices.normalizeMac] に通してから交差を取る。**
     *
     * この 2 つは出どころが違う — 登録済みは `EqDevices` が正規化した値、出口は
     * `AudioDeviceInfo.getAddress()` の値。**書式がずれると交差が恒久的に空になり、
     * 「EQ が永久に届かない」のに例外も終了コードも出ない。**`EqRouteTest` が固定している。
     */
    private fun candidates(outputs: Collection<String>, registered: Collection<String>): List<String> {
        val known = registered.mapNotNullTo(mutableSetOf(), EqDevices::normalizeMac)
        return outputs.mapNotNull(EqDevices::normalizeMac).distinct().filter { it in known }
    }
}
