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

    // ⚠️ 増える余地が 1 つある — **枠と MAC の対応が確実なのは、生きた枠が 1 つのときだけ。**
    // `.so` は自分がどのイヤホンの枠か知らない (`deviceId` は SW 経路で常に 0、
    // `EFFECT_CMD_SET_DEVICE` は種別しか運ばず MAC を持たない) ので、2 台同時に接続していると
    // どちらの設定をどちらへ渡すか決められない。書き手 (EqParams) 側は推測で 1 つ選ばず失敗する。
    //
    // **ここに理由を足すのは、その状態をアプリから検出できるようになってから。**
    // 検出せずに足すと「2 台繋いでいます」を出す条件が無く、片方が必ず嘘になる。
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

    /** アプリが求めるフック側の受け口の版。StatusReport.eqSchema と突き合わせる。 */
    const val SCHEMA = 1

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
