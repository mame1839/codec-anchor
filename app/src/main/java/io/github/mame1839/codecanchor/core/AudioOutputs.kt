package io.github.mame1839.codecanchor.core

import android.media.AudioDeviceInfo
import android.media.AudioManager

object AudioOutputs {

    fun a2dp(manager: AudioManager?): Set<String> = runCatching {
        manager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .orEmpty()
            .filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
            .mapNotNull { EqDevices.normalizeMac(it.address) }
            .toSet()
    }.getOrDefault(emptySet())

    fun anyBluetooth(manager: AudioManager?): Boolean = runCatching {
        manager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .orEmpty()
            .any { it.type in BLUETOOTH_TYPES }
    }.getOrDefault(false)

    private val BLUETOOTH_TYPES = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_HEARING_AID,
    )
}
