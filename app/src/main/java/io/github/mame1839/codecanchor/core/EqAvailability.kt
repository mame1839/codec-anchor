package io.github.mame1839.codecanchor.core

import android.media.audiofx.AudioEffect
import java.util.UUID

/**
 * 音響処理が使えない理由。使えないときも項目は伸ばしたまま残し、隠さずにこの理由を出す。
 *
 * 理由は 4 つしかない。増やす前に「アプリからその 2 つを本当に区別できるか」を確かめること —
 * 区別できないものを分けて出すと、片方が必ず嘘になる。
 */
enum class EqAvailability {
    OK,

    /**
     * エフェクトが音声フレームワークに登録されていない。
     *
     * 「モジュールが入っていない」と「設定 XML に行が入っていない」は**アプリからは区別できない**
     * (どちらも queryEffects() に出てこないだけ) ので、分けずに 1 つの理由にまとめている。
     */
    EFFECT_NOT_REGISTERED,

    /** A2DP ハードウェアオフロードが効いている。切る導線は一覧画面の案内が持っている。 */
    OFFLOAD_ENABLED,

    /**
     * この機器の MAC が `audio_effects.xml` の `<deviceEffects>` に入っていない。
     *
     * 上の 2 つと違い、**アプリ自身の記録から分かる** — 登録は `EqDevices.apply()` の成功でしか
     * 起きず、成功したときにしか記録しないので、記録と XML は 1 回の成功ごとに合う。
     */
    DEVICE_NOT_REGISTERED,

    /** Bluetooth プロセスで動いているフックが、音響処理を知らない版。 */
    HOOK_TOO_OLD,

    // ⚠️ **「いま繋がっていない」「2 台繋がっている」をここに足さないこと。**検出はできるように
    // なった (`EqRoute` が `AudioManager` の出力一覧と登録済みの一覧から決める) が、**軸が違う。**
    //
    // ここは「音響処理が使えるか」で、**一覧のカードの要約 (`EqSummaryCard`) もこれを読む。**
    // 接続の有無を混ぜると、イヤホンを繋いでいないあいだ**一覧の全機器が「使えません」になる** —
    // 設定は作れるし保存もされるのに、使えないと言うことになる。
    //
    // 「いまこの瞬間、この機器の音になるか」は `EqDelivery` が別に持つ。**分けたまま保つこと。**
    ;

    /**
     * この機器の EQ の設定を編集させてよいか。
     *
     * **[DEVICE_NOT_REGISTERED] だけは止めない。**この画面の登録トグルで直せる理由なので、
     * 編集まで止めると「登録する前に設定を作れず、設定が無いまま登録する」しかなくなる。
     * ほかの 3 つはアプリの外を直さないと解けない。
     */
    val allowsEditing: Boolean
        get() = this == OK || this == DEVICE_NOT_REGISTERED
}

object EqSupport {
    /**
     * ca_eq.cpp の descriptor の uuid。**type UUID (…9f61) ではなく実装 UUID (…9f60) で引く。**
     * XML の `<effect uuid=...>` に書かれているのも実装 UUID のほう。
     */
    val IMPL_UUID: UUID = UUID.fromString("7a1c9f60-4a2e-4f6b-9d21-0a5c1b3e77d1")

    /**
     * 音響処理の受け口の版。**1 つの定数が 2 役を持つ** —
     * **フックが `StatusReport.eqSchema` に押す値**であり、**アプリが求める値**でもある。
     *
     * **同じ APK の中では必ず一致する**ので、突き合わせは常に通る。狙いはそこではなく
     * **APK をまたいだ食い違いの検出**で、LSPosed がプロセス起動時に dex を読む以上、
     * アプリを更新しても Bluetooth プロセスの中のフックは古い版のまま動き続けうる。
     * そのときだけ「フックが古い」が出る。
     *
     * **2 つに分けないこと。**分けた瞬間に同期を保つ責任が生まれ、ずれるとアプリが自分の
     * フックを拒む。このリポジトリで繰り返し踏んでいる形 (共有メモリの大きさ・終了コード)。
     *
     * **⚠️ `const` を外さないこと。**`const` だから参照側でリテラルに畳まれ、フックが読んでも
     * `EqSupport` のクラスロードが起きない。`val` にすると Bluetooth プロセスがこのオブジェクトを
     * 読み込み、[effectRegistered] 経由で `AudioEffect` に触る — **コンパイルもテストも通り、
     * 実機のフックだけが壊れる。**EqSupportTest がこれを固定している。
     *
     * **⚠️ [EqSettings] の JSON のキーを増やしても減らしても必ずここを上げる。**古いフックは
     * 知らないキーを落とし、消したキーは自分の既定で書き足すので、どちらでも
     * `AppConfig.hash()` が永久に食い違い、アプリは理由を言えないまま
     * 「設定が届いていません」と出し続ける。上げてあれば「フックが古い」と名指しできる。
     * `EqSchemaGuardTest` がこの 2 つを一緒に動かすよう縛っている。
     *
     * 版の履歴: 1 = 音響処理の受け口 / 2 = `EqSettings.precision` (処理方式) /
     * 3 = 自動プリアンプ (`pa`) の廃止
     */
    const val SCHEMA = 3

    /**
     * エフェクトが登録されているか。
     *
     * queryEffects() は public static で、**root も権限も要らない。**
     * 音声フレームワークがまだ立っていないと null が返るので、そのときは「無い」に倒す
     * (呼び出し側が refresh() で取り直す)。
     */
    fun effectRegistered(): Boolean = runCatching {
        AudioEffect.queryEffects()?.any { it.uuid == IMPL_UUID } == true
    }.getOrDefault(false)
}
