package io.github.mame1839.codecanchor.core

import android.media.audiofx.AudioEffect
import java.util.UUID

/**
 * 音響処理が使えない理由。使えないときも項目は伸ばしたまま残し、隠さずにこの理由を出す。
 *
 * 理由は 3 つしかない。増やす前に「アプリからその 2 つを本当に区別できるか」を確かめること —
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

    /** Bluetooth プロセスで動いているフックが、音響処理を知らない版。 */
    HOOK_TOO_OLD,
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
