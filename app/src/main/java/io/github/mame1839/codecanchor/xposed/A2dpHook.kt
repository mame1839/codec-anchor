package io.github.mame1839.codecanchor.xposed

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Parcelable
import android.widget.Toast
import android.app.AndroidAppHelper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.mame1839.codecanchor.BuildConfig
import io.github.mame1839.codecanchor.core.AppConfig
import io.github.mame1839.codecanchor.core.Bridge
import io.github.mame1839.codecanchor.core.CodecInfo
import io.github.mame1839.codecanchor.core.CodecKeys
import io.github.mame1839.codecanchor.core.DeviceProfile
import io.github.mame1839.codecanchor.core.DeviceStatus
import io.github.mame1839.codecanchor.core.StatusReport
import java.util.concurrent.ConcurrentHashMap

internal object A2dpHook {
    private const val CLASS_A2DP_SERVICE = "com.android.bluetooth.a2dp.A2dpService"
    private const val CLASS_A2DP_CODEC_CONFIG = "com.android.bluetooth.a2dp.A2dpCodecConfig"

    private const val ACTION_CONNECTION_STATE_CHANGED =
        "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED"
    private const val ACTION_ACTIVE_DEVICE_CHANGED =
        "android.bluetooth.a2dp.profile.action.ACTIVE_DEVICE_CHANGED"
    private const val ACTION_CODEC_CONFIG_CHANGED =
        "android.bluetooth.a2dp.profile.action.CODEC_CONFIG_CHANGED"
    private const val EXTRA_STATE = "android.bluetooth.profile.extra.STATE"
    private const val EXTRA_CODEC_STATUS = "android.bluetooth.extra.CODEC_STATUS"

    private const val STATE_DISCONNECTED = 0
    private const val STATE_CONNECTED = 2

    private const val SBC_STEP_DELAY_MS = 900L

    @Volatile private var service: Any? = null
    @Volatile private var context: Context? = null
    @Volatile private var hostPackage = ""
    @Volatile private var config = AppConfig()
    @Volatile private var configLoaded = false
    private var receiversRegistered = false

    private val statuses = ConcurrentHashMap<String, DeviceStatus>()
    private val announced = ConcurrentHashMap<String, String>()
    private val applyingMacs = ConcurrentHashMap.newKeySet<String>()
    private val worker: Handler by lazy {
        Handler(HandlerThread("CodecAnchor").apply { start() }.looper)
    }
    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

    fun install(classLoader: ClassLoader, packageName: String) {
        hostPackage = packageName
        Bt.init(classLoader)
        val serviceClass = XposedHelpers.findClassIfExists(CLASS_A2DP_SERVICE, classLoader)
        if (serviceClass == null) {
            XLog.d("$packageName に A2dpService が無いので何もしない")
            return
        }
        hookLifecycle(serviceClass)
        hookTriggers(serviceClass)
        hookEnforce(serviceClass)
        hookSelectableGate(classLoader)
        XLog.i("フックを設置した (host=$packageName, module=${BuildConfig.VERSION_NAME})")
    }

    private fun hookLifecycle(serviceClass: Class<*>) {
        XposedBridge.hookAllMethods(serviceClass, "start", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                onServiceReady(param.thisObject)
            }
        })
        XposedBridge.hookAllMethods(serviceClass, "cleanup", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                service = null
                statuses.clear()
            }
        })
    }

    private fun hookTriggers(serviceClass: Class<*>) {
        XposedBridge.hookAllMethods(serviceClass, "connectionStateChanged", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                service = param.thisObject
                val device = param.args.getOrNull(0) as? BluetoothDevice ?: return
                when (param.args.getOrNull(2) as? Int) {
                    STATE_CONNECTED -> scheduleApply(device, "接続")
                    STATE_DISCONNECTED -> forget(device)
                }
            }
        })
        XposedBridge.hookAllMethods(serviceClass, "codecConfigUpdated", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                service = param.thisObject
                val device = param.args.getOrNull(0) as? BluetoothDevice ?: return
                onCodecObserved(device, param.args.getOrNull(1))
            }
        })
    }

    private fun hookEnforce(serviceClass: Class<*>) {
        XposedBridge.hookAllMethods(serviceClass, "setCodecConfigPreference", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                service = param.thisObject
                val incoming = param.args.getOrNull(1)
                if (!Bt.codecConfigClass.isInstance(incoming)) return
                if (Applying.isOwn(incoming)) return
                if (!config.enabled || !config.enforce) return
                val device = param.args.getOrNull(0) as? BluetoothDevice ?: return
                val profile = config.profileFor(macOf(device)) ?: return
                if (!profile.enabled || !profile.hasAnyTarget()) return
                val target = buildTarget(profile, codecStatusOf(device)) ?: return
                Applying.begin(target, profile.force)
                param.args[1] = target
                XLog.i("外部からの変更を上書き: ${macOf(device)} -> ${Bt.codecInfo(target)?.summary()}")
            }
        })
    }

    private fun hookSelectableGate(classLoader: ClassLoader) {
        val gate = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val target = param.args.firstOrNull { Applying.allowsForce(it) } ?: return
                param.result = true
                XLog.d("選択可能チェックを迂回: ${Bt.codecInfo(target)?.summary()}")
            }
        }
        XposedHelpers.findClassIfExists(Bt.CLASS_CODEC_STATUS, classLoader)?.let {
            XposedBridge.hookAllMethods(it, "isCodecConfigSelectable", gate)
        }
        XposedHelpers.findClassIfExists(CLASS_A2DP_CODEC_CONFIG, classLoader)?.let {
            XposedBridge.hookAllMethods(it, "isMiuiCodecConfigSelectable", gate)
        }
    }

    private fun onServiceReady(instance: Any) {
        service = instance
        val ctx = (instance as? Context) ?: AndroidAppHelper.currentApplication()
        if (ctx == null) {
            XLog.e("Context が取れなかったので設定を受け取れない")
            return
        }
        context = ctx
        registerReceivers(ctx)
        loadConfigFromPrefs()
        requestConfig(ctx)
    }

    private fun registerReceivers(ctx: Context) {
        if (receiversRegistered) return
        receiversRegistered = true

        val btFilter = IntentFilter().apply {
            addAction(ACTION_CONNECTION_STATE_CHANGED)
            addAction(ACTION_ACTIVE_DEVICE_CHANGED)
            addAction(ACTION_CODEC_CONFIG_CHANGED)
        }
        runCatching {
            ctx.registerReceiver(btReceiver, btFilter, null, worker, Context.RECEIVER_EXPORTED)
        }.onFailure { XLog.e("A2DP ブロードキャストを受け取れない", it) }

        val bridgeFilter = IntentFilter().apply {
            addAction(Bridge.ACTION_PUSH_CONFIG)
            addAction(Bridge.ACTION_REQUEST_STATUS)
            addAction(Bridge.ACTION_APPLY_NOW)
        }
        runCatching {
            ctx.registerReceiver(bridgeReceiver, bridgeFilter, Bridge.PERMISSION, worker, Context.RECEIVER_EXPORTED)
        }.onFailure { XLog.e("設定の受け口を作れない", it) }
    }

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val device = deviceOf(intent) ?: return
            when (action) {
                ACTION_CONNECTION_STATE_CHANGED -> {
                    when (intent.getIntExtra(EXTRA_STATE, -1)) {
                        STATE_CONNECTED -> scheduleApply(device, "接続")
                        STATE_DISCONNECTED -> forget(device)
                    }
                }
                ACTION_ACTIVE_DEVICE_CHANGED -> scheduleApply(device, "アクティブ切替")
                ACTION_CODEC_CONFIG_CHANGED -> onCodecObserved(device, intent.codecStatus())
            }
        }
    }

    private val bridgeReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                Bridge.ACTION_PUSH_CONFIG -> {
                    val received = AppConfig.decode(intent.getStringExtra(Bridge.EXTRA_JSON))
                    val changed = received != config
                    config = received
                    configLoaded = true
                    XLog.verbose = received.verbose
                    XLog.i("設定を受信 (${received.profiles.size} 台, 有効=${received.enabled})")
                    if (changed) reapplyConnected("設定更新")
                    refreshAllConnected()
                    sendReport(null)
                }
                Bridge.ACTION_REQUEST_STATUS -> {
                    refreshAllConnected()
                    sendReport(null)
                }
                Bridge.ACTION_APPLY_NOW -> {
                    val mac = intent.getStringExtra(Bridge.EXTRA_MAC)?.uppercase()
                    connectedDevices().filter { mac == null || macOf(it) == mac }
                        .forEach { scheduleApply(it, "手動適用", immediate = true) }
                }
            }
        }
    }

    private fun scheduleApply(device: BluetoothDevice, reason: String, immediate: Boolean = false) {
        val mac = macOf(device) ?: return
        if (!configLoaded) context?.let { requestConfig(it) }
        if (!config.enabled) return
        val profile = config.profileFor(mac) ?: return
        if (!profile.enabled || !profile.hasAnyTarget()) return
        val delay = if (immediate) 0L else profile.delayMs.toLong().coerceAtLeast(0L)
        val token = mac.intern()
        applyingMacs.add(mac)
        worker.removeCallbacksAndMessages(token)
        worker.postDelayed({ applyWithRetry(device, profile, 1, reason) }, token, delay)
    }

    private fun reapplyConnected(reason: String) {
        connectedDevices().forEach { scheduleApply(it, reason) }
    }

    private fun applyWithRetry(device: BluetoothDevice, profile: DeviceProfile, attempt: Int, reason: String) {
        val mac = macOf(device) ?: return
        val svc = service
        if (svc == null) {
            applyingMacs.remove(mac)
            return
        }
        val status = codecStatusOf(device)
        val current = Bt.codecInfo(Bt.currentConfig(status))

        if (current != null && matches(current, profile)) {
            refreshStatus(device, status, "適用済み: ${current.summary()}")
            sendReport(mac)
            endCycle(device, mac, current.summary(), null)
            return
        }

        val target = buildTarget(profile, status)
        if (target == null) {
            refreshStatus(device, status, "設定を組み立てられなかった")
            sendReport(mac)
            applyingMacs.remove(mac)
            return
        }

        val maxAttempts = profile.retries.coerceIn(1, 10)
        val lastAttempt = attempt >= maxAttempts

        if (profile.autoEnableHd) ensureOptionalCodecs(svc, device, target)

        if (profile.viaSbc && attempt == 1 && current?.codecType != CodecKeys.CODEC_TYPE_SBC) {
            applySbc(svc, device, status)
            worker.postDelayed(
                { applyWithRetry(device, profile.copy(viaSbc = false), attempt, reason) },
                mac.intern(),
                SBC_STEP_DELAY_MS,
            )
            return
        }

        val info = Bt.codecInfo(target)
        XLog.i("適用 $mac ${info?.summary()} (試行 $attempt/$maxAttempts, $reason)")
        setCodec(svc, device, target, profile.force, useNative = profile.force && lastAttempt)

        worker.postDelayed({
            val after = codecStatusOf(device)
            val now = Bt.codecInfo(Bt.currentConfig(after))
            when {
                now != null && matches(now, profile) -> {
                    XLog.i("反映を確認: $mac ${now.summary()}")
                    refreshStatus(device, after, "適用済み: ${now.summary()}")
                    sendReport(mac)
                    endCycle(device, mac, now.summary(), null)
                }
                !lastAttempt -> applyWithRetry(device, profile, attempt + 1, reason)
                else -> {
                    XLog.i("反映されなかった: $mac 現在=${now?.summary()} 目標=${info?.summary()}")
                    refreshStatus(device, after, "適用できなかった (現在 ${now?.summary() ?: "不明"})")
                    sendReport(mac)
                    endCycle(device, mac, now?.summary(), info?.summary() ?: "指定した設定")
                }
            }
        }, mac.intern(), profile.retryDelayMs.toLong().coerceIn(300L, 30_000L))
    }

    private fun matches(current: CodecInfo, profile: DeviceProfile): Boolean {
        if (profile.codecType != CodecKeys.KEEP_INT && current.codecType != profile.codecType) return false
        if (profile.sampleRate != CodecKeys.KEEP_MASK && current.sampleRate != profile.sampleRate) return false
        if (profile.bitsPerSample != CodecKeys.KEEP_MASK && current.bitsPerSample != profile.bitsPerSample) return false
        if (profile.channelMode != CodecKeys.KEEP_MASK && current.channelMode != profile.channelMode) return false
        if (profile.codecSpecific1 != CodecKeys.KEEP_LONG &&
            CodecKeys.isLdac(current.codecName) &&
            current.codecSpecific1 != profile.codecSpecific1
        ) return false
        return true
    }

    private fun buildTarget(profile: DeviceProfile, codecStatus: Any?): Any? {
        val current = Bt.codecInfo(Bt.currentConfig(codecStatus))
        val codecType = when {
            profile.codecType != CodecKeys.KEEP_INT -> profile.codecType
            current != null -> current.codecType
            else -> return null
        }
        val capability = capabilityFor(codecStatus, codecType)
        val sameCodec = current?.codecType == codecType
        val sampleRate = pickMask(profile.sampleRate, current?.sampleRate?.takeIf { sameCodec }, capability?.sampleRate, CodecKeys.SAMPLE_RATES)
        val bits = pickMask(profile.bitsPerSample, current?.bitsPerSample?.takeIf { sameCodec }, capability?.bitsPerSample, CodecKeys.BIT_DEPTHS)
        val channel = pickMask(profile.channelMode, current?.channelMode?.takeIf { sameCodec }, capability?.channelMode, CodecKeys.CHANNEL_MODES)
        val cs1 = when {
            profile.codecSpecific1 != CodecKeys.KEEP_LONG -> profile.codecSpecific1
            sameCodec -> current?.codecSpecific1 ?: 0L
            else -> 0L
        }
        return Bt.buildCodecConfig(
            codecType = codecType,
            sampleRate = sampleRate,
            bitsPerSample = bits,
            channelMode = channel,
            codecSpecific1 = cs1,
            codecSpecific2 = profile.codecSpecific2.coerceAtLeast(0L),
            codecSpecific3 = profile.codecSpecific3.coerceAtLeast(0L),
            codecSpecific4 = profile.codecSpecific4.coerceAtLeast(0L),
        )
    }

    private fun pickMask(
        requested: Int,
        currentValue: Int?,
        capability: Int?,
        table: List<Pair<Int, String>>,
    ): Int {
        if (requested != CodecKeys.KEEP_MASK) return requested
        val allowed = capability ?: 0
        if (currentValue != null && currentValue != 0 && (allowed == 0 || currentValue and allowed != 0)) {
            return currentValue
        }
        val pool = if (allowed == 0) table.map { it.first } else table.map { it.first }.filter { it and allowed != 0 }
        return pool.lastOrNull() ?: table.first().first
    }

    private fun capabilityFor(codecStatus: Any?, codecType: Int): CodecInfo? {
        val selectable = Bt.selectableCapabilities(codecStatus).firstOrNull { Bt.codecTypeOf(it) == codecType }
        val local = Bt.localCapabilities(codecStatus).firstOrNull { Bt.codecTypeOf(it) == codecType }
        return Bt.codecInfo(selectable ?: local)
    }

    private fun setCodec(svc: Any, device: BluetoothDevice, target: Any, force: Boolean, useNative: Boolean): Boolean =
        runCatching {
            Applying.begin(target, force)
            if (useNative) {
                val native = nativeInterface(svc)
                if (native == null) {
                    XLog.e("ネイティブ層が取れないので通常経路で送る")
                    XposedHelpers.callMethod(svc, "setCodecConfigPreference", device, target)
                } else {
                    XLog.i("ネイティブ層へ直接指定する")
                    XposedHelpers.callMethod(native, "setCodecConfigPreference", device, Bt.codecConfigArray(target))
                }
            } else {
                XposedHelpers.callMethod(svc, "setCodecConfigPreference", device, target)
            }
            true
        }.onFailure { XLog.e("コーデック指定に失敗", it) }.getOrDefault(false)

    private fun applySbc(svc: Any, device: BluetoothDevice, codecStatus: Any?) {
        val capability = capabilityFor(codecStatus, CodecKeys.CODEC_TYPE_SBC) ?: return
        val sbc = Bt.buildCodecConfig(
            codecType = CodecKeys.CODEC_TYPE_SBC,
            sampleRate = pickMask(CodecKeys.KEEP_MASK, null, capability.sampleRate, CodecKeys.SAMPLE_RATES),
            bitsPerSample = pickMask(CodecKeys.KEEP_MASK, null, capability.bitsPerSample, CodecKeys.BIT_DEPTHS),
            channelMode = pickMask(CodecKeys.KEEP_MASK, null, capability.channelMode, CodecKeys.CHANNEL_MODES),
            codecSpecific1 = 0, codecSpecific2 = 0, codecSpecific3 = 0, codecSpecific4 = 0,
        ) ?: return
        XLog.d("いったん SBC を経由する")
        setCodec(svc, device, sbc, force = false, useNative = false)
    }

    private fun ensureOptionalCodecs(svc: Any, device: BluetoothDevice, target: Any) {
        if (Bt.codecTypeOf(target) == CodecKeys.CODEC_TYPE_SBC) return
        runCatching {
            val supported = XposedHelpers.callMethod(svc, "getSupportsOptionalCodecs", device) as? Int
            if (supported != CodecKeys.OPTIONAL_CODECS_SUPPORTED) return
            val enabled = XposedHelpers.callMethod(svc, "getOptionalCodecsEnabled", device) as? Int
            if (enabled == CodecKeys.OPTIONAL_CODECS_PREF_ENABLED) return
            XLog.i("HD オーディオを有効化する: ${macOf(device)}")
            XposedHelpers.callMethod(svc, "setOptionalCodecsEnabled", device, CodecKeys.OPTIONAL_CODECS_PREF_ENABLED)
            XposedHelpers.callMethod(svc, "enableOptionalCodecs", device)
        }.onFailure { XLog.d("HD オーディオの有効化に失敗: ${it.message}") }
    }

    private fun nativeInterface(svc: Any): Any? {
        listOf("mNativeInterface", "mA2dpNativeInterface").forEach { field ->
            runCatching { XposedHelpers.getObjectField(svc, field) }.getOrNull()?.let { return it }
        }
        return runCatching {
            val codecConfig = XposedHelpers.getObjectField(svc, "mA2dpCodecConfig")
            XposedHelpers.getObjectField(codecConfig, "mA2dpNativeInterface")
        }.getOrNull()
    }

    private fun codecStatusOf(device: BluetoothDevice): Any? {
        val svc = service ?: return null
        return runCatching { XposedHelpers.callMethod(svc, "getCodecStatus", device) }
            .onFailure { XLog.d("getCodecStatus 失敗: ${it.message}") }
            .getOrNull()
    }

    private fun connectedDevices(): List<BluetoothDevice> {
        val svc = service ?: return emptyList()
        return runCatching {
            (XposedHelpers.callMethod(svc, "getConnectedDevices") as? List<*>)
                ?.filterIsInstance<BluetoothDevice>()
                .orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun refreshAllConnected() {
        connectedDevices().forEach { refreshStatus(it, codecStatusOf(it)) }
    }

    private fun refreshStatus(device: BluetoothDevice, codecStatus: Any?, note: String? = null): DeviceStatus? {
        val mac = macOf(device) ?: return null
        val svc = service ?: return null
        val connected = runCatching {
            XposedHelpers.callMethod(svc, "getConnectionState", device) as? Int == STATE_CONNECTED
        }.getOrDefault(false)
        val active = runCatching {
            device == XposedHelpers.callMethod(svc, "getActiveDevice")
        }.getOrDefault(false)
        val status = DeviceStatus(
            mac = mac,
            name = runCatching { device.name }.getOrNull().orEmpty(),
            connected = connected,
            active = active,
            current = Bt.codecInfo(Bt.currentConfig(codecStatus)),
            selectable = Bt.selectableCapabilities(codecStatus).mapNotNull { Bt.codecInfo(it) },
            local = Bt.localCapabilities(codecStatus).mapNotNull { Bt.codecInfo(it) },
            note = note ?: statuses[mac]?.note.orEmpty(),
            updatedAt = System.currentTimeMillis(),
        )
        statuses[mac] = status
        return status
    }

    private fun forget(device: BluetoothDevice) {
        val mac = macOf(device) ?: return
        worker.removeCallbacksAndMessages(mac.intern())
        applyingMacs.remove(mac)
        announced.remove(mac)
        statuses[mac]?.let { statuses[mac] = it.copy(connected = false, active = false, current = null) }
    }

    private fun onCodecObserved(device: BluetoothDevice, codecStatus: Any?) {
        val mac = macOf(device) ?: return
        refreshStatus(device, codecStatus)
        sendReport(mac)
        if (applyingMacs.contains(mac)) return
        val summary = Bt.codecInfo(Bt.currentConfig(codecStatus))?.summary() ?: return
        if (announced.put(mac, summary) != summary) announceChanged(device, summary)
    }

    private fun endCycle(device: BluetoothDevice, mac: String, summary: String?, failedTarget: String?) {
        applyingMacs.remove(mac)
        if (failedTarget != null) {
            summary?.let { announced[mac] = it }
            announceFailed(device, failedTarget, summary)
            return
        }
        if (summary == null) return
        if (announced.put(mac, summary) != summary) announceChanged(device, summary)
    }

    private fun announceChanged(device: BluetoothDevice, summary: String) {
        val ctx = context ?: return
        toast(HookStrings.format(ctx, "hook_codec_changed", "%1\$s: switched to %2\$s", label(device), summary))
    }

    private fun announceFailed(device: BluetoothDevice, target: String, current: String?) {
        val ctx = context ?: return
        val text = if (current == null) {
            HookStrings.format(ctx, "hook_codec_failed", "%1\$s: could not switch to %2\$s", label(device), target)
        } else {
            HookStrings.format(
                ctx, "hook_codec_failed_with_current",
                "%1\$s: could not switch to %2\$s (now %3\$s)", label(device), target, current,
            )
        }
        toast(text)
    }

    private fun toast(text: String) {
        if (!config.notifyChanges) return
        val ctx = context ?: return
        mainHandler.post {
            runCatching { Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show() }
                .onFailure { XLog.d("トーストを出せない: ${it.message}") }
        }
    }

    private fun label(device: BluetoothDevice): String =
        runCatching { device.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: macOf(device).orEmpty()

    private fun sendReport(mac: String?) {
        val ctx = context ?: return
        val devices = if (mac == null) statuses.values.toList() else listOfNotNull(statuses[mac])
        val report = StatusReport(
            moduleVersion = BuildConfig.VERSION_NAME,
            hostPackage = hostPackage,
            configHash = config.hash(),
            configLoaded = configLoaded,
            devices = devices,
            codecNames = Bt.codecNames.toMap(),
            timestamp = System.currentTimeMillis(),
        )
        val intent = Intent(Bridge.ACTION_REPORT).apply {
            setPackage(Bridge.PKG)
            putExtra(Bridge.EXTRA_JSON, report.encode())
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        }
        runCatching { ctx.sendBroadcast(intent) }.onFailure { XLog.d("報告を送れない: ${it.message}") }
    }

    private fun requestConfig(ctx: Context) {
        val intent = Intent(Bridge.ACTION_REQUEST_CONFIG).apply {
            setClassName(Bridge.PKG, Bridge.HOOK_REQUEST_RECEIVER)
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
        }
        runCatching { ctx.sendBroadcast(intent) }.onFailure { XLog.d("設定を要求できない: ${it.message}") }
    }

    private fun loadConfigFromPrefs() {
        if (configLoaded) return
        runCatching {
            val prefs = XSharedPreferences(Bridge.PKG, "config")
            if (!prefs.file.canRead()) return
            val json = prefs.getString("json", null) ?: return
            config = AppConfig.decode(json)
            configLoaded = true
            XLog.verbose = config.verbose
            XLog.i("設定をファイルから読み込んだ (${config.profiles.size} 台)")
        }
    }

    private fun macOf(device: BluetoothDevice?): String? = device?.address?.uppercase()

    @Suppress("DEPRECATION")
    private fun deviceOf(intent: Intent): BluetoothDevice? =
        intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)

    @Suppress("DEPRECATION")
    private fun Intent.codecStatus(): Any? = getParcelableExtra<Parcelable>(EXTRA_CODEC_STATUS)
}
