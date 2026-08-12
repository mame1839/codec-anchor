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

/**
 * 直近の登録操作の結果。**どの機器の、どちら向きの操作だったか**を一緒に持つ —
 * 詳細画面を開き直したときに、別の機器の結果が残って見えないようにするため。
 */
data class EqRegisterReport(
    val mac: String,
    val turnedOn: Boolean,
    val result: EqDevicesResult,
)

/**
 * 直近で EQ の値を書きに行った結果。**どの機器へ書いたか**を一緒に持つ — 枠の持ち主は
 * 画面で開いている機器とは限らないので、これが無いと別の機器の結果を出すことになる。
 */
data class EqParamsReport(
    val mac: String,
    val result: EqParamsResult,
)

/**
 * 探索セッション (好みの EQ を探す) が一時的に鳴らす設定。**永続化しない。**
 *
 * [mac] は正規化済み (`EqDevices.normalizeMac`) の対象機器。機器を持つのは、押す直前に
 * 枠の持ち主が入れ替わっていたとき、別のイヤホンへプレビューの曲線を掛けないため。
 *
 * `EqPreview` (EqCurve.kt) はスライダーのドラッグ中の**絵**の受け皿で別物 — あちらは描画にだけ
 * 効き、こちらは共有メモリへ押す値にだけ効く。
 */
data class EqSessionPreview(val mac: String, val settings: EqSettings)

/** 共有メモリへ書き込みが通った曲線。**同じものを送り直さない**判断だけに使う。 */
private data class SentCurve(val mac: String, val text: String)

/**
 * 曲線を送るまでの静止時間。**最後の操作からこれだけ静かになってから 1 回だけ送る。**
 *
 * 送るたびに `.so` は FIR を組み直す (0.3〜0.6 s)。摘みを 1 本ずつ動かすたびに組み直させると
 * 完成が先送りされ続けるので、まとめて 1 回にする。長すぎると「切り替えたのにすぐ変わらない」に
 * なるので、組み直しそのものの時間 (上記) より短く取る。
 */
private const val EQ_CURVE_DEBOUNCE_MS = 400L

/**
 * 曲線を渡すファイル。**cacheDir に固定名で置く** — root で走る `caeqset` が読める場所で、
 * 名前が固定なら `EqParams.isSafePath` を必ず通る (端末ごとに変わるのは前半の cacheDir だけ)。
 */
private const val EQ_CURVE_FILE = "eq_curve.txt"

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context = application.applicationContext
    private val store = SettingsStore(context)
    private val stored = store.load()
    private val presetStore = PresetStore(context)
    private val eqDeviceStore = EqDeviceStore(context)
    private val slotStore = SlotStore(context)

    // ⚠️ a2dpOutputs の初期化より前に置くこと (プロパティの初期化は宣言順に走る)。
    private val audioManager: AudioManager? =
        runCatching { context.getSystemService(AudioManager::class.java) }.getOrNull()

    /** `su -c` に渡す実行ファイルの置き場。プロセスの生存中に変わらない。 */
    private val nativeLibraryDir: String = context.applicationInfo.nativeLibraryDir.orEmpty()

    var presets by mutableStateOf(presetStore.load())
        private set

    /**
     * イヤホンごとの EQ スロット台帳 (`llmdocs/eq-slot-design.md`)。選択中スロットの中身は
     * profile.eq の写し (同じ値が 2 箇所) で、食い違いは起動時の和解とここ経由の write-through
     * ([updateEq]) が `reconciledWith` で直す。
     */
    var slots by mutableStateOf(loadSlotsReconciled())
        private set

    /** `audio_effects.xml` に登録済みのイヤホン。成功した登録操作でしか動かない。 */
    var eqRegisteredMacs by mutableStateOf(eqDeviceStore.load())
        private set

    /**
     * 走っている登録操作の対象。null なら走っていない。
     *
     * **1 度に 1 つだけ** — 同時に走らせると、あとから終わったほうの一覧で XML が上書きされる。
     */
    var eqRegisterRunning by mutableStateOf<String?>(null)
        private set

    /** 直近の登録操作の結果。成功も失敗も必ず出す。 */
    var eqRegisterReport by mutableStateOf<EqRegisterReport?>(null)
        private set

    /**
     * いま音の出口になっている A2DP 機器。**枠の持ち主を決める材料** (`EqRoute` の KDoc)。
     *
     * `AudioDeviceCallback` で追う。**「アプリを開く → イヤホンを着ける」は普通の順序**で、
     * そのとき ON_RESUME はもう過ぎている — 前面に来たときだけ見ていると、その回が丸ごと落ちる。
     */
    var a2dpOutputs by mutableStateOf(AudioOutputs.a2dp(audioManager))
        private set

    /**
     * 直近で値を書きに行った結果。**失敗を黙らせないための唯一の置き場。**
     *
     * 成功のときも入れる (画面側が「前の失敗が残って見える」を避けられる)。出すかどうかは画面の判断。
     */
    var eqParamsReport by mutableStateOf<EqParamsReport?>(null)
        private set

    // パラメトリックからグラフィックへ移ると fc と Q が固定値へ丸められる。一度だけ確認し、
    // 「今後表示しない」を押されたら二度と聞かない。
    var eqRoundingConfirmed by mutableStateOf(presetStore.roundingConfirmed())
        private set

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

    // 設定アプリのフックが一度でも判定を差し替えたか。開発者向けオプションを開くのはアプリの外なので、
    // 画面に戻ってきたときに読み直す。
    var settingsHooked by mutableStateOf(store.settingsHooked())
        private set

    // queryEffects() は audioserver への binder 呼び出し。Compose の再構成のたびに走らせないよう、
    // ここに持って refresh() でだけ取り直す。
    private var effectRegistered by mutableStateOf(EqSupport.effectRegistered())

    // ro.* は読み取り専用でプロセスの生存中に変わらないので 1 回だけ読む。
    private val moduleVersionCode = ModuleVersion.read(ModuleVersion.PROPERTY_CODE)

    // 表示にだけ使う。突き合わせは versionCode 側でやるので、版がずれているときにしか読まれない。
    // 読むのは getprop の exec なので、by lazy にして「揃っている / モジュールが無い」ほうの
    // 起動から外す。ro.* は変わらないので、遅らせても値は同じ。
    val moduleSemver: String by lazy { ModuleVersion.read(ModuleVersion.PROPERTY_SEMVER) }

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

    // 値の書き込みは 1 本ずつ。走っている間に来た依頼はこの旗 1 つに畳む (最後の 1 つだけが流れる)。
    // viewModelScope は Main.immediate なので、旗の読み書きは全部同じスレッドで直列に起きる。
    private var eqPushJob: Job? = null
    private var eqPushQueued = false

    // 「高精度」の曲線だけは手が止まってから送る (理由は scheduleEqCurvePush)。
    private var eqCurveJob: Job? = null

    /** 最後に書き込みが通った曲線。**一致したときだけ送らない** (詳しくは pushEqCurve)。 */
    private var eqSentCurve: SentCurve? = null

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

    val moduleVersionState: ModuleVersionState
        get() = ModuleVersion.compare(moduleVersionCode, BuildConfig.VERSION_CODE)

    // 表示の判断はここ 1 箇所。順序は「アプリから確実に分かるもの」から。
    // 版の判定だけは報告が要るので、1 通も来ていないうちは判定しない — フックが動いていないことは
    // 一覧のカードが既に出しているし、起動直後の数秒だけ「古い版です」が出て消えるのは嘘に近い。
    //
    // 未登録をオフロードより後に置くのは、オフロード中の登録が音を切るだけで何も変えないため。
    // 先に未登録を出すと、無駄な重い操作へ誘導することになる。
    fun eqAvailability(mac: String): EqAvailability = when {
        !effectRegistered -> EqAvailability.EFFECT_NOT_REGISTERED
        a2dpOffloadEnabled -> EqAvailability.OFFLOAD_ENABLED
        !eqRegistered(mac) -> EqAvailability.DEVICE_NOT_REGISTERED
        report?.let { it.eqSchema < EqSupport.SCHEMA } == true -> EqAvailability.HOOK_TOO_OLD
        else -> EqAvailability.OK
    }

    fun eqRegistered(mac: String): Boolean = EqDevices.normalizeMac(mac) in eqRegisteredMacs

    /**
     * 共有メモリの枠の持ち主。**値を書く相手はここでしか決めない。**
     *
     * `eqAvailability` とは別の軸なので混ぜない — あちらは「音響処理が使えるか」、こちらは
     * 「いまこの瞬間どの機器の音になるか」。混ぜると、繋いでいないあいだ一覧の全機器が
     * 「使えません」になる。
     */
    val eqOwner: String?
        get() = EqRoute.owner(a2dpOutputs, eqRegisteredMacs)

    fun eqDelivery(mac: String): EqDelivery = EqRoute.deliveryOf(mac, a2dpOutputs, eqRegisteredMacs)

    fun refreshAudioOutputs() {
        a2dpOutputs = AudioOutputs.a2dp(audioManager)
    }

    /**
     * 探索セッション中だけ永続設定の代わりに押す値。null なら普段どおり。
     *
     * `updateEq` を通さないのは、あちらが保存とフックへの送信まで一続きだから — 試行のたびに
     * 通すと、聴き比べの候補が本物の設定として保存されて残る。既存の押し直し (前面復帰・
     * 出口の変化・登録直後) は [eqSettingsToPush] 経由でこの値を勝手に拾うので、
     * セッション側での再送は要らない。
     */
    // private set にしないのは JVM の都合 — プロパティの setter が setEqPreview という同じ
    // JVM 名を生成して、下の関数と衝突する。
    private var eqPreviewState by mutableStateOf<EqSessionPreview?>(null)
    val eqPreview: EqSessionPreview? get() = eqPreviewState

    fun setEqPreview(p: EqSessionPreview?) {
        eqPreviewState = p
        pushEqParams()
    }

    /** 枠の持ち主 [target] へ書く設定。プレビューは相手が一致するときだけ永続設定に勝つ。 */
    internal fun eqSettingsToPush(target: String): EqSettings =
        eqPreview?.takeIf { it.mac == target }?.settings
            ?: (config.profileFor(target)?.eq ?: EqSettings())

    /**
     * 枠の持ち主の設定を共有メモリへ書く。**開いている画面とは無関係** — 共有メモリは常に
     * 「いま鳴っている機器」の設定を映す。
     *
     * **持ち主が決まらないときは何もしない。**推測で 1 つ選ぶと、片方のイヤホンにもう片方の
     * 曲線が掛かる。終了コードもログも正常なので、原因に辿り着く手掛かりが 1 つも残らない。
     *
     * ⚠️ **同時に 2 本走らせない。**`caeqset` は seqlock で書くが、プロセスをまたぐ排他は無いので
     * 2 本が同じ枠に重なると壊れる。走っている間に来た依頼は「最後の 1 つ」だけを後で流す
     * (溜めると古い値が後から着地する)。
     */
    fun pushEqParams(settingsChangedOnly: Boolean = false) {
        // 登録の実行中は audioserver が落ちていて枠が無い。終わったら setEqRegistered が押し直す。
        if (eqRegisterRunning != null) return
        if (eqOwner == null) return
        // **既定は「曲線を送り直す」側。**押し直しの理由 (前面復帰・出口の変化・登録の完了・
        // 探索セッションの出入り) はどれも共有メモリが作り直されている / 枠が移っている
        // 可能性を含む。設定を変えただけのときだけ [eqSentCurve] を信じる。
        if (!settingsChangedOnly) eqSentCurve = null
        scheduleEqCurvePush()
        if (eqPushJob?.isActive == true) {
            eqPushQueued = true
            return
        }
        eqPushJob = viewModelScope.launch {
            do {
                // 走る直前に読み直す。待っている間に持ち主が変わっていることがある。
                eqPushQueued = false
                val target = eqOwner ?: break
                // 設定を作っていない機器にも書く。共有メモリには前の機器の値が残っているので、
                // 「何もしない」は「前の曲線が掛かったまま」を意味する (params[] はインスタンスの
                // 死を越えて残る)。既定は EQ オフなので、書けば素通しに戻る。
                val settings = eqSettingsToPush(target)
                val result = withContext(Dispatchers.IO) { EqParams.apply(nativeLibraryDir, settings) }
                eqParamsReport = EqParamsReport(mac = target, result = result)
            } while (eqPushQueued)
        }
    }

    /**
     * 「高精度」の目標曲線を送り直す予約。**手が止まってから 1 回だけ。**
     *
     * 曲線を送ると `.so` は FIR を組み直す (0.3〜0.6 s、そのあいだは biquad で鳴る)。
     * 摘みを 1 本ずつ動かすたびに組み直させると完成が先送りされ続けるので、最後の操作から
     * [EQ_CURVE_DEBOUNCE_MS] 静かになるまで待つ。**bands は上の即時の押し込みで届いている**
     * ので、待っているあいだも音は摘みどおりに鳴る (biquad interim)。
     *
     * **標準のときは何もしない。**予約だけ取り消して、`--curve` を伴わない押し込みに任せる。
     */
    private fun scheduleEqCurvePush() {
        eqCurveJob?.cancel()
        val target = eqOwner ?: return
        if (!eqSettingsToPush(target).firRequested) return
        eqCurveJob = viewModelScope.launch {
            delay(EQ_CURVE_DEBOUNCE_MS)
            pushEqCurve()
        }
    }

    /**
     * 曲線を書き出して `caeqset --curve` で送る。**押し込みの本体 ([pushEqParams]) と同じ
     * 直列化に乗せる** — `caeqset` はプロセスをまたぐ排他を持たないので、2 本が同じ枠に
     * 重なると seqlock ごと壊れる。
     *
     * **前に送ったものと同じ曲線なら送らない。**送れば世代が動いて FIR が組み直され、
     * 鳴っている音が 0.3〜0.6 s のあいだ biquad に戻る。プリアンプだけを動かしたときに
     * それが起きないようにするための記憶で、**合致しなければ必ず送る側に倒す**
     * (送りすぎは組み直し 1 回で済むが、送り損ねると「高精度にしたのに変わらない」が
     * 黙って残り、次に曲線を触るまで直らない)。
     */
    private suspend fun pushEqCurve() {
        eqPushJob?.join()
        if (eqRegisterRunning != null) return
        val target = eqOwner ?: return
        val settings = eqSettingsToPush(target)
        if (!settings.firRequested) return
        val curve = EqCurveGrid.graphicCurveDb(settings.bands)
        // 非有限が混ざった曲線は直しようがない。送れば `.so` が枠の更新を丸ごと捨てて
        // bands まで消えるので、**送らずに biquad のまま鳴らす**ほうが害が小さい。
        if (!EqCurveGrid.valid(curve)) return
        val text = EqCurveGrid.encode(curve)
        if (eqSentCurve == SentCurve(target, text)) return
        val result = withContext(Dispatchers.IO) {
            val file = File(context.cacheDir, EQ_CURVE_FILE)
            runCatching { file.writeText(text) }
                .map { EqParams.apply(nativeLibraryDir, settings, file) }
                .getOrElse {
                    EqParamsResult(EqParamsOutcome.BAD_INPUT, EqParams.NO_EXIT_CODE, "", it.message.orEmpty())
                }
        }
        eqSentCurve = if (result.outcome == EqParamsOutcome.APPLIED) SentCurve(target, text) else null
        eqParamsReport = EqParamsReport(mac = target, result = result)
    }

    /**
     * このイヤホンで音響処理を使う / やめる。
     *
     * **重い操作。**`audio_effects.xml` を作り直して audioserver を再起動するので、再生中の音が
     * 一瞬切れる。UI を止めないよう別スレッドで走らせ、[EqDevices.TIMEOUT_MS] で打ち切る。
     *
     * **成功したときだけ記録を更新する。**失敗して記録を進めると、XML に無いものを「登録済み」と
     * 出すことになる。途中でプロセスが死んで記録だけ遅れた場合は、次の操作で一覧を丸ごと送り直す
     * ので直る (root 側は受け取った一覧をそのまま真とする)。
     */
    fun setEqRegistered(mac: String, registered: Boolean) {
        if (eqRegisterRunning != null) return
        val key = EqDevices.normalizeMac(mac) ?: return
        val next = EqDevices.withDevice(eqRegisteredMacs, key, registered)
        eqRegisterRunning = key
        eqRegisterReport = null
        viewModelScope.launch {
            // 作り直しのあいだ音を押さえる。**取るのは apply の前** — 後だと、押さえる前に
            // audioserver が落ちる。
            val quiet = withContext(Dispatchers.IO) {
                QuietSwitch.open(SystemQuietBackend(audioManager))
            }
            try {
                val result = withContext(Dispatchers.IO) { EqDevices.apply(next) }
                // **audioserver が戻っても、イヤホンが経路に戻るのはその後。**ここで押さえを
                // 解くと、まさに音がスピーカーへ落ちる瞬間に解くことになる。
                withContext(Dispatchers.IO) { quiet.awaitOutputRestored() }
                // **押さえは効かなくても何も出ない** — 音が漏れたことは端末の持ち主にしか
                // 分からず、ログにも画面にも痕跡が残らない。ここだけが手掛かりになるので残す。
                // sawOutputGone=false は「見に行く前に戻っていた」と「出口の一覧が
                // audioserver の生死を映していない (待ちが素通り)」の両方を意味しうる。
                // HyperOS はアプリの Log を既定で抑制する (開発者向け設定で許可しないと
                // logcat に出ない)。この行が見つからないときは、コードより先に端末を疑う。
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
                // ⚠️ **登録が変わったら必ず押し直す。**audioserver を作り直すので、生きている
                // インスタンスの顔ぶれが変わり、枠の添字が変わる。**`params[]` はインスタンスの死を
                // 越えて残る**ので、押し直さないと新しいインスタンスが**前の機器の曲線を拾う** —
                // B の登録を外した後、A が B の添字に載ると A に B のカーブが掛かったまま鳴る。
                if (result.outcome == EqDevicesOutcome.OK) {
                    refreshAudioOutputs()
                    pushEqParams()
                }
                // **押し直しが載るまで音を戻さない。**pushEqParams() は投げっぱなしなので、
                // join しないと前の機器の曲線が掛かったまま鳴る瞬間がそのまま聞こえる。
                // 押していなければ即座に返る。
                eqPushJob?.join()
            } finally {
                // キャンセルされても必ず解く。**ここで suspend を挟むと走らない** —
                // 音楽を止めたまま戻らなくなる。
                quiet.close()
            }
        }
    }

    // ボンド済みに出てこない MAC (ペアリングを解除した) も設定を残しておく。
    // 権限が無い / Bluetooth がオフのときは一覧そのものが読めないので、ボンド済みに無いことを根拠にできない。
    //
    // 登録済みの MAC も混ぜる。設定を消したうえでペアリングも解除された機器は、そうしないと
    // 画面のどこからも開けなくなり、XML に残った登録を解除する手立てが無くなる。
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

    // 保存する名前に訳文を混ぜないため、実名が無ければ空のまま返す。
    private fun rawName(mac: String, bonded: String = ""): String {
        val key = mac.uppercase()
        return bonded.ifBlank { statuses[key]?.name.orEmpty() }
            .ifBlank { bondedNames[key].orEmpty() }
            .ifBlank { config.profiles[key]?.name.orEmpty() }
    }

    fun refresh() {
        if (!settingsHooked) settingsHooked = store.settingsHooked()
        // モジュールを入れた直後は audioserver の作り直しで登録が変わる。手動の更新でだけ取り直す。
        effectRegistered = EqSupport.effectRegistered()
        refreshDevices()
        refreshAudioOutputs()
        // 前面に来たら押し直す。**端末を再起動すると共有メモリが作り直されて `generation` が 0 に
        // 戻る**ので、押し直さないと「再起動したら EQ が効かなくなった」になる。
        // 押した結果そのものが「効いているか」の答えになるので、問い合わせの経路は要らない。
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

    /**
     * 起動時 1 回の、スロット台帳の移行と和解。**写すだけ** — profile.eq には触らず
     * [updateEq] / push も通らないので、起動でスロットが音を変えることはない。
     *
     * 設定が読めないとき (configBroken) は台帳に触らない — 空の profiles と和解すると
     * 全スロットを孤児として消してしまう。
     */
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

    /**
     * スロットを選ぶ。
     *
     * ⚠️ **台帳を先に書いてから [updateEq] を呼ぶ。**逆順だと write-through が新しい曲線を
     * 「まだ選択中の古いスロット」へ写して上書きする ([EqSlotBook.reconciledWith] の KDoc)。
     * **選択を動かす操作 ([deleteSlot] / [landInNewSlot]) はすべてこの順番。**
     * `EqSlotSelectionTest.switchingSlotsLeavesTheCurveYouCameFromAlone` が見張っている。
     */
    fun selectSlot(mac: String, id: String) {
        val device = slots.of(mac)
        if (device.active == id) return
        // 知らない id には倒さない。倒すと active が宙に浮き、和解が勝手に新しいスロットを立てる。
        val target = if (id == EqSlotBook.FLAT_ID) null else device.slot(id) ?: return
        saveSlots(slots.mapDevice(mac) { it.copy(active = id) })
        applySlotCurve(mac, target?.eq)
    }

    /** 「+」。フラットを種にした新しいスロットを作って選ぶ。 */
    fun addSlot(mac: String) {
        landInNewSlot(mac, flatEq(config.profileFor(mac)?.eq ?: EqSettings(enabled = true)))
    }

    /**
     * 複製。**名前は引き継がない** (未命名 = 表示は次の「カスタム n」)。
     * 「〜のコピー」を作ると、訳文がデータに焼かれて端末の言語を替えたときに嘘になる。
     */
    fun duplicateSlot(mac: String, id: String) {
        val slot = slots.of(mac).slot(id) ?: return
        landInNewSlot(mac, slot.eq)
    }

    /** 空文字は未命名に戻す (表示は既定名へ)。名前は音に関わらないので [updateEq] は通さない。 */
    fun renameSlot(mac: String, id: String, name: String) {
        saveSlots(slots.mapDevice(mac) { it.renamed(id, name.trim()) })
    }

    /** 選択中を消したらフラットへ戻る ([DeviceSlots.without])。鳴っている音もそこへ合わせる。 */
    fun deleteSlot(mac: String, id: String) {
        val device = slots.of(mac)
        if (device.slot(id) == null) return
        val wasActive = device.active == id
        saveSlots(slots.mapDevice(mac) { it.without(id) })
        if (wasActive) applySlotCurve(mac, null)
    }

    /**
     * 外から来た曲線を**新しいスロットに着地**させて選ぶ。プリセットの適用・AutoEQ の取り込み・
     * 好み探索の結果・「+」が通る唯一の道 (`llmdocs/eq-slot-design.md` §1「既存スロットを
     * 黙って上書きする経路を作らない」)。
     *
     * [name] を渡してよいのは**ユーザが付けた名前**だけ (プリセット名)。既定名は表示側で作る —
     * 「自動 1」のような訳文をデータに焼くと、端末の言語を替えたときにデータが嘘になる。
     */
    fun landInNewSlot(mac: String, eq: EqSettings, name: String = "") {
        val curve = slotCurve(eq)
        saveSlots(slots.mapDevice(mac) { it.withNewSlot(curve, name) })
        updateEq(mac) { curve }
    }

    /** [eq] が null ならフラット。台帳を書き終えた後にだけ呼ぶこと (上の ⚠️)。 */
    private fun applySlotCurve(mac: String, eq: EqSettings?) {
        updateEq(mac) { current -> eq?.let(::slotCurve) ?: flatEq(current) }
    }

    /**
     * スロットの中身として扱ってよい形にする。**主電源は必ず入れる** — オフとスロットは別の層
     * (仕様 §1) なので、スロットを選んだだけでイコライザーが切れてはいけない。
     * `enabled = false` のプリセットを読み込んだときにだけ効く。
     */
    private fun slotCurve(eq: EqSettings): EqSettings =
        if (eq.enabled) eq else eq.copy(enabled = true)

    /**
     * EQ の設定を変える。**入口はここ 1 つ** (スライダー・プリセット・AutoEQ の取り込みが全部通る)。
     *
     * ここで共有メモリへ書く。**ドラッグ中は呼ばれない** — スライダーは `onValueChangeFinished` で
     * しか確定しないので、値が確定したときだけ `su` が走る。
     *
     * **書くのは [mac] がいまの枠の持ち主のときだけ。**繋がっていない機器の設定を書くと、
     * 鳴っているほうの機器にその曲線が掛かる。**`--off` も同じ門を通す** — オフは無害に見えるが、
     * 通すと鳴っている別のイヤホンの EQ を消す。
     */
    fun updateEq(mac: String, transform: (EqSettings) -> EqSettings) {
        updateProfile(mac) { it.copy(eq = transform(it.eq)) }
        // 選択中スロットへの write-through。二重保持 (選択中スロットの中身 = profile.eq) の
        // 食い違いを変更の入口で潰す。configBroken のときは profileFor が空の config を見るので走らない。
        config.profileFor(mac)?.let { saveSlots(slots.reconciledWith(it.mac, it.eq)) }
        if (EqDevices.normalizeMac(mac) == eqOwner) pushEqParams(settingsChangedOnly = true)
    }

    // プリセットは設定とは別のファイルに持つ。同じ名前で保存し直したら差し替える。
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

    /**
     * プリセットの「適用」= **新しいスロットとして読み込む。**選択中のスロットは残るので、
     * 試した後に元の曲線へ 1 タップで戻れる。
     *
     * 名前はプリセットの名前をそのまま引き継ぐ — ユーザが付けた名前なので、
     * 「既定名をデータに焼かない」規則には触れない。
     */
    fun applyPreset(mac: String, name: String) {
        val preset = presets.presets.firstOrNull { it.name == name } ?: return
        landInNewSlot(mac, preset.settings, preset.name)
    }

    fun confirmEqRounding() {
        presetStore.confirmRounding()
        eqRoundingConfirmed = true
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

    fun removeProfile(mac: String) {
        update { it.withoutProfile(mac) }
        // スロットの孤児防止。プロファイルと一緒にその機器の台帳も消す。
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
            // 復元で profile.eq が丸ごと入れ替わるので、起動時と同じ和解をここでも通す
            // (写す向きも同じ: profile.eq → 選択中スロット)。
            saveSlots(slots.reconciled(restored.profiles))
            refreshDevices()
            pendingMessage = R.string.msg_backup_imported
        }
    }

    // 既存の exportConfig は openOutputStream(uri, "wt") を使っているが、"wt" は
    // provider が実装していないことがある (PLAN.md の UI-18)。CreateDocument は必ず
    // 新規の空ファイルを作るので、新しい経路では切り詰めの要らない "w" を使う。
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
            // GraphicEQ 形式は曲線全体へのフィットを回すので main スレッドでは重い
            val result = withContext(Dispatchers.Default) { AutoEqParser.parse(text, bandCount) }
            when (result) {
                is AutoEqResult.Ok -> {
                    // 取り込んだ曲線も新しいスロットに着地する (既存の作りかけを潰さない)。
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

    /**
     * 出口が変わったら押し直す。**前面に来たときだけでは足りない** — 「アプリを開く →
     * イヤホンを着ける」は普通の順序で、そのとき ON_RESUME はもう過ぎている。
     *
     * **断ったときの回収経路でもある。**繋がっていない機器を編集していて書かなかった分は、
     * その機器を繋いだこのコールバックで初めて届く。**断りと回収は 1 対**で、片方だけだと
     * 「設定したのに効かない」に戻る。
     *
     * 常駐は増えない (アプリのプロセスが生きている間だけの登録)。
     */
    private val audioDevices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = onOutputsChanged()

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = onOutputsChanged()
    }

    private fun onOutputsChanged() {
        refreshAudioOutputs()
        pushEqParams()
    }

    // ⚠️ 上のプロパティが全部そろってから登録する。registerAudioDeviceCallback は登録した時点の
    // 一覧でコールバックを 1 回呼ぶので、宣言順を入れ替えると初期化前の値を読む。
    init {
        runCatching { audioManager?.registerAudioDeviceCallback(audioDevices, null) }
    }

    // super.onCleared() は呼ばない。@EmptySuper が付いていて lint が落とす。
    override fun onCleared() {
        runCatching { audioManager?.unregisterAudioDeviceCallback(audioDevices) }
    }

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
