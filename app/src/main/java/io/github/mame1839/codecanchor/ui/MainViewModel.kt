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

    // 画面が作り直されても消えないよう、書き出し / 復元の結果は未消費のメッセージとして持つ。
    var pendingMessage by mutableStateOf<Int?>(null)
        private set

    private var statuses by mutableStateOf<Map<String, DeviceStatus>>(emptyMap())

    // ペアリング済み一覧から得た実名。権限が切れたり Bluetooth を切っても消さない (詳細画面と
    // プロファイルに保存する名前の出どころになる)。
    private var bondedNames by mutableStateOf<Map<String, String>>(emptyMap())
    private var probe: Job? = null
    private var watch: Job? = null
    private var syncWait: Job? = null

    // 一覧のカードが recomposition ごとに読むので、設定を変えたときだけ計算する (hash は JSON を組み直す)。
    private var configHash = config.hash()

    // 送った設定に対するフックの返事を待っている間は true。
    private var awaitingSync by mutableStateOf(false)

    val codecNames: Map<Int, String>
        get() = report?.codecNames?.takeIf { it.isNotEmpty() } ?: CodecKeys.FALLBACK_CODEC_NAMES

    // 送ってからフックの返事が届くまでは、フックが持つ内容が古いのは当たり前。一致しないことを根拠に
    // 「届いていません」を出すと、押した直後だけカードに行が増えて一覧全体が上下する。返事を待つ間は伏せる。
    val configSynced: Boolean
        get() = awaitingSync || report?.configHash == configHash

    val a2dpOffloadEnabled: Boolean
        get() = report?.a2dpOffloadEnabled == true

    // ボンド済みに出てこない MAC (ペアリングを解除した) も設定を残しておく。
    // 権限が無い / Bluetooth がオフのときは一覧そのものが読めないので、ボンド済みに無いことを根拠にできない。
    val orphanRows: List<DeviceRow>
        get() {
            if (!connectGranted || !bluetoothOn) return emptyList()
            val known = bondedRows.map { it.mac }.toSet()
            return (config.profiles.keys + statuses.keys).filterNot { it in known }
                .map { DeviceRow(it, nameOf(it), audio = true, bonded = false) }
                .sortedBy { it.name }
        }

    fun statusOf(mac: String): DeviceStatus? = statuses[mac.uppercase()]

    fun nameOf(mac: String, bonded: String = ""): String =
        rawName(mac, bonded).ifBlank { context.getString(R.string.device_unnamed) }

    fun consumeMessage() {
        pendingMessage = null
    }

    // 保存する名前に訳文を混ぜないため、実名が無ければ空のまま返す。
    private fun rawName(mac: String, bonded: String = ""): String {
        val key = mac.uppercase()
        return bonded.ifBlank { statuses[key]?.name.orEmpty() }
            .ifBlank { bondedNames[key].orEmpty() }
            .ifBlank { config.profiles[key]?.name.orEmpty() }
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
        val names = runCatching {
            adapter?.bondedDevices.orEmpty().mapNotNull { device ->
                val name = runCatching { device.name }.getOrNull()?.takeIf { it.isNotBlank() }
                name?.let { device.address.uppercase() to it }
            }.toMap()
        }.getOrDefault(emptyMap())
        if (names.isNotEmpty()) bondedNames = bondedNames + names
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
        probing = true
        probe?.cancel()
        probe = viewModelScope.launch {
            // まだ 1 通も受けていないうちは判定を保留して送り直す。報告が届けば onReport が probe を取り消す。
            val attempts = if (report == null) UNANSWERED_ATTEMPTS else 1
            repeat(attempts) {
                BridgeClient.requestStatus(context)
                delay(REPORT_TIMEOUT_MS)
            }
            probing = false
            val last = report?.timestamp ?: 0L
            if (System.currentTimeMillis() - last > STALE_REPORT_MS) moduleState = ModuleState.INACTIVE
        }
    }

    // LDAC の実効ビットレートは送信中に動くので、それを見ている画面が開いている間だけ取り直す。
    // 判定 (probing / moduleState) は requestStatus に任せ、ここでは要求だけ投げる —
    // 取り直しのたびに判定を動かすと、答えを待つ数秒のあいだ表示がちらつく。
    fun startWatching() {
        if (watch?.isActive == true) return
        watch = viewModelScope.launch {
            while (true) {
                BridgeClient.requestStatus(context)
                delay(WATCH_INTERVAL_MS)
            }
        }
    }

    fun stopWatching() {
        watch?.cancel()
        watch = null
    }

    // フックは単一機器だけの報告も送るので、機器ごとにマージする。
    // 報告は適用サイクル中に数秒で何通も届くので、ここでボンド済みの再列挙 (binder 呼び出し) はしない。
    fun onReport(received: StatusReport) {
        probe?.cancel()
        probing = false
        if (received.configHash == configHash) endSyncWait()
        report = received
        if (received.devices.isNotEmpty()) statuses = statuses + received.devices.associateBy { it.mac }
        moduleState = ModuleState.ACTIVE
    }

    fun pushConfig() {
        if (configBroken) return
        beginSyncWait()
        BridgeClient.pushConfig(context, config)
    }

    fun applyNow(mac: String?) = BridgeClient.applyNow(context, mac)

    fun update(transform: (AppConfig) -> AppConfig) {
        if (configBroken) return
        val next = transform(config)
        if (next == config) return
        commit(next)
    }

    // 設定の差し替えは保存とフックへの送信まで一続き。configHash も config と同じ場所で更新する。
    private fun commit(next: AppConfig) {
        config = next
        configHash = next.hash()
        store.save(next)
        beginSyncWait()
        BridgeClient.pushConfig(context, next)
    }

    private fun beginSyncWait() {
        awaitingSync = true
        syncWait?.cancel()
        syncWait = viewModelScope.launch {
            delay(SYNC_WAIT_MS)
            awaitingSync = false
        }
    }

    private fun endSyncWait() {
        syncWait?.cancel()
        syncWait = null
        awaitingSync = false
    }

    fun updateProfile(mac: String, transform: (DeviceProfile) -> DeviceProfile) = update { current ->
        val base = current.profileFor(mac) ?: DeviceProfile(mac = mac.uppercase(), name = rawName(mac))
        current.withProfile(transform(base))
    }

    // 名前が空のまま保存されたプロファイルは、実名が分かった時点で埋める (バックアップにも載る)。
    fun ensureProfile(mac: String) {
        val key = mac.uppercase()
        val existing = config.profileFor(key)
        val name = rawName(key)
        if (existing == null) {
            update { it.withProfile(DeviceProfile(mac = key, name = name)) }
        } else if (existing.name.isBlank() && name.isNotBlank()) {
            update { it.withProfile(existing.copy(name = name)) }
        }
    }

    fun removeProfile(mac: String) = update { it.withoutProfile(mac) }

    fun exportConfig(uri: Uri) {
        val json = runCatching { JSONObject(config.encode()).toString(2) }.getOrDefault(config.encode())
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val stream = context.contentResolver.openOutputStream(uri, "wt")
                        ?: error("openOutputStream returned null")
                    stream.use { it.write(json.toByteArray()) }
                }.isSuccess
            }
            pendingMessage = if (ok) R.string.msg_backup_exported else R.string.msg_backup_export_failed
        }
    }

    // 読めなかったのと Codec Anchor のバックアップでないのを分けるため、JSON の形を見てから fromJson に渡す。
    fun importConfig(uri: Uri) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
            }
            if (text.isNullOrBlank()) {
                pendingMessage = R.string.msg_backup_failed
                return@launch
            }
            val parsed = runCatching { JSONObject(text) }.getOrNull()
            if (parsed == null || !isBackup(parsed)) {
                pendingMessage = R.string.msg_backup_invalid
                return@launch
            }
            val restored = AppConfig.fromJson(parsed)
            configBroken = false
            commit(restored)
            refreshDevices()
            pendingMessage = R.string.msg_backup_imported
        }
    }

    // 既知の版で profiles を持つファイルだけを復元する。緩い判定だと無関係な JSON で全設定が消える。
    private fun isBackup(o: JSONObject): Boolean =
        o.optInt("v", 0) in 1..AppConfig.VERSION && o.optJSONObject("profiles") != null

    // 呼び出し元が connectGranted を確認しており、失敗しても runCatching で拾う
    @SuppressLint("MissingPermission")
    private fun isAudio(device: BluetoothDevice): Boolean = runCatching {
        val cls: BluetoothClass = device.bluetoothClass ?: return@runCatching false
        cls.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO ||
            cls.hasService(BluetoothClass.Service.AUDIO) ||
            cls.hasService(BluetoothClass.Service.RENDER)
    }.getOrDefault(false)

    private companion object {
        const val REPORT_TIMEOUT_MS = 5_000L
        const val STALE_REPORT_MS = 10_000L
        const val UNANSWERED_ATTEMPTS = 2

        // フックは状態を組むときに LDAC のダンプを読む (最長 3 秒) ので、返事はそれより遅れることがある。
        const val SYNC_WAIT_MS = 5_000L

        // フック側は同じ機器の読み取りを 2 秒キャッシュするので、それより長い間隔で回す。
        const val WATCH_INTERVAL_MS = 3_000L
    }
}
