package io.github.mame1839.codecanchor.ui

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import io.github.mame1839.codecanchor.R
import io.github.mame1839.codecanchor.audio.Loaded
import io.github.mame1839.codecanchor.audio.LoopPlayer
import io.github.mame1839.codecanchor.audio.LoopSource
import io.github.mame1839.codecanchor.bridge.EqFinderSaved
import io.github.mame1839.codecanchor.bridge.EqFinderStore
import io.github.mame1839.codecanchor.core.EqAvailability
import io.github.mame1839.codecanchor.core.EqBand
import io.github.mame1839.codecanchor.core.EqBandType
import io.github.mame1839.codecanchor.core.EqDevices
import io.github.mame1839.codecanchor.core.EqFinderAxes
import io.github.mame1839.codecanchor.core.EqFinderAxis
import io.github.mame1839.codecanchor.core.EqFinderMaterialize
import io.github.mame1839.codecanchor.core.EqFinderSession
import io.github.mame1839.codecanchor.core.EqLoudness
import io.github.mame1839.codecanchor.core.EqSettings
import io.github.mame1839.codecanchor.core.Spectrum
import androidx.compose.material3.SnackbarHostState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** 探索の進行段階。導入 → 試行 → 結果の一方向で、戻るのは画面を閉じるときだけ。 */
enum class EqFinderPhase { INTRO, TRIAL, RESULT }

/**
 * セッション進行の接着。画面 (EqFinderScreen.kt の content) は表示するだけで、
 * ここがエンジン (EqFinderSession)・音 (LoopPlayer)・押し込み (MainViewModel.setEqPreview)・
 * 保存 (EqFinderStore) をつなぐ。
 *
 * ### 守っている境界
 *
 * - **音の書き込みは [MainViewModel.setEqPreview] だけ。**独自のコルーチンから
 *   `EqParams.apply` を呼ばない (single-flight と「同じ枠に 2 本書かない」規約は vm 側が持つ)。
 *   トグルの体感は su 一発 (実測 median 109 ms) なので、人工的な待ちやデバウンスも置かない
 * - **セッション中は `updateEq` を通さない** (あれは永続化する)。永続化は確定 (apply) の 1 回だけ
 * - **聴感の重みとセッション共通トリムはセッション開始時に 1 回決めて固定** (eq-finder-design.md §1)。
 *   途中で計算し直すと候補間の相対音量が崩れ、音量バイアスが戻ってくる
 * - 進行状態は回答のたびに保存する。中断・プロセス死のどちらでも「続きから」が立つ
 */
class EqFinderController(
    private val context: Context,
    private val vm: MainViewModel,
    /** 正規化済み (`EqDevices.normalizeMac`) の対象イヤホン。 */
    val mac: String,
    private val scope: CoroutineScope,
) {
    private val store = EqFinderStore(context)
    private val player = LoopPlayer(
        runCatching { context.getSystemService(AudioManager::class.java) }.getOrNull(),
    ).also { p ->
        // 呼ばれるスレッドはシステム任せなので、状態はメインへ運んでから触る。
        p.onFocusLost = { scope.launch { onFocusLost() } }
    }

    var phase by mutableStateOf(EqFinderPhase.INTRO)
        private set

    // ---- 導入 ----

    var songName by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var loadFailed by mutableStateOf(false)
        private set
    var songLoaded by mutableStateOf(false)
        private set
    var trackDurationMs by mutableIntStateOf(0)
        private set
    // 3 つとも公開の var — 明示の setter を並べると、プロパティが生成する JVM の setter と
    // 名前が衝突する (eqPreviewState と同じ罠)。書くのは画面のコールバックとこの中だけ。
    var startMs by mutableIntStateOf(0)
    var includeMid by mutableStateOf(false)
    var fineTune by mutableStateOf(false)
    var previewPlaying by mutableStateOf(false)
        private set

    /** この機器の中断セッション。null なら新規の導入を出す。 */
    var saved by mutableStateOf(store.load()?.takeIf { it.mac == mac })
        private set

    /** 保存した一節が同じ形で開けなかった (消えた・差し替わった)。再開は遮断する。 */
    private var resumeSongBlocked by mutableStateOf(false)

    /**
     * 「続きから」を遮断する理由。null なら再開できる。
     *
     * 設定の照合を保存値との**完全一致**にするのは、候補もオーバーレイも保存時の base の
     * 上に組んであるため — base がずれたまま再開すると、探索は古い土台で進むのに確定は
     * その古い土台で現行設定を上書きし、中断のあいだの編集が黙って消える。
     */
    fun resumeBlocked(): EqFinderResumeBlocked? {
        val record = saved ?: return null
        return when {
            resumeSongBlocked -> EqFinderResumeBlocked.SONG_CHANGED
            (vm.config.profileFor(mac)?.eq ?: EqSettings()) != record.base ->
                EqFinderResumeBlocked.SETTINGS_CHANGED

            else -> null
        }
    }

    private var songUri: Uri? = saved?.uri?.let { runCatching { it.toUri() }.getOrNull() }
    private var loaded: Loaded? = null

    // ---- セッションの固定値 (開始時に決めて以後変えない) ----

    private var session: EqFinderSession? = null
    private var axes: List<EqFinderAxis> = emptyList()
    private var base: EqSettings = EqSettings()
    private var baseBands: List<EqBand> = emptyList()
    private var weights: DoubleArray = DoubleArray(0)
    private var trimDb = 0.0
    private var pcmHash = 0L

    // ---- 試行 ----

    var selected by mutableStateOf(EqFinderCandidate.A)
        private set
    var aHeard by mutableStateOf(false)
        private set
    var bHeard by mutableStateOf(false)
        private set
    var pause by mutableStateOf(EqFinderPause.NONE)
        private set
    var done by mutableIntStateOf(0)
        private set
    var total by mutableIntStateOf(0)
        private set

    // ---- 結果 ----

    var result by mutableStateOf<EqFinderResultUi?>(null)
        private set
    private var overlay: List<Int> = emptyList()
    private var appliedSettings: EqSettings? = null

    // ------------------------------------------------------------------
    // 導入
    // ------------------------------------------------------------------

    /**
     * 曲が選ばれた。permission は取り直しの効かない一度きりなのでここで永続化する
     * (中断 → 再起動 → 再開で同じ URI を開くため)。
     */
    fun pickSong(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        songUri = uri
        startMs = 0
        loadClip(0) { clip ->
            songName = displayNameOf(uri)
            trackDurationMs = clip.trackDurationMs.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        }
    }

    /** スライダーを離した。試聴中ならその位置で読み直して鳴らし続ける。 */
    fun startMsChosen() {
        if (saved != null || songUri == null) return
        val wasPlaying = previewPlaying
        loadClip(startMs) {
            if (wasPlaying) startPlayer { previewPlaying = it }
        }
    }

    fun previewToggle() {
        if (previewPlaying) {
            player.stop()
            previewPlaying = false
            return
        }
        if (loaded == null) return
        startPlayer { previewPlaying = it }
    }

    /** 微調整モードを出すか。現行 EQ が鳴っているときだけ意味を持つ (仕様: 中断データが無いこと)。 */
    fun fineTuneVisible(): Boolean {
        if (saved != null) return false
        val eq = vm.config.profileFor(mac)?.eq ?: return false
        return eq.enabled && eq.bands.isNotEmpty()
    }

    private fun loadClip(atMs: Int, onLoaded: (Loaded) -> Unit = {}) {
        val uri = songUri ?: return
        scope.launch {
            loading = true
            loadFailed = false
            player.stop()
            previewPlaying = false
            val clip = withContext(Dispatchers.IO) {
                LoopSource.load(context, uri, atMs.toLong(), EQ_FINDER_LOOP_MS.toLong())
            }.getOrNull()
            loading = false
            if (clip == null) {
                loaded = null
                songLoaded = false
                loadFailed = true
                return@launch
            }
            loaded = clip
            songLoaded = true
            onLoaded(clip)
        }
    }

    private fun displayNameOf(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty().ifBlank { "-" }

    // ------------------------------------------------------------------
    // セッションの開始と再開
    // ------------------------------------------------------------------

    /** 新規に始める。重み・トリム・開始点をここで固定する。 */
    fun begin() {
        val clip = loaded ?: return
        if (session != null || loading) return
        val current = vm.config.profileFor(mac)?.eq ?: EqSettings()
        scope.launch {
            loading = true
            base = current
            // EQ を切っている人の基準は「素の音」。バンドを重ねる土台も空にする。
            baseBands = if (current.enabled) current.bands else emptyList()
            axes = EqFinderAxes.default(includeMid)
            val bands = baseBands
            val ax = axes
            val prep = withContext(Dispatchers.Default) {
                val w = weightsOf(clip)
                val corners = eqFinderCornerOverlays(ax)
                    .map { EqFinderMaterialize.candidateBands(bands, ax, it) }
                w to EqLoudness.sessionTrimDb(corners, w)
            }
            weights = prep.first
            trimDb = prep.second
            pcmHash = pcmContentHash(clip.pcm)
            session = EqFinderSession.start(
                axes = ax,
                seed = Random.nextLong(),
                startStepDb10 = if (fineTune && fineTuneVisible()) FINE_STEP_DB10 else START_STEP_DB10,
            )
            persist()
            loading = false
            phase = EqFinderPhase.TRIAL
            beginTrial()
            startPlayer { if (!it) pause = EqFinderPause.FOCUS_LOST }
        }
    }

    /**
     * 中断からの再開。曲は保存時と同じ一節を読み直す。開けない・中身が違う・設定が変わった —
     * どれか 1 つでも当てはまれば再開しない ([resumeBlocked])。
     */
    fun resume() {
        val record = saved ?: return
        val uri = songUri ?: return
        if (loading || session != null || resumeBlocked() != null) return
        scope.launch {
            loading = true
            loadFailed = false
            val clip = withContext(Dispatchers.IO) {
                LoopSource.load(context, uri, record.startMs.toLong(), record.lengthMs.toLong())
            }.getOrNull()
            if (clip == null) {
                loading = false
                resumeSongBlocked = true
                return@launch
            }
            val hash = pcmContentHash(clip.pcm)
            if (record.pcmHash != 0L && hash != record.pcmHash) {
                // 別の音になっている。これまでの回答は前の音についてのものなので続けない。
                loading = false
                resumeSongBlocked = true
                return@launch
            }
            val restored = EqFinderSession.fromJson(record.session)
            if (restored == null) {
                // 読めない記録は再開できない。壊れたまま出し続けるより、新規の導入に戻す。
                store.clear()
                saved = null
                loading = false
                return@launch
            }
            loaded = clip
            songLoaded = true
            base = record.base
            baseBands = if (base.enabled) base.bands else emptyList()
            includeMid = record.includeMid
            fineTune = record.fineTune
            startMs = record.startMs
            axes = EqFinderAxes.default(record.includeMid)
            pcmHash = hash
            val bands = baseBands
            val ax = axes
            val prep = withContext(Dispatchers.Default) {
                // 重みとトリムは保存していない — 同じ一節から決定的に同じ値が出る
                // (同じであることはハッシュで確かめた後)。
                val w = weightsOf(clip)
                val corners = eqFinderCornerOverlays(ax)
                    .map { EqFinderMaterialize.candidateBands(bands, ax, it) }
                w to EqLoudness.sessionTrimDb(corners, w)
            }
            weights = prep.first
            trimDb = prep.second
            session = restored
            loading = false
            phase = EqFinderPhase.TRIAL
            beginTrial()
            startPlayer { if (!it) pause = EqFinderPause.FOCUS_LOST }
        }
    }

    /** 保存済みセッションを消して新規の導入に戻す (確認は画面側で済んでいる)。 */
    fun startOver() {
        store.clear()
        saved = null
        resumeSongBlocked = false
        songUri = null
        songName = null
        loaded = null
        songLoaded = false
        trackDurationMs = 0
        startMs = 0
    }

    /**
     * 一節の実測スペクトルから聴感の重みを作る。窓 1 枚も置けない短さでは空が返るので、
     * そのまま resample すると全 0 dB (= 白色相当) に化ける — 既定の重みへ退避する。
     */
    private fun weightsOf(clip: Loaded): DoubleArray {
        val (hz, db) = Spectrum.averageSpectrumDb(clip.monoMix(), clip.sampleRate)
        if (hz.isEmpty()) return EqLoudness.defaultWeights()
        return EqLoudness.weightsFromSpectrumDb(Spectrum.resampleDb(hz, db, EqLoudness.GRID_HZ))
    }

    // ------------------------------------------------------------------
    // 試行
    // ------------------------------------------------------------------

    private fun beginTrial() {
        val s = session ?: return
        val trial = s.currentTrial()
        if (trial == null || s.finished) {
            showResult()
            return
        }
        val (answered, estimate) = s.progress()
        done = answered
        total = estimate
        // A を自動で鳴らす — 鳴っている音とラベルを常に一致させる (無音のまま答えさせない)。
        selected = EqFinderCandidate.A
        aHeard = true
        bHeard = false
        pushOverlay(trial.aOverlayDb10)
    }

    /** A/B カードのタップ = その候補を即プッシュ。何度でも。 */
    fun listen(candidate: EqFinderCandidate) {
        if (pause != EqFinderPause.NONE) return
        val trial = session?.currentTrial() ?: return
        selected = candidate
        when (candidate) {
            EqFinderCandidate.A -> {
                aHeard = true
                pushOverlay(trial.aOverlayDb10)
            }

            EqFinderCandidate.B -> {
                bHeard = true
                pushOverlay(trial.bOverlayDb10)
            }
        }
    }

    fun answer(answer: EqFinderAnswer) {
        val s = session ?: return
        if (pause != EqFinderPause.NONE || !(aHeard && bHeard)) return
        session = s.answered(
            when (answer) {
                EqFinderAnswer.A -> EqFinderSession.Answer.A
                EqFinderAnswer.SAME -> EqFinderSession.Answer.SAME
                EqFinderAnswer.B -> EqFinderSession.Answer.B
            },
        )
        persist()
        beginTrial()
    }

    /** 「ここで確定」。今の推定で結果画面へ (検証段まで行っていなければ、その旨は結果に出る)。 */
    fun finishNow() {
        if (done > 0) showResult()
    }

    private fun showResult() {
        val s = session ?: return
        val r = s.result()
        overlay = r.overlayDb10
        val afterBands = EqFinderMaterialize.candidateBands(baseBands, axes, overlay)
        // 結果の音を鳴らしたまま見せる (前後の聴き比べは確定前の最後の判断材料)。
        pushBands(afterBands)
        result = EqFinderResultUi(
            axes = axes.mapIndexed { i, axis ->
                EqFinderAxisDelta(axisKind(axis), overlay.getOrElse(i) { 0 })
            },
            beforeDb = eqFinderResponseDb(baseBands),
            afterDb = eqFinderResponseDb(afterBands),
            consistencyWarning = r.consistencyWarning,
            startBeaten = r.startBeatenInValidation,
        )
        phase = EqFinderPhase.RESULT
    }

    // ------------------------------------------------------------------
    // 結果
    // ------------------------------------------------------------------

    /** 適用して保存。成功したらプリセット保存の提案に切り替わる。 */
    fun apply() {
        val r = result ?: return
        val baked = EqFinderMaterialize.bake(base, axes, overlay)
        if (baked == null) {
            result = EqFinderResultUi(
                axes = r.axes,
                beforeDb = r.beforeDb,
                afterDb = r.afterDb,
                consistencyWarning = r.consistencyWarning,
                startBeaten = r.startBeaten,
                bakeFailed = true,
            )
            return
        }
        appliedSettings = baked
        // 永続化はここ 1 回だけ。押し込みは updateEq → pushEqParams が担い、直後の
        // setEqPreview(null) で最後の押しが「プレビュー無し + 新しい永続値」になる。
        vm.updateEq(mac) { baked }
        vm.setEqPreview(null)
        store.clear()
        result = EqFinderResultUi(
            axes = r.axes,
            beforeDb = r.beforeDb,
            afterDb = r.afterDb,
            consistencyWarning = r.consistencyWarning,
            startBeaten = r.startBeaten,
            presetOffer = true,
        )
    }

    /** 破棄。永続設定の音へ戻す (画面側が確認済み)。 */
    fun discard() {
        store.clear()
        vm.setEqPreview(null)
    }

    fun savePreset(name: String) {
        appliedSettings?.let { vm.savePreset(name, it) }
    }

    // ------------------------------------------------------------------
    // 一時停止と後始末
    // ------------------------------------------------------------------

    /** 枠の持ち主の変化。切れたら止める (スピーカーへ漏らさない)。戻ったら音だけ戻す。 */
    fun onConnectionChanged(connected: Boolean) {
        if (!connected) {
            if (previewPlaying) {
                player.stop()
                previewPlaying = false
            }
            if (phase != EqFinderPhase.INTRO) player.stop()
            if (phase == EqFinderPhase.TRIAL && pause == EqFinderPause.NONE) {
                pause = EqFinderPause.DISCONNECTED
            }
            return
        }
        // 候補の押し直しは vm の出口変化の再押し込み (eqPreview を拾う) が担う。音だけ戻す。
        if (phase == EqFinderPhase.TRIAL && pause == EqFinderPause.DISCONNECTED) {
            if (player.play()) pause = EqFinderPause.NONE
        }
    }

    /** AudioFocus を失った。プレイヤーは自分で止まっているので、状態だけ追いつかせる。 */
    private fun onFocusLost() {
        previewPlaying = false
        if (phase == EqFinderPhase.TRIAL && pause == EqFinderPause.NONE) {
            pause = EqFinderPause.FOCUS_LOST
        }
    }

    /** 一時停止 (フォーカス喪失) からの再開ボタン。 */
    fun resumePlayback() {
        if (player.play()) pause = EqFinderPause.NONE
    }

    /** 画面を離れるときに必ず呼ぶ。**終了経路はすべて setEqPreview(null) を通す。** */
    fun dispose() {
        player.release()
        vm.setEqPreview(null)
    }

    // ------------------------------------------------------------------

    private fun startPlayer(onResult: (Boolean) -> Unit) {
        val clip = loaded ?: return onResult(false)
        val ok = player.prepare(clip.pcm, clip.channels, clip.sampleRate) && player.play()
        onResult(ok)
    }

    private fun pushOverlay(overlayDb10: List<Int>) {
        pushBands(EqFinderMaterialize.candidateBands(baseBands, axes, overlayDb10))
    }

    private fun pushBands(bands: List<EqBand>) {
        vm.setEqPreview(EqSessionPreview(mac, eqFinderCandidateSettings(base, bands, weights, trimDb)))
    }

    private fun persist() {
        val s = session ?: return
        val uri = songUri?.toString() ?: return
        store.save(
            EqFinderSaved(
                mac = mac,
                uri = uri,
                startMs = startMs,
                lengthMs = EQ_FINDER_LOOP_MS,
                includeMid = includeMid,
                fineTune = fineTune,
                done = s.progress().first,
                pcmHash = pcmHash,
                base = base,
                session = s.toJson(),
                savedAt = System.currentTimeMillis(),
            ),
        )
    }

    private companion object {
        /** 縮小ステップの初期値 (0.1 dB 単位)。仕様 §2: 4 dB から半減で 0.5 dB まで。 */
        const val START_STEP_DB10 = 40

        /** 微調整モードの初期ステップ。現在値の近傍だけを細かく探す。 */
        const val FINE_STEP_DB10 = 10
    }
}

/**
 * 押し込む候補の設定。**プリアンプは聴感等価** (音量で選ばせない — この機能の成立条件) で、
 * 自動プリアンプ (ピーク基準) はセッション中だけこの値で上書きする。
 * mode / bandCount は元の値のまま — 変えると hash の往復とグラフィックの解き直しに波及する。
 */
internal fun eqFinderCandidateSettings(
    base: EqSettings,
    bands: List<EqBand>,
    weights: DoubleArray,
    trimDb: Double,
): EqSettings = base.copy(
    enabled = true,
    bands = bands,
    preampAuto = false,
    preampDb10 = EqLoudness.preampDb10(bands, weights, trimDb),
)

/** 軸の見た目の区分。周波数ではなく種別で決める (定義の周波数が動いても表示は正しいまま)。 */
internal fun axisKind(axis: EqFinderAxis): EqFinderAxisKind = when (axis.type) {
    EqBandType.LOW_SHELF -> EqFinderAxisKind.BASS
    EqBandType.HIGH_SHELF -> EqFinderAxisKind.TREBLE
    else -> EqFinderAxisKind.MID
}

/**
 * セッション共通トリムに使う「端」の候補群 — 各軸 min/max の全組合せ + 全 0。
 * 最悪候補 (ピーク − 聴感が最大のもの) は端に出るので、この集合で足りる。
 */
internal fun eqFinderCornerOverlays(axes: List<EqFinderAxis>): List<List<Int>> {
    var combos = listOf(emptyList<Int>())
    for (axis in axes) {
        combos = combos.flatMap { listOf(it + axis.minDb10, it + axis.maxDb10) }
    }
    return combos + listOf(axes.map { 0 })
}

/**
 * 一節の中身の指紋 (FNV-1a を float のビット列に畳んだもの)。暗号ではなく、
 * 「保存したときと同じ音か」を照合するためだけの値。デコーダが同じなら同じファイルで一致する。
 */
internal fun pcmContentHash(pcm: FloatArray): Long {
    var h = -0x340d631b7bdddcdbL // FNV offset basis (0xcbf29ce484222325)
    for (f in pcm) {
        h = (h xor f.toRawBits().toLong()) * 0x100000001b3L
    }
    return h
}

/**
 * 好みの EQ を探す画面。遷移の状態 (開いているか) は `MainActivity` が持ち、
 * ここは開かれている間だけ組まれる (`BackHandler` を 1 つに保つ規約)。
 */
@Composable
fun EqFinderScreen(
    vm: MainViewModel,
    mac: String,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
) {
    val profile = vm.config.profileFor(mac)
    // 機器の設定が消えたら閉じる (EqScreen と同じ形)。
    if (profile == null) {
        LaunchedEffect(mac) { onBack() }
        return
    }

    val appContext = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val key = remember(mac) { EqDevices.normalizeMac(mac) ?: mac.uppercase() }
    val controller = remember { EqFinderController(appContext, vm, key, scope) }
    DisposableEffect(controller) {
        onDispose { controller.dispose() }
    }

    val owner = vm.eqOwner
    LaunchedEffect(owner) { controller.onConnectionChanged(owner == key) }

    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) controller.pickSong(uri)
    }

    EqFinderScaffold(
        deviceName = vm.nameOf(mac),
        snackbarHostState = snackbarHostState,
        onBack = onBack,
    ) {
        when (controller.phase) {
            EqFinderPhase.INTRO -> {
                val blocked = when {
                    vm.eqAvailability(key) != EqAvailability.OK ->
                        stringResource(R.string.eq_finder_entry_unavailable)

                    owner != key -> stringResource(R.string.eq_finder_entry_disconnected)
                    else -> null
                }
                EqFinderIntroContent(
                    ui = EqFinderIntroUi(
                        songName = controller.songName,
                        loading = controller.loading,
                        loadFailed = controller.loadFailed,
                        loaded = controller.songLoaded,
                        trackDurationMs = controller.trackDurationMs,
                        startMs = controller.startMs,
                        previewPlaying = controller.previewPlaying,
                        includeMid = controller.includeMid,
                        fineTuneVisible = controller.fineTuneVisible(),
                        fineTune = controller.fineTune,
                        startBlockedReason = blocked,
                        resume = controller.saved?.let {
                            EqFinderResumeUi(done = it.done, blocked = controller.resumeBlocked())
                        },
                    ),
                    onPickSong = { pickLauncher.launch(arrayOf("audio/*")) },
                    onStartMs = { controller.startMs = it },
                    onStartMsChosen = controller::startMsChosen,
                    onPreviewToggle = controller::previewToggle,
                    onIncludeMid = { controller.includeMid = it },
                    onFineTune = { controller.fineTune = it },
                    onBegin = controller::begin,
                    onResume = controller::resume,
                    onStartOver = controller::startOver,
                )
            }

            EqFinderPhase.TRIAL -> EqFinderTrialContent(
                ui = EqFinderTrialUi(
                    done = controller.done,
                    total = controller.total,
                    selected = controller.selected,
                    bothHeard = controller.aHeard && controller.bHeard,
                    pause = controller.pause,
                ),
                onListen = controller::listen,
                onAnswer = controller::answer,
                onFinishNow = controller::finishNow,
                onSuspend = onBack,
                onResumePlayback = controller::resumePlayback,
            )

            EqFinderPhase.RESULT -> controller.result?.let { ui ->
                EqFinderResultContent(
                    ui = ui,
                    onApply = controller::apply,
                    onDiscard = {
                        controller.discard()
                        onBack()
                    },
                    onSavePreset = { name ->
                        controller.savePreset(name)
                        onBack()
                    },
                    onDismissPresetOffer = onBack,
                )
            }
        }
    }
}
