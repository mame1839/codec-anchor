package io.github.mame1839.codecanchor.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.mame1839.codecanchor.BuildConfig
import io.github.mame1839.codecanchor.R
import io.github.mame1839.codecanchor.bridge.BridgeClient
import io.github.mame1839.codecanchor.bridge.EqDeviceStore
import io.github.mame1839.codecanchor.bridge.PresetStore
import io.github.mame1839.codecanchor.bridge.SettingsStore
import io.github.mame1839.codecanchor.bridge.SlotStore
import io.github.mame1839.codecanchor.core.AppConfig
import io.github.mame1839.codecanchor.core.AudioOutputs
import io.github.mame1839.codecanchor.core.AutoEqParser
import io.github.mame1839.codecanchor.core.AutoEqResult
import io.github.mame1839.codecanchor.core.CodecKeys
import io.github.mame1839.codecanchor.core.DeviceProfile
import io.github.mame1839.codecanchor.core.DeviceSlots
import io.github.mame1839.codecanchor.core.DeviceStatus
import io.github.mame1839.codecanchor.core.EqAvailability
import io.github.mame1839.codecanchor.core.EqCurveGrid
import io.github.mame1839.codecanchor.core.EqDelivery
import io.github.mame1839.codecanchor.core.EqDevices
import io.github.mame1839.codecanchor.core.EqDevicesOutcome
import io.github.mame1839.codecanchor.core.EqDevicesResult
import io.github.mame1839.codecanchor.core.EqParams
import io.github.mame1839.codecanchor.core.EqParamsOutcome
import io.github.mame1839.codecanchor.core.EqParamsResult
import io.github.mame1839.codecanchor.core.EqPreset
import io.github.mame1839.codecanchor.core.EqRoute
import io.github.mame1839.codecanchor.core.EqSettings
import io.github.mame1839.codecanchor.core.EqSlotBook
import io.github.mame1839.codecanchor.core.EqSupport
import io.github.mame1839.codecanchor.core.ModuleVersion
import io.github.mame1839.codecanchor.core.ModuleVersionState
import io.github.mame1839.codecanchor.core.QuietSwitch
import io.github.mame1839.codecanchor.core.StatusReport
import io.github.mame1839.codecanchor.core.SystemQuietBackend
import io.github.mame1839.codecanchor.xposed.XLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

enum class ModuleState { CHECKING, ACTIVE, INACTIVE }

data class DeviceRow(
    val mac: String,
    val name: String,
    val audio: Boolean,
    val bonded: Boolean,
)

data class EqRegisterReport(
    val mac: String,
    val turnedOn: Boolean,
    val result: EqDevicesResult,
)

data class EqParamsReport(
    val mac: String,
    val result: EqParamsResult,
)

private data class SentCurve(val mac: String, val text: String)

private data class PreparedCurve(val file: File, val text: String)

private const val EQ_CURVE_DEBOUNCE_MS = 400L

private const val EQ_CURVE_FILE = "eq_curve.txt"

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context = application.applicationContext
    private val store = SettingsStore(context)
    private val stored = store.load()
    private val presetStore = PresetStore(context)
    private val eqDeviceStore = EqDeviceStore(context)
    private val slotStore = SlotStore(context)

    private val audioManager: AudioManager? =
        runCatching { context.getSystemService(AudioManager::class.java) }.getOrNull()

    private val nativeLibraryDir: String = context.applicationInfo.nativeLibraryDir.orEmpty()

    var presets by mutableStateOf(presetStore.load())
        private set

    var slots by mutableStateOf(loadSlotsReconciled())
        private set

    var eqRegisteredMacs by mutableStateOf(eqDeviceStore.load())
        private set

    var eqRegisterRunning by mutableStateOf<String?>(null)
        private set

    var eqRegisterReport by mutableStateOf<EqRegisterReport?>(null)
        private set

    var a2dpOutputs by mutableStateOf(AudioOutputs.a2dp(audioManager))
        private set

    var eqParamsReport by mutableStateOf<EqParamsReport?>(null)
        private set

    var eqRoundingConfirmed by mutableStateOf(presetStore.roundingConfirmed())
        private set

    var config by mutableStateOf(stored ?: AppConfig())
        private set

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

    var settingsHooked by mutableStateOf(store.settingsHooked())
        private set

    var freeOffloadSwitch by mutableStateOf(store.freeOffloadSwitch())
        private set

    private var effectRegistered by mutableStateOf(EqSupport.effectRegistered())

    private val moduleVersionCode = ModuleVersion.read(ModuleVersion.PROPERTY_CODE)

    val moduleSemver: String by lazy { ModuleVersion.read(ModuleVersion.PROPERTY_SEMVER) }

    var pendingMessage by mutableStateOf<Int?>(null)
        private set

    private var statuses by mutableStateOf<Map<String, DeviceStatus>>(emptyMap())

    private var bondedNames by mutableStateOf<Map<String, String>>(emptyMap())
    private var probe: Job? = null
    private var watch: Job? = null
    private var syncWait: Job? = null

    private var eqPushJob: Job? = null
    private var eqPushQueued = false

    private var eqCurveJob: Job? = null

    private var eqCurveDue = false

    private var eqSentCurve: SentCurve? = null

    private var configHash = config.hash()

    private var awaitingSync by mutableStateOf(false)

    val codecNames: Map<Int, String>
        get() = report?.codecNames?.takeIf { it.isNotEmpty() } ?: CodecKeys.FALLBACK_CODEC_NAMES

    val configSynced: Boolean
        get() = awaitingSync || report?.configHash == configHash

    val a2dpOffloadEnabled: Boolean
        get() = report?.a2dpOffloadEnabled == true

    val moduleVersionState: ModuleVersionState
        get() = ModuleVersion.compare(moduleVersionCode, BuildConfig.VERSION_CODE)

    fun eqAvailability(mac: String): EqAvailability = when {
        !effectRegistered -> EqAvailability.EFFECT_NOT_REGISTERED
        a2dpOffloadEnabled -> EqAvailability.OFFLOAD_ENABLED
        !eqRegistered(mac) -> EqAvailability.DEVICE_NOT_REGISTERED
        report?.let { it.eqSchema < EqSupport.SCHEMA } == true -> EqAvailability.HOOK_TOO_OLD
        else -> EqAvailability.OK
    }

    fun eqRegistered(mac: String): Boolean = EqDevices.normalizeMac(mac) in eqRegisteredMacs

    val eqOwner: String?
        get() = EqRoute.owner(a2dpOutputs, eqRegisteredMacs)

    fun eqDelivery(mac: String): EqDelivery = EqRoute.deliveryOf(mac, a2dpOutputs, eqRegisteredMacs)

    fun refreshAudioOutputs() {
        a2dpOutputs = AudioOutputs.a2dp(audioManager)
    }

    internal fun eqSettingsToPush(target: String): EqSettings =
        config.profileFor(target)?.eq ?: EqSettings()

    fun pushEqParams(settingsChangedOnly: Boolean = false) {
        if (eqRegisterRunning != null) return
        if (eqOwner == null) return
        if (!settingsChangedOnly) eqSentCurve = null
        scheduleEqCurvePush()
        runEqPush()
    }

    private fun scheduleEqCurvePush() {
        eqCurveJob?.cancel()
        val target = eqOwner ?: return
        if (!eqSettingsToPush(target).firRequested) return
        eqCurveJob = viewModelScope.launch {
            delay(EQ_CURVE_DEBOUNCE_MS)
            eqCurveDue = true
            runEqPush()
        }
    }

    private fun runEqPush() {
        if (eqPushJob?.isActive == true) {
            eqPushQueued = true
            return
        }
        eqPushJob = viewModelScope.launch {
            do {
                eqPushQueued = false
                val target = eqOwner ?: break
                val settings = eqSettingsToPush(target)
                val sendCurve = eqCurveDue
                eqCurveDue = false
                val sent = eqSentCurve?.takeIf { it.mac == target }?.text
                val (curve, result) = withContext(Dispatchers.IO) {
                    val prepared = if (sendCurve) prepareEqCurve(settings, sent) else null
                    prepared to EqParams.apply(nativeLibraryDir, settings, prepared?.file)
                }
                if (curve != null) {
                    eqSentCurve =
                        if (result.outcome == EqParamsOutcome.APPLIED) SentCurve(target, curve.text) else null
                }
                eqParamsReport = EqParamsReport(mac = target, result = result)
            } while (eqPushQueued)
        }
    }

    private fun prepareEqCurve(settings: EqSettings, sent: String?): PreparedCurve? {
        if (!settings.firRequested) return null
        val curve = EqCurveGrid.graphicCurveDb(settings.bands)
        if (!EqCurveGrid.valid(curve)) return null
        val text = EqCurveGrid.encode(curve)
        if (text == sent) return null
        val file = File(context.cacheDir, EQ_CURVE_FILE)
        return runCatching { file.writeText(text) }.map { PreparedCurve(file, text) }.getOrNull()
    }

    fun setEqRegistered(mac: String, registered: Boolean) {
        if (eqRegisterRunning != null) return
        val key = EqDevices.normalizeMac(mac) ?: return
        val next = EqDevices.withDevice(eqRegisteredMacs, key, registered)
        eqRegisterRunning = key
        eqRegisterReport = null
        viewModelScope.launch {
            val quiet = withContext(Dispatchers.IO) {
                QuietSwitch.open(SystemQuietBackend(audioManager))
            }
            try {
                val result = withContext(Dispatchers.IO) { EqDevices.apply(next) }
                withContext(Dispatchers.IO) { quiet.awaitOutputRestored() }
                Log.i(
                    XLog.TAG,
                    "QuietSwitch: hold=${quiet.outcome} restored=${quiet.restored} " +
                        "sawOutputGone=${quiet.sawOutputGone}",
                )
                val record = EqDevices.recordAfter(eqRegisteredMacs, next, result.outcome)
                if (record != eqRegisteredMacs) {
                    eqDeviceStore.save(record)
                    eqRegisteredMacs = record
                }
                eqRegisterReport = EqRegisterReport(mac = key, turnedOn = registered, result = result)
                eqRegisterRunning = null
                if (result.outcome == EqDevicesOutcome.OK) {
                    refreshAudioOutputs()
                    pushEqParams()
                }
                eqPushJob?.join()
            } finally {
                quiet.close()
            }
        }
    }

    val orphanRows: List<DeviceRow>
        get() {
            if (!connectGranted || !bluetoothOn) return emptyList()
            val known = bondedRows.map { it.mac }.toSet()
            return (config.profiles.keys + statuses.keys + eqRegisteredMacs).filterNot { it in known }
                .map { DeviceRow(it, nameOf(it), audio = true, bonded = false) }
                .sortedBy { it.name }
        }

    fun statusOf(mac: String): DeviceStatus? = statuses[mac.uppercase()]

    fun nameOf(mac: String, bonded: String = ""): String =
        rawName(mac, bonded).ifBlank { context.getString(R.string.device_unnamed) }

    fun consumeMessage() {
        pendingMessage = null
    }

    private fun rawName(mac: String, bonded: String = ""): String {
        val key = mac.uppercase()
        return bonded.ifBlank { statuses[key]?.name.orEmpty() }
            .ifBlank { bondedNames[key].orEmpty() }
            .ifBlank { config.profiles[key]?.name.orEmpty() }
    }

    fun refresh() {
        if (!settingsHooked) settingsHooked = store.settingsHooked()
        effectRegistered = EqSupport.effectRegistered()
        refreshDevices()
        refreshAudioOutputs()
        pushEqParams()
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

    fun requestStatus() {
        probing = true
        probe?.cancel()
        probe = viewModelScope.launch {
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

    fun updateFreeOffloadSwitch(value: Boolean) {
        if (value == freeOffloadSwitch) return
        freeOffloadSwitch = value
        store.setFreeOffloadSwitch(value)
        BridgeClient.pushSettingsHook(context, value)
    }

    fun update(transform: (AppConfig) -> AppConfig) {
        if (configBroken) return
        val next = transform(config)
        if (next == config) return
        commit(next)
    }

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

    private fun loadSlotsReconciled(): EqSlotBook {
        val book = slotStore.load()
        val profiles = stored?.profiles ?: return book
        val next = book.reconciled(profiles)
        if (next != book) slotStore.save(next)
        return next
    }

    private fun saveSlots(next: EqSlotBook) {
        if (next == slots) return
        slots = next
        slotStore.save(next)
    }

    fun slotsOf(mac: String): DeviceSlots = slots.of(mac)

    fun selectSlot(mac: String, id: String) {
        val device = slots.of(mac)
        if (device.active == id) return
        val target = if (id == EqSlotBook.FLAT_ID) null else device.slot(id) ?: return
        saveSlots(slots.mapDevice(mac) { it.copy(active = id) })
        applySlotCurve(mac, target?.eq)
    }

    fun addSlot(mac: String) {
        landInNewSlot(mac, flatEq(config.profileFor(mac)?.eq ?: EqSettings(enabled = true)))
    }

    fun duplicateSlot(mac: String, id: String) {
        val slot = slots.of(mac).slot(id) ?: return
        landInNewSlot(mac, slot.eq)
    }

    fun renameSlot(mac: String, id: String, name: String) {
        saveSlots(slots.mapDevice(mac) { it.renamed(id, name.trim()) })
    }

    fun deleteSlot(mac: String, id: String) {
        val device = slots.of(mac)
        if (device.slot(id) == null) return
        val wasActive = device.active == id
        saveSlots(slots.mapDevice(mac) { it.without(id) })
        if (wasActive) applySlotCurve(mac, null)
    }

    fun landInNewSlot(mac: String, eq: EqSettings, name: String = "") {
        val curve = slotCurve(eq)
        saveSlots(slots.mapDevice(mac) { it.withNewSlot(curve, name) })
        updateEq(mac) { curve }
    }

    private fun applySlotCurve(mac: String, eq: EqSettings?) {
        updateEq(mac) { current -> eq?.let(::slotCurve) ?: flatEq(current) }
    }

    private fun slotCurve(eq: EqSettings): EqSettings =
        if (eq.enabled) eq else eq.copy(enabled = true)

    fun updateEq(mac: String, transform: (EqSettings) -> EqSettings) {
        updateProfile(mac) { it.copy(eq = transform(it.eq)) }
        config.profileFor(mac)?.let { saveSlots(slots.reconciledWith(it.mac, it.eq)) }
        if (EqDevices.normalizeMac(mac) == eqOwner) pushEqParams(settingsChangedOnly = true)
    }

    fun savePreset(name: String, settings: EqSettings) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        presets = presets.with(EqPreset(trimmed, settings))
        presetStore.save(presets)
    }

    fun deletePreset(name: String) {
        presets = presets.without(name)
        presetStore.save(presets)
    }

    fun applyPreset(mac: String, name: String) {
        val preset = presets.presets.firstOrNull { it.name == name } ?: return
        landInNewSlot(mac, preset.settings, preset.name)
    }

    fun confirmEqRounding() {
        presetStore.confirmRounding()
        eqRoundingConfirmed = true
    }

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

    fun removeProfile(mac: String) {
        update { it.withoutProfile(mac) }
        saveSlots(slots.without(mac))
    }

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
            saveSlots(slots.reconciled(restored.profiles))
            refreshDevices()
            pendingMessage = R.string.msg_backup_imported
        }
    }

    fun exportPreset(uri: Uri, preset: EqPreset) {
        val json = EqPreset.encodeSingle(preset)
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val stream = context.contentResolver.openOutputStream(uri, "w")
                        ?: error("openOutputStream returned null")
                    stream.use { it.write(json.toByteArray()) }
                }.isSuccess
            }
            pendingMessage = if (ok) R.string.msg_preset_exported else R.string.msg_preset_export_failed
        }
    }

    fun importPreset(uri: Uri) {
        viewModelScope.launch {
            val text = readText(uri)
            val preset = text?.let { EqPreset.decodeSingle(it) }
            if (preset == null) {
                pendingMessage = R.string.msg_preset_invalid
                return@launch
            }
            presets = presets.with(preset)
            presetStore.save(presets)
            pendingMessage = R.string.msg_preset_imported
        }
    }

    fun importAutoEq(uri: Uri, mac: String, bandCount: Int) {
        viewModelScope.launch {
            val text = readText(uri)
            if (text == null) {
                pendingMessage = R.string.msg_autoeq_failed
                return@launch
            }
            val result = withContext(Dispatchers.Default) { AutoEqParser.parse(text, bandCount) }
            when (result) {
                is AutoEqResult.Ok -> {
                    landInNewSlot(mac, result.settings)
                    pendingMessage = R.string.msg_autoeq_imported
                }

                is AutoEqResult.Error -> {
                    pendingMessage = when (result.reason) {
                        AutoEqResult.Reason.CORNER_SHELF -> R.string.msg_autoeq_corner_shelf
                        AutoEqResult.Reason.NO_BANDS -> R.string.msg_autoeq_no_bands
                        AutoEqResult.Reason.NOT_AUTOEQ -> R.string.msg_autoeq_invalid
                    }
                }
            }
        }
    }

    private suspend fun readText(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
    }

    private fun isBackup(o: JSONObject): Boolean =
        o.optInt("v", 0) in 1..AppConfig.VERSION && o.optJSONObject("profiles") != null

    @SuppressLint("MissingPermission")
    private fun isAudio(device: BluetoothDevice): Boolean = runCatching {
        val cls: BluetoothClass = device.bluetoothClass ?: return@runCatching false
        cls.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO ||
            cls.hasService(BluetoothClass.Service.AUDIO) ||
            cls.hasService(BluetoothClass.Service.RENDER)
    }.getOrDefault(false)

    private val audioDevices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = onOutputsChanged()

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = onOutputsChanged()
    }

    private fun onOutputsChanged() {
        refreshAudioOutputs()
        pushEqParams()
    }

    init {
        runCatching { audioManager?.registerAudioDeviceCallback(audioDevices, null) }
    }

    override fun onCleared() {
        runCatching { audioManager?.unregisterAudioDeviceCallback(audioDevices) }
    }

    private companion object {
        const val REPORT_TIMEOUT_MS = 5_000L
        const val STALE_REPORT_MS = 10_000L
        const val UNANSWERED_ATTEMPTS = 2
        const val SYNC_WAIT_MS = 5_000L
        const val WATCH_INTERVAL_MS = 3_000L
    }
}
