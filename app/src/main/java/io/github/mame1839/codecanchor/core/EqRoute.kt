package io.github.mame1839.codecanchor.core

// この機器の EQ の値が、いま音に届いているか。[EqAvailability] (音響処理が使えるか) とは別の軸 —
// 混ぜると、繋いでいないあいだ一覧の全機器が「使えません」になる。
enum class EqDelivery {
    LIVE,

    // 異常ではないので画面には出さない (繋いでいないだけで、次に繋いだときに押し直される)。
    IDLE,

    // 画面に出す。「次に何をすればよいか」(この機器を繋ぐ) が書ける唯一の場面。
    OTHER,

    // 画面に出す。.so は自分がどのイヤホンの枠か知らないので、2 台以上あると決められない
    // (推測で選ぶと片方に別のイヤホンの曲線が掛かり、原因に辿り着く手掛かりが残らない)。
    AMBIGUOUS,
}

// 共有メモリの枠の持ち主を決める。[EqParams] を呼ぶ前に必ずここを通す。
//
// caeqset --auto-slot とアプリは相補的な半分ずつしか持たない (caeqset は生きた枠の数だけ分かって
// どの MAC かは分からず、アプリはどの MAC が出口か分かって枠が立っているかは分からない) ので、
// 「誰の値を送るか」はアプリが決め、「枠が本当に1つか」は caeqset が見る。
//
// 出口の集合は AudioManager.getDevices(GET_DEVICES_OUTPUTS) の A2DP から取る。フックの報告
// (DeviceStatus.connected) は使わない — EQ はモジュールと audioserver だけで成立させたいのと、
// 報告はマージするだけで期限が無く古い true が残りうるため。
object EqRoute {

    fun owner(outputs: Collection<String>, registered: Collection<String>): String? =
        candidates(outputs, registered).singleOrNull()

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

    // ⚠️ 両方を EqDevices.normalizeMac に通してから交差を取る。出どころが違う 2 つ (登録済みは
    // 正規化済み、出口は AudioDeviceInfo.getAddress()) の書式がずれると交差が恒久的に空になる。
    private fun candidates(outputs: Collection<String>, registered: Collection<String>): List<String> {
        val known = registered.mapNotNullTo(mutableSetOf(), EqDevices::normalizeMac)
        return outputs.mapNotNull(EqDevices::normalizeMac).distinct().filter { it in known }
    }
}
