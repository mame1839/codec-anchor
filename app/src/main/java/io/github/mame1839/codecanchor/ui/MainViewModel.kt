package io.github.mame1839.codecanchor.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.mame1839.codecanchor.R
import io.github.mame1839.codecanchor.bridge.BridgeClient
import io.github.mame1839.codecanchor.bridge.SettingsStore
import io.github.mame1839.codecanchor.core.AppConfig
import io.github.mame1839.codecanchor.core.CodecKeys
import io.github.mame1839.codecanchor.core.DeviceProfile
import io.github.mame1839.codecanchor.core.DeviceStatus
import io.github.mame1839.codecanchor.core.StatusReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class ModuleState { CHECKING, ACTIVE, INACTIVE }

enum class BackupResult { OK, INVALID, FAILED }

data class DeviceRow(
    val mac: String,
    val name: String,
    val audio: Boolean,
    val bonded: Boolean,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context = application.applicationContext
    private val store = SettingsStore(context)
    private val stored = store.load()

    var config by mutableStateOf(stored ?: AppConfig())
        private set

    // 保存された設定が読めないときは、編集で上書きしてしまわないよう保存とプッシュを止める。
    var configBroken by mutableStateOf(stored == null)
        private set
    var report by mutableStateOf<StatusReport?>(null)
        private set
    var moduleState by mutableStateOf(ModuleState.CHECKING)
        private set
    var probing by mutableStateOf(false)
        private set
    var bondedRows by mutableStateOf<List<DeviceRow>>(emptyList())
        private set
    var connectGranted by mutableStateOf(true)
        private set
    var bluetoothOn by mutableStateOf(true)
        private set

    private var statuses by mutableStateOf<Map<String, DeviceStatus>>(emptyMap())
    private var probe: Job? = null

    val codecNames: Map<Int, String>
        get() = report?.codecNames?.takeIf { it.isNotEmpty() } ?: CodecKeys.FALLBACK_CODEC_NAMES

    val configSynced: Boolean
        get() = report?.configHash == config.hash()

    // ボンド済みに出てこない MAC (権限が無い / ペアリングを解除した) も設定を残しておく。
    val orphanRows: List<DeviceRow>
        get() {
            val known = bondedRows.map { it.mac }.toSet()
            return (config.profiles.keys + statuses.keys).filterNot { it in known }
                .map { DeviceRow(it, nameOf(it), audio = true, bonded = false) }
                .sortedBy { it.name }
        }

    fun statusOf(mac: String): DeviceStatus? = statuses[mac.uppercase()]

    fun nameOf(mac: String, bonded: String = ""): String {
        val key = mac.uppercase()
        return bonded.ifBlank { statuses[key]?.name.orEmpty() }
            .ifBlank { config.profiles[key]?.name.orEmpty() }
            .ifBlank { context.getString(R.string.device_unnamed) }
    }

    fun refresh() {
        refreshDevices()
        requestStatus()
    }

    fun refreshDevices() {
        connectGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
        val adapter = runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter }.getOrNull()
        bluetoothOn = runCatching { adapter?.isEnabled != false }.getOrDefault(true)
        bondedRows = runCatching {
            adapter?.bondedDevices.orEmpty().map { device ->
                DeviceRow(
                    mac = device.address.uppercase(),
                    name = nameOf(device.address, runCatching { device.name }.getOrNull().orEmpty()),
                    audio = isAudio(device),
                    bonded = true,
                )
            }
        }.getOrDefault(emptyList()).sortedWith(compareBy({ !it.audio }, { it.name.lowercase() }))
    }

    // 問い合わせ中に moduleState を戻すと、答えが返るまでの一瞬だけカードが差し替わって画面がちらつく。
    // 状態は据え置き、進行中は probing で示す。
    fun requestStatus() {
        BridgeClient.requestStatus(context)
        probing = true
        probe?.cancel()
        probe = viewModelScope.launch {
            delay(REPORT_TIMEOUT_MS)
            probing = false
            val last = report?.timestamp ?: 0L
            if (System.currentTimeMillis() - last > STALE_REPORT_MS) moduleState = ModuleState.INACTIVE
        }
    }

    // フックは単一機器だけの報告も送るので、機器ごとにマージする。
    fun onReport(received: StatusReport) {
        probe?.cancel()
        probing = false
        report = received
        if (received.devices.isNotEmpty()) statuses = statuses + received.devices.associateBy { it.mac }
        moduleState = ModuleState.ACTIVE
        refreshDevices()
    }

    fun pushConfig() {
        if (configBroken) return
        BridgeClient.pushConfig(context, config)
    }

    fun applyNow(mac: String?) = BridgeClient.applyNow(context, mac)

    fun update(transform: (AppConfig) -> AppConfig) {
        if (configBroken) return
        val next = transform(config)
        if (next == config) return
        config = next
        store.save(next)
        BridgeClient.pushConfig(context, next)
    }

    fun updateProfile(mac: String, transform: (DeviceProfile) -> DeviceProfile) = update { current ->
        val base = current.profileFor(mac) ?: DeviceProfile(mac = mac.uppercase(), name = nameOf(mac))
        current.withProfile(transform(base))
    }

    fun ensureProfile(mac: String) {
        if (config.profileFor(mac) != null) return
        update { it.withProfile(DeviceProfile(mac = mac.uppercase(), name = nameOf(mac))) }
    }

    fun removeProfile(mac: String) = update { it.withoutProfile(mac) }

    fun exportConfig(uri: Uri, onDone: (Boolean) -> Unit) {
        val json = runCatching { JSONObject(config.encode()).toString(2) }.getOrDefault(config.encode())
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val stream = context.contentResolver.openOutputStream(uri, "wt")
                        ?: error("openOutputStream returned null")
                    stream.use { it.write(json.toByteArray()) }
                }.isSuccess
            }
            onDone(ok)
        }
    }

    // 読めなかった (FAILED) と Codec Anchor のバックアップではない (INVALID) を分けるため、
    // JSON の形を自分で見てから fromJson に渡す。
    fun importConfig(uri: Uri, onDone: (BackupResult) -> Unit) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
            }
            if (text.isNullOrBlank()) {
                onDone(BackupResult.FAILED)
                return@launch
            }
            val parsed = runCatching { JSONObject(text) }.getOrNull()
            if (parsed == null || !(parsed.has("profiles") || parsed.has("enabled"))) {
                onDone(BackupResult.INVALID)
                return@launch
            }
            val restored = AppConfig.fromJson(parsed)
            config = restored
            configBroken = false
            store.save(restored)
            BridgeClient.pushConfig(context, restored)
            refreshDevices()
            onDone(BackupResult.OK)
        }
    }

    // 呼び出し元が connectGranted を確認しており、失敗しても runCatching で拾う
    @SuppressLint("MissingPermission")
    private fun isAudio(device: BluetoothDevice): Boolean = runCatching {
        val cls: BluetoothClass = device.bluetoothClass ?: return@runCatching false
        cls.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO ||
            cls.hasService(BluetoothClass.Service.AUDIO) ||
            cls.hasService(BluetoothClass.Service.RENDER)
    }.getOrDefault(false)

    private companion object {
        const val REPORT_TIMEOUT_MS = 2_000L
        const val STALE_REPORT_MS = 10_000L
    }
}
