package io.github.mame1839.codecanchor.core

import android.media.AudioDeviceInfo
import android.media.AudioManager

// いま音の出口になっている A2DP 機器。[EqRoute] が枠の持ち主を決める材料。
//
// ⚠️ BluetoothProfile.getConnectedDevices() ではなく AudioManager.getDevices を使う理由:
// 見たいのは「Bluetooth として繋がっているか」ではなく「音声フレームワークがそこへ出しているか」。
// <deviceEffects> を立てる audioserver が知っている出力機器と同じものを見ているので、枠の有無との
// 対応が構造で決まる。加えて getProfileProxy は非同期で、onServiceConnected が来るまで
// 「接続0台」と見分けが付かず、アプリ起動直後の1回目が黙って落ちる。getDevices は同期で返る。
object AudioOutputs {

    // [EqDevices.normalizeMac] を通した形で返す — 登録済みの一覧と交差を取るので、書式がずれると
    // 交差が恒久的に空になる。BLUETOOTH_CONNECT が無いと address が空になる (Android 12 以降)。
    fun a2dp(manager: AudioManager?): Set<String> = runCatching {
        manager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .orEmpty()
            .filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
            .mapNotNull { EqDevices.normalizeMac(it.address) }
            .toSet()
    }.getOrDefault(emptySet())

    // [QuietSwitch] が「押さえる必要があるか」の材料。[a2dp] と違って MAC を読まない —
    // BLUETOOTH_CONNECT が無くても type は読めるので、権限の無いユーザにも押さえを効かせられる。
    // A2DP に絞らない (音が漏れるのは出口消失全般で LE Audio や補聴器でも同じ)。
    fun anyBluetooth(manager: AudioManager?): Boolean = runCatching {
        manager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .orEmpty()
            .any { it.type in BLUETOOTH_TYPES }
    }.getOrDefault(false)

    // 通話用の SCO と TYPE_BLE_BROADCAST は入れない (音楽を聴いている状態を表さない)。
    // ⚠️ SCO は A2DP と同じ MAC で別の口として出るが、ここは type で引くので混ざらない。
    private val BLUETOOTH_TYPES = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_HEARING_AID,
    )
}
