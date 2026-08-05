package io.github.mame1839.codecanchor.xposed

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import android.os.Build
import android.os.Looper
import android.os.PowerManager
import android.os.Parcelable
import android.os.SystemClock
import android.widget.Toast
import java.lang.reflect.Member
import java.lang.reflect.Method
import android.app.AndroidAppHelper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.mame1839.codecanchor.BuildConfig
import io.github.mame1839.codecanchor.core.AppConfig
import io.github.mame1839.codecanchor.core.ApplyOutcome
import io.github.mame1839.codecanchor.core.Bridge
import io.github.mame1839.codecanchor.core.CodecInfo
import io.github.mame1839.codecanchor.core.CodecKeys
import io.github.mame1839.codecanchor.core.DeviceProfile
import io.github.mame1839.codecanchor.core.DeviceStatus
import io.github.mame1839.codecanchor.core.StatusReport
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// Bluetooth プロセスの Context を持ち続けるのは設計どおり (フックはプロセスと同じ寿命)。
@SuppressLint("StaticFieldLeak")
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

    // 起動直後に保留した通知を、いつまで有効とみなすか
    private const val PENDING_TOAST_TTL_MS = 3 * 60_000L
    private const val CYCLE_SLACK_MS = 2_000L

    // 適用サイクルの寿命。世代が違う run は打ち切り、締切で総時間を縛る。
    private class Cycle(val generation: Int, val deadline: Long)

    @Volatile private var service: Any? = null
    @Volatile private var context: Context? = null
    @Volatile private var hostPackage = ""
    @Volatile private var config = AppConfig()
    @Volatile private var configLoaded = false
    @Volatile private var receiverContext: Context? = null

    private val statuses = ConcurrentHashMap<String, DeviceStatus>()
    private val reported = ConcurrentHashMap<String, DeviceStatus>()
    private val announced = ConcurrentHashMap<String, String>()
    private val pendingToasts = ConcurrentHashMap<String, Pair<String, Long>>()
    private val applyingMacs = ConcurrentHashMap.newKeySet<String>()
    private val cycles = ConcurrentHashMap<String, Cycle>()
    private val generations = AtomicInteger()
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
        val teardown = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                onServiceGone()
            }
        }
        if (XposedBridge.hookAllMethods(serviceClass, "cleanup", teardown).isEmpty()) {
            XLog.d("cleanup が無いので stop で後始末する")
            XposedBridge.hookAllMethods(serviceClass, "stop", teardown)
        }
    }

    // 積んである適用は mac のトークン単位で取り消す。トークン無しの一括削除はレシーバ配送の
    // Runnable まで消して、ブロードキャストが完了しないまま残る。
    private fun onServiceGone() {
        service = null
        applyingMacs.forEach { worker.removeCallbacksAndMessages(it.intern()) }
        cycles.clear()
        applyingMacs.clear()
        statuses.clear()
        reported.clear()
        announced.clear()
        LdacStats.clear()
        unregisterReceivers()
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
                val codecStatus = param.args.getOrNull(1)
                worker.post { safely("コーデック変化の観測") { onCodecObserved(device, codecStatus) } }
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
                val status = codecStatusOf(device)
                // 固定していない項目は呼び出し元の要求をそのまま通すので、現在値ではなく incoming を基底にする。
                val target = buildTarget(profile, status, Bt.codecInfo(incoming))
                if (target == null) {
                    // HD オーディオが無効なときはここで組めない。有効化を伴う通常の適用に回す。
                    scheduleApply(device, "上書き")
                    return
                }
                Applying.begin(target, profile.force)
                param.args[1] = target
                XLog.i("外部からの変更を上書き: ${macOf(device)} -> ${Bt.codecInfo(target)?.summary()}")
                // 差し替えた内容が選択可能でないなら、有効化やリトライを伴う通常の適用も走らせる。
                if (capabilityFor(status, Bt.codecTypeOf(target) ?: return) == null) {
                    scheduleApply(device, "上書きの補完")
                }
            }
        })
    }

    private fun hookSelectableGate(classLoader: ClassLoader) {
        val gate = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!returnsBoolean(param.method)) return
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

    private fun returnsBoolean(method: Member?): Boolean {
        val type = (method as? Method)?.returnType ?: return false
        return type == Boolean::class.javaPrimitiveType || type == java.lang.Boolean::class.java
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

    // start が二度来ると Context が変わる。古い Context に登録を残すと解除できなくなるので張り替える。
    private fun registerReceivers(ctx: Context) {
        if (receiverContext === ctx) return
        unregisterReceivers()

        val btFilter = IntentFilter().apply {
            addAction(ACTION_CONNECTION_STATE_CHANGED)
            addAction(ACTION_ACTIVE_DEVICE_CHANGED)
            addAction(ACTION_CODEC_CONFIG_CHANGED)
            // 端末の起動直後は画面がトーストを出せる状態になる前に接続が終わる。保留したぶんはここで出す。
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        runCatching {
            registerExported(ctx, btReceiver, btFilter, null)
        }.onFailure { XLog.e("A2DP ブロードキャストを受け取れない", it) }

        val bridgeFilter = IntentFilter().apply {
            addAction(Bridge.ACTION_PUSH_CONFIG)
            addAction(Bridge.ACTION_REQUEST_STATUS)
            addAction(Bridge.ACTION_APPLY_NOW)
        }
        runCatching {
            registerExported(ctx, bridgeReceiver, bridgeFilter, Bridge.PERMISSION)
        }.onFailure { XLog.e("設定の受け口を作れない", it) }

        receiverContext = ctx
    }

    private fun registerExported(
        ctx: Context,
        receiver: BroadcastReceiver,
        filter: IntentFilter,
        permission: String?,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(receiver, filter, permission, worker, Context.RECEIVER_EXPORTED)
        } else {
            ctx.registerReceiver(receiver, filter, permission, worker)
        }
    }

    private fun unregisterReceivers() {
        val ctx = receiverContext ?: return
        runCatching { ctx.unregisterReceiver(btReceiver) }
        runCatching { ctx.unregisterReceiver(bridgeReceiver) }
        receiverContext = null
    }

    // フックのコールバックと違い、レシーバとハンドラで投げた例外は Bluetooth プロセスを落とす。
    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            safely("A2DP イベント") { handleBtEvent(intent) }
        }
    }

    private fun handleBtEvent(intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_USER_PRESENT || action == Intent.ACTION_SCREEN_ON) {
            flushPendingToasts()
            return
        }
        val device = deviceOf(intent) ?: return
        when (action) {
            ACTION_CONNECTION_STATE_CHANGED -> when (intent.getIntExtra(EXTRA_STATE, -1)) {
                STATE_CONNECTED -> scheduleApply(device, "接続")
                STATE_DISCONNECTED -> forget(device)
            }

            ACTION_ACTIVE_DEVICE_CHANGED -> scheduleApply(device, "アクティブ切替", skipWhileApplying = true)
            ACTION_CODEC_CONFIG_CHANGED -> onCodecObserved(device, intent.codecStatus())
        }
    }

    private val bridgeReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            safely("設定の受信") { handleBridgeEvent(intent) }
        }
    }

    private fun handleBridgeEvent(intent: Intent?) {
        when (intent?.action) {
            Bridge.ACTION_PUSH_CONFIG -> {
                val json = intent.getStringExtra(Bridge.EXTRA_JSON)
                if (json.isNullOrBlank()) {
                    XLog.d("中身のない設定は無視する")
                    return
                }
                val received = AppConfig.decode(json)
                if (received == null) {
                    XLog.e("受け取った設定を読めないので今の設定を保つ")
                    return
                }
                val changed = received != config
                config = received
                configLoaded = true
                XLog.verbose = received.verbose
                XLog.i("設定を受信 (${received.profiles.size} 台, 有効=${received.enabled})")
                if (changed) reapplyConnected("設定更新")
                refreshAllConnected(readLdac = true)
                sendReport(null)
            }

            Bridge.ACTION_REQUEST_STATUS -> {
                refreshAllConnected(readLdac = true)
                sendReport(null)
            }

            Bridge.ACTION_APPLY_NOW -> {
                val mac = intent.getStringExtra(Bridge.EXTRA_MAC)?.uppercase()
                connectedDevices().filter { mac == null || macOf(it) == mac }
                    .forEach { scheduleApply(it, "手動適用", immediate = true) }
            }
        }
    }

    private fun safely(what: String, block: () -> Unit) {
        runCatching(block).onFailure { XLog.e("$what の処理で例外", it) }
    }

    private fun scheduleApply(
        device: BluetoothDevice,
        reason: String,
        immediate: Boolean = false,
        skipWhileApplying: Boolean = false,
    ) {
        worker.post { safely("適用の予約") { scheduleOnWorker(device, reason, immediate, skipWhileApplying) } }
    }

    private fun scheduleOnWorker(
        device: BluetoothDevice,
        reason: String,
        immediate: Boolean,
        skipWhileApplying: Boolean,
    ) {
        val mac = macOf(device) ?: return
        if (!configLoaded) context?.let { requestConfig(it) }
        if (!config.enabled) return
        val profile = config.profileFor(mac) ?: return
        if (!profile.enabled || !profile.hasAnyTarget()) return
        if (skipWhileApplying && applyingMacs.contains(mac)) {
            XLog.d("適用中なので見送る: $mac ($reason)")
            return
        }
        val delay = if (immediate) 0L else profile.delayMs.coerceIn(DeviceProfile.DELAY_MS_RANGE).toLong()
        val token = mac.intern()
        val generation = startCycle(mac, profile, resetBudget = immediate)
        applyingMacs.add(mac)
        worker.removeCallbacksAndMessages(token)
        worker.postDelayed({ runApply(device, profile, 1, reason, generation) }, token, delay)
    }

    // 再トリガは世代を進めて古い run を無効にする。進行中のサイクルの締切は引き継ぐので、
    // 適用が誘発した再通知で試行が際限なく伸びない。手動適用は新しい要求なので引き直す。
    private fun startCycle(mac: String, profile: DeviceProfile, resetBudget: Boolean): Int {
        val now = SystemClock.uptimeMillis()
        val generation = generations.incrementAndGet()
        return cycles.compute(mac) { _, prev ->
            val carried = prev?.deadline?.takeIf { !resetBudget && now <= it }
            Cycle(generation, carried ?: (now + budgetMs(profile)))
        }!!.generation
    }

    private fun budgetMs(profile: DeviceProfile): Long =
        profile.delayMs.toLong().coerceAtLeast(0L) + SBC_STEP_DELAY_MS +
            profile.retries.coerceIn(1, 10) * (retryDelayMs(profile) + CYCLE_SLACK_MS)

    private fun retryDelayMs(profile: DeviceProfile): Long =
        profile.retryDelayMs.toLong().coerceIn(300L, 30_000L)

    private fun cycleAlive(mac: String, generation: Int, device: BluetoothDevice): Boolean {
        val cycle = cycles[mac] ?: return false
        if (cycle.generation != generation) return false
        if (SystemClock.uptimeMillis() > cycle.deadline) {
            XLog.d("適用の時間切れ: $mac")
            return false
        }
        if (!stillConnected(device)) {
            XLog.d("接続が切れたので適用をやめる: $mac")
            return false
        }
        return true
    }

    // 新しい世代が始まっていたら、その記録は消さない。
    private fun finishCycle(mac: String, generation: Int) {
        val current = cycles[mac]
        if (current != null && current.generation != generation) return
        cycles.remove(mac)
        applyingMacs.remove(mac)
    }

    // 状態が読めないときは打ち切らない (メーカー改造で欠けている可能性がある)。
    private fun stillConnected(device: BluetoothDevice): Boolean {
        val svc = service ?: return false
        val state = runCatching { XposedHelpers.callMethod(svc, "getConnectionState", device) as? Int }.getOrNull()
        return state == null || state == STATE_CONNECTED
    }

    private fun reapplyConnected(reason: String) {
        connectedDevices().forEach { scheduleApply(it, reason) }
    }

    private fun runApply(
        device: BluetoothDevice,
        profile: DeviceProfile,
        attempt: Int,
        reason: String,
        generation: Int,
    ) {
        val mac = macOf(device) ?: return
        runCatching { applyWithRetry(device, mac, profile, attempt, reason, generation) }.onFailure {
            finishCycle(mac, generation)
            XLog.e("適用の処理で例外", it)
        }
    }

    private fun applyWithRetry(
        device: BluetoothDevice,
        mac: String,
        profile: DeviceProfile,
        attempt: Int,
        reason: String,
        generation: Int,
    ) {
        val svc = service
        if (svc == null || !cycleAlive(mac, generation, device)) {
            finishCycle(mac, generation)
            return
        }
        var status = codecStatusOf(device)
        val current = Bt.codecInfo(Bt.currentConfig(status))

        if (current != null && matches(current, profile)) {
            refreshStatus(device, status, ApplyOutcome.APPLIED, current.summary())
            sendReport(mac)
            endCycle(device, mac, generation, current.summary(), null)
            return
        }

        val maxAttempts = profile.retries.coerceIn(DeviceProfile.RETRIES_RANGE)
        val lastAttempt = attempt >= maxAttempts

        // HD オーディオが無効だと非必須コーデックが選択肢から消え、目標を組めなくなる。組む前に有効化する。
        if (profile.autoEnableHd && ensureOptionalCodecs(svc, device, profile.codecType)) {
            status = codecStatusOf(device)
        }

        val target = buildTarget(profile, status)
        if (target == null) {
            // 有効化の直後はケーパビリティの再交渉が終わっていないことがあるので、残り試行があれば待つ
            if (!lastAttempt) {
                worker.postDelayed(
                    { runApply(device, profile, attempt + 1, reason, generation) },
                    mac.intern(),
                    retryDelayMs(profile),
                )
                return
            }
            refreshStatus(device, status, ApplyOutcome.UNDECIDED)
            sendReport(mac)
            finishCycle(mac, generation)
            return
        }

        if (profile.viaSbc && attempt == 1 && current?.codecType != CodecKeys.CODEC_TYPE_SBC) {
            applySbc(svc, device, status)
            worker.postDelayed(
                { runApply(device, profile.copy(viaSbc = false), attempt, reason, generation) },
                mac.intern(),
                SBC_STEP_DELAY_MS,
            )
            return
        }

        val info = Bt.codecInfo(target)
        XLog.i("適用 $mac ${info?.summary()} (試行 $attempt/$maxAttempts, $reason)")
        setCodec(
            svc, device, target, profile.force,
            useNative = profile.force && lastAttempt && locallySupported(status, target),
        )

        worker.postDelayed({
            runCatching { verifyApply(device, mac, profile, attempt, lastAttempt, reason, generation, info) }
                .onFailure {
                    finishCycle(mac, generation)
                    XLog.e("反映の確認で例外", it)
                }
        }, mac.intern(), retryDelayMs(profile))
    }

    private fun verifyApply(
        device: BluetoothDevice,
        mac: String,
        profile: DeviceProfile,
        attempt: Int,
        lastAttempt: Boolean,
        reason: String,
        generation: Int,
        target: CodecInfo?,
    ) {
        if (!cycleAlive(mac, generation, device)) {
            finishCycle(mac, generation)
            return
        }
        val after = codecStatusOf(device)
        val now = Bt.codecInfo(Bt.currentConfig(after))
        when {
            now != null && matches(now, profile) -> {
                XLog.i("反映を確認: $mac ${now.summary()}")
                refreshStatus(device, after, ApplyOutcome.APPLIED, now.summary())
                sendReport(mac)
                endCycle(device, mac, generation, now.summary(), null)
            }

            !lastAttempt -> runApply(device, profile, attempt + 1, reason, generation)

            else -> {
                XLog.i("反映されなかった: $mac 現在=${now?.summary()} 目標=${target?.summary()}")
                refreshStatus(device, after, ApplyOutcome.FAILED, now?.summary().orEmpty())
                sendReport(mac)
                val summary = target?.summary()
                if (summary != null) endCycle(device, mac, generation, now?.summary(), summary)
                else finishCycle(mac, generation)
            }
        }
    }

    private fun matches(current: CodecInfo, profile: DeviceProfile): Boolean {
        if (profile.codecType != CodecKeys.KEEP_INT && current.codecType != profile.codecType) return false
        if (profile.sampleRate != CodecKeys.KEEP_MASK && current.sampleRate != profile.sampleRate) return false
        if (profile.bitsPerSample != CodecKeys.KEEP_MASK && current.bitsPerSample != profile.bitsPerSample) return false
        if (profile.channelMode != CodecKeys.KEEP_MASK && current.channelMode != profile.channelMode) return false
        if (profile.codecSpecific1 != CodecKeys.KEEP_LONG &&
            current.codecSpecific1 != profile.codecSpecific1
        ) return false
        return true
    }

    private fun buildTarget(profile: DeviceProfile, codecStatus: Any?, base: CodecInfo? = null): Any? {
        val current = base ?: Bt.codecInfo(Bt.currentConfig(codecStatus))
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
        if (sampleRate == null || bits == null || channel == null) {
            XLog.d("codec=$codecType の対応範囲が分からないので目標を決めない")
            return null
        }
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

    // 対応範囲が分からないまま表の最大値を選ぶと、端末が受け付けない組み合わせになる。
    // 引き継げる現在値も無いなら決めずに null を返す。
    private fun pickMask(
        requested: Int,
        currentValue: Int?,
        capability: Int?,
        table: List<Pair<Int, String>>,
    ): Int? {
        if (requested != CodecKeys.KEEP_MASK) return requested
        val allowed = capability ?: 0
        if (currentValue != null && currentValue != 0 && (allowed == 0 || currentValue and allowed != 0)) {
            return currentValue
        }
        if (allowed == 0) return null
        return table.map { it.first }.lastOrNull { it and allowed != 0 }
    }

    private fun capabilityFor(codecStatus: Any?, codecType: Int): CodecInfo? {
        val selectable = Bt.selectableCapabilities(codecStatus).firstOrNull { Bt.codecTypeOf(it) == codecType }
        val local = Bt.localCapabilities(codecStatus).firstOrNull { Bt.codecTypeOf(it) == codecType }
        return Bt.codecInfo(selectable ?: local)
    }

    // ネイティブ直叩きは検証を全部飛ばす。端末が持っていないコーデックは載せない。
    private fun locallySupported(codecStatus: Any?, target: Any): Boolean {
        val codecType = Bt.codecTypeOf(target) ?: return false
        return Bt.localCapabilities(codecStatus).any { Bt.codecTypeOf(it) == codecType }
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
            sampleRate = pickMask(CodecKeys.KEEP_MASK, null, capability.sampleRate, CodecKeys.SAMPLE_RATES) ?: return,
            bitsPerSample = pickMask(CodecKeys.KEEP_MASK, null, capability.bitsPerSample, CodecKeys.BIT_DEPTHS) ?: return,
            channelMode = pickMask(CodecKeys.KEEP_MASK, null, capability.channelMode, CodecKeys.CHANNEL_MODES) ?: return,
            codecSpecific1 = 0, codecSpecific2 = 0, codecSpecific3 = 0, codecSpecific4 = 0,
        ) ?: return
        XLog.d("いったん SBC を経由する")
        setCodec(svc, device, sbc, force = false, useNative = false)
    }

    // 有効化したときだけ true。呼び出し元はケーパビリティを読み直す。
    private fun ensureOptionalCodecs(svc: Any, device: BluetoothDevice, codecType: Int): Boolean {
        if (codecType == CodecKeys.CODEC_TYPE_SBC) return false
        return runCatching {
            val supported = XposedHelpers.callMethod(svc, "getSupportsOptionalCodecs", device) as? Int
            if (supported != CodecKeys.OPTIONAL_CODECS_SUPPORTED) return false
            val enabled = XposedHelpers.callMethod(svc, "getOptionalCodecsEnabled", device) as? Int
            if (enabled == CodecKeys.OPTIONAL_CODECS_PREF_ENABLED) return false
            XLog.i("HD オーディオを有効化する: ${macOf(device)}")
            XposedHelpers.callMethod(svc, "setOptionalCodecsEnabled", device, CodecKeys.OPTIONAL_CODECS_PREF_ENABLED)
            XposedHelpers.callMethod(svc, "enableOptionalCodecs", device)
            true
        }.onFailure { XLog.d("HD オーディオの有効化に失敗: ${it.message}") }.getOrDefault(false)
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

    private fun refreshAllConnected(readLdac: Boolean = false) {
        connectedDevices().forEach { device ->
            val codecStatus = codecStatusOf(device)
            if (readLdac) refreshLdacStats(device, codecStatus)
            refreshStatus(device, codecStatus)
        }
    }

    // ダンプの A2DP 区画は今のストリーム 1 本分しかないので、複数台が LDAC で繋がっているときは
    // アクティブな機器にだけ紐づける (他の機器に他人の値を出さない)。
    private fun refreshLdacStats(device: BluetoothDevice, codecStatus: Any?) {
        val mac = macOf(device) ?: return
        val svc = service ?: return
        // オフロード中はホスト側のエンコーダが動かないので、ダンプの値を更新する主体が居ない
        // (ネゴシエートした公称値がそのまま残る)。1 回で数秒かかる読み取りを、意味の無い値のために払わない。
        if (a2dpOffloadEnabled()) {
            LdacStats.forget(mac)
            return
        }
        val active = activeDevice()
        val current = Bt.codecInfo(Bt.currentConfig(codecStatus))
        if (!CodecKeys.isLdac(current?.displayName()) || (active != null && active != device)) {
            LdacStats.forget(mac)
            return
        }
        LdacStats.refresh(svc, mac)
    }

    private fun activeDevice(): Any? {
        val svc = service ?: return null
        return runCatching { XposedHelpers.callMethod(svc, "getActiveDevice") }.getOrNull()
    }

    // Bluetooth プロセスの中で動くので BLUETOOTH_CONNECT は常に許可されている
    @SuppressLint("MissingPermission")
    private fun refreshStatus(
        device: BluetoothDevice,
        codecStatus: Any?,
        outcome: ApplyOutcome? = null,
        outcomeValue: String = "",
    ): DeviceStatus? {
        val mac = macOf(device) ?: return null
        val svc = service ?: return null
        val connected = runCatching {
            XposedHelpers.callMethod(svc, "getConnectionState", device) as? Int == STATE_CONNECTED
        }.getOrDefault(false)
        val active = device == activeDevice()
        val current = Bt.codecInfo(Bt.currentConfig(codecStatus))
        // 読み取りは状態要求の経路だけで行う (適用サイクル中の報告にも直近の値を載せる)。
        val ldac = if (CodecKeys.isLdac(current?.displayName())) LdacStats.cached(mac) else null
        val fresh = DeviceStatus(
            mac = mac,
            name = runCatching { device.name }.getOrNull().orEmpty(),
            connected = connected,
            active = active,
            current = current,
            ldacQualityMode = ldac?.mode.orEmpty(),
            ldacBitrateKbps = ldac?.kbps ?: 0,
            selectable = Bt.selectableCapabilities(codecStatus).mapNotNull { Bt.codecInfo(it) },
            local = Bt.localCapabilities(codecStatus).mapNotNull { Bt.codecInfo(it) },
            outcome = outcome ?: ApplyOutcome.NONE,
            outcomeValue = outcomeValue,
            updatedAt = System.currentTimeMillis(),
        )
        // 適用結果の引き継ぎは読みと書きを 1 操作にまとめる。別スレッドの観測で APPLIED が消える。
        return statuses.compute(mac) { _, prev ->
            if (outcome != null) {
                fresh
            } else {
                fresh.copy(outcome = prev?.outcome ?: ApplyOutcome.NONE, outcomeValue = prev?.outcomeValue.orEmpty())
            }
        }
    }

    private fun forget(device: BluetoothDevice) {
        val mac = macOf(device) ?: return
        worker.removeCallbacksAndMessages(mac.intern())
        cycles.remove(mac)
        applyingMacs.remove(mac)
        announced.remove(mac)
        pendingToasts.remove(mac)
        reported.remove(mac)
        LdacStats.forget(mac)
        statuses.computeIfPresent(mac) { _, prev ->
            prev.copy(
                connected = false,
                active = false,
                current = null,
                ldacQualityMode = "",
                ldacBitrateKbps = 0,
            )
        }
    }

    // 接続時はコーデック変更の通知が接続完了より先に届く。これから自分で変えるコーデックについて
    // 「変更しました」と言わないよう、目標と一致するまでは黙っておく。
    private fun onCodecObserved(device: BluetoothDevice, codecStatus: Any?) {
        val mac = macOf(device) ?: return
        val status = refreshStatus(device, codecStatus)
        if (changedSinceReport(mac, status)) sendReport(mac)
        if (applyingMacs.contains(mac)) return
        val info = Bt.codecInfo(Bt.currentConfig(codecStatus)) ?: return
        val profile = config.profileFor(mac)
        val managed = config.enabled && profile != null && profile.enabled && profile.hasAnyTarget()
        if (managed && !matches(info, profile!!)) return
        val summary = info.summary()
        if (announced.put(mac, summary) != summary) announceChanged(device, summary)
    }

    // 同じ変化について codecConfigUpdated のフックとブロードキャストの両方から呼ばれる。
    private fun changedSinceReport(mac: String, status: DeviceStatus?): Boolean {
        if (status == null) return true
        val prev = reported.put(mac, status)
        return prev == null || prev.copy(updatedAt = status.updatedAt) != status
    }

    private fun endCycle(
        device: BluetoothDevice,
        mac: String,
        generation: Int,
        summary: String?,
        failedTarget: String?,
    ) {
        finishCycle(mac, generation)
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
        toast(
            macOf(device).orEmpty(),
            HookStrings.format(ctx, "hook_codec_changed", "%1\$s: switched to %2\$s", label(device), summary),
        )
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
        toast(macOf(device).orEmpty(), text)
    }

    // 画面が消えている / ロック中は出しても捨てられるので保留し、使える状態になってから出す。
    private fun toast(key: String, text: String) {
        if (!config.notifyChanges) return
        val ctx = context ?: return
        if (!screenUsable(ctx)) {
            pendingToasts[key] = text to SystemClock.uptimeMillis()
            XLog.d("画面が使えないので通知を保留: $text")
            return
        }
        showToast(ctx, text)
    }

    private fun showToast(ctx: Context, text: String) {
        mainHandler.post {
            runCatching { Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show() }
                .onFailure { XLog.d("トーストを出せない: ${it.message}") }
        }
    }

    // 判定できないときは出す側に倒す (通知が消えるより、出て困らないほうを選ぶ)。
    private fun screenUsable(ctx: Context): Boolean = runCatching {
        val power = ctx.getSystemService(PowerManager::class.java)
        val keyguard = ctx.getSystemService(KeyguardManager::class.java)
        power?.isInteractive != false && keyguard?.isKeyguardLocked != true
    }.getOrDefault(true)

    private fun flushPendingToasts() {
        if (pendingToasts.isEmpty()) return
        val ctx = context ?: return
        if (!screenUsable(ctx)) return
        val now = SystemClock.uptimeMillis()
        pendingToasts.keys.toList().forEach { key ->
            val entry = pendingToasts.remove(key) ?: return@forEach
            if (now - entry.second <= PENDING_TOAST_TTL_MS) showToast(ctx, entry.first)
        }
    }

    @SuppressLint("MissingPermission")
    private fun label(device: BluetoothDevice): String =
        runCatching { device.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: macOf(device).orEmpty()

    private fun sendReport(mac: String?) {
        val ctx = context ?: return
        runCatching { buildAndSendReport(ctx, mac) }.onFailure { XLog.d("報告を送れない: ${it.message}") }
    }

    private fun buildAndSendReport(ctx: Context, mac: String?) {
        val devices = if (mac == null) statuses.values.toList() else listOfNotNull(statuses[mac])
        val report = StatusReport(
            moduleVersion = BuildConfig.VERSION_NAME,
            hostPackage = hostPackage,
            configHash = config.hash(),
            configLoaded = configLoaded,
            a2dpOffloadEnabled = a2dpOffloadEnabled(),
            devices = devices,
            codecNames = Bt.codecNames.toMap(),
            timestamp = System.currentTimeMillis(),
        )
        val intent = Intent(Bridge.ACTION_REPORT).apply {
            setPackage(Bridge.PKG)
            putExtra(Bridge.EXTRA_JSON, report.encode())
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        }
        ctx.sendBroadcast(intent)
    }

    // オフロード中はエンコードが DSP の中で行われるので、ホスト側のダンプに出る実効ビットレートは
    // 更新されない (値が残っていても測定値ではない)。ダンプを読むかどうかの判断と、画面に理由を
    // 出すための材料として報告に載せる。
    private fun a2dpOffloadEnabled(): Boolean {
        val svc = service ?: return false
        runCatching { XposedHelpers.getBooleanField(svc, "mA2dpOffloadEnabled") }
            .getOrNull()?.let { return it }
        return runCatching {
            val adapter = XposedHelpers.getObjectField(svc, "mAdapterService")
            XposedHelpers.callMethod(adapter, "isA2dpOffloadEnabled") as? Boolean
        }.getOrNull() ?: false
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
            val prefs = XSharedPreferences(Bridge.PKG, Bridge.PREFS_NAME)
            if (!prefs.file.canRead()) {
                XLog.d("設定ファイルを読めない: ${prefs.file.path}")
                return
            }
            val json = prefs.getString(Bridge.PREFS_KEY, null)
            if (json == null) {
                XLog.d("設定ファイルに ${Bridge.PREFS_KEY} が無い")
                return
            }
            val loaded = AppConfig.decode(json)
            if (loaded == null) {
                XLog.e("ファイルの設定が壊れているので使わない")
                return
            }
            config = loaded
            configLoaded = true
            XLog.verbose = config.verbose
            XLog.i("設定をファイルから読み込んだ (${config.profiles.size} 台)")
        }.onFailure { XLog.e("設定ファイルを読み込めない", it) }
    }

    private fun macOf(device: BluetoothDevice?): String? = device?.address?.uppercase()

    @Suppress("DEPRECATION")
    private fun deviceOf(intent: Intent): BluetoothDevice? =
        intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)

    @Suppress("DEPRECATION")
    private fun Intent.codecStatus(): Any? = getParcelableExtra<Parcelable>(EXTRA_CODEC_STATUS)
}
