package io.github.mame1839.codecanchor.core

import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * いま音の出口になっている A2DP 機器。**[EqRoute] が枠の持ち主を決める材料。**
 *
 * ### なぜ `BluetoothProfile.getConnectedDevices()` ではないのか
 *
 * **見たいのは「Bluetooth として繋がっているか」ではなく「音声フレームワークがそこへ出しているか」。**
 * `<deviceEffects>` のエフェクトを立てるのは audioserver で、audioserver が知っている出力機器と
 * `AudioManager.getDevices` が返す一覧は**同じもの**を見ている。枠の有無との対応が推測ではなく構造で決まる。
 *
 * 加えて `getProfileProxy` は非同期で、`onServiceConnected` が来るまで**接続 0 台と見分けが付かない。**
 * それを「未接続」と読むと**アプリ起動直後の 1 回目が必ず黙って落ちる** (しかも未接続は正常なので
 * 画面には何も出ない)。`getDevices` は同期で返るので、その状態がそもそも存在しない。
 *
 * ### ⚠️ 「接続中」と「出口」が食い違う端末があっても、判断は変わらない
 *
 * A2DP は複数台を同時に繋げる (この端末は 5 台) が、音が出るのは 1 台。
 * **接続だけの機器まで一覧に出る端末があるかは未確認**だが、出るなら [EqRoute] が
 * [EqDelivery.AMBIGUOUS] に倒れて書かなくなるだけで、**別の機器に書く側へは倒れない。**
 */
object AudioOutputs {

    /**
     * A2DP の出口の MAC。**[EqDevices.normalizeMac] を通した形で返す** —
     * 登録済みの一覧と交差を取るので、書式がずれると交差が恒久的に空になる。
     *
     * **`BLUETOOTH_CONNECT` が無いと `address` が空になる** (Android 12 以降)。
     * 権限の欠如は空集合として出て、[EqRoute] は「出口が無い」= 書かない側に倒れる。
     */
    fun a2dp(manager: AudioManager?): Set<String> = runCatching {
        manager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .orEmpty()
            .filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
            .mapNotNull { EqDevices.normalizeMac(it.address) }
            .toSet()
    }.getOrDefault(emptySet())
}
