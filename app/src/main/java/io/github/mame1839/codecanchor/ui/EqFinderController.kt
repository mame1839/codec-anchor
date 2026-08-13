package io.github.mame1839.codecanchor.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
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
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import io.github.mame1839.codecanchor.R
import io.github.mame1839.codecanchor.audio.Loaded
import io.github.mame1839.codecanchor.audio.LoopPlayer
import io.github.mame1839.codecanchor.audio.LoopSource
import io.github.mame1839.codecanchor.audio.MusicPlaybackMonitor
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
import io.github.mame1839.codecanchor.core.EqMode
import io.github.mame1839.codecanchor.core.EqPrecision
import io.github.mame1839.codecanchor.core.EqSettings
import io.github.mame1839.codecanchor.core.EqUnits
import io.github.mame1839.codecanchor.core.Spectrum
import androidx.compose.material3.SnackbarHostState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
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
    /** 音楽の検知。注入なのは Robolectric で raw の遷移を直接振って全遷移を検査するため。 */
    private val music: MusicPlaybackMonitor = MusicPlaybackMonitor(
        runCatching { context.getSystemService(AudioManager::class.java) }.getOrNull(),
        Handler(Looper.getMainLooper()),
    ),
    /** 重い計算 (重み・トリム・焼き込み計画) の走り先。テストは Unconfined を入れて同期に落とす。 */
    private val compute: CoroutineDispatcher = Dispatchers.Default,
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

    /**
     * 題材: false = 曲の一節のループ再生 (既定)、true = いま流れている音楽。
     * 中断データがあるあいだは記録の値が真 (導入は選択肢を出さない)。
     */
    var materialLive by mutableStateOf(saved?.live == true)
        private set

    /** 音楽 (USAGE_MEDIA) が鳴っているか。ライブ題材の門と一時停止の材料 (デバウンス済み)。 */
    var musicActive by mutableStateOf(false)
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

    /** 焼き込むバンド数 (グラフィックのみ意味を持つ)。既定は結果画面に入った時点の bandCount。 */
    var bakeBandCount by mutableIntStateOf(10)
        private set

    // 適用の途中で変わる状態。**[EqFinderResultUi] には入れない** — あちらに混ぜると
    // 旗 1 つのために全フィールドを写す形になり、写し忘れが静かに壊す (KDoc 参照)。
    var bakeFailed by mutableStateOf(false)
        private set

    private var overlay: List<Int> = emptyList()

    /**
     * 結果画面に入るときに全選択肢分を一度に作るキャッシュ。副題の忠実度と [apply] が保存する
     * 実体を**同じ計算から**出す (「同じ値が 2 箇所」の予防)。値 null = その形には焼けない
     * (パラメトリック満杯)。
     */
    private var bakePlan: Map<Int, EqFinderBaked?> = emptyMap()

    init {
        // 検知は画面が開いているあいだ常時。読む相手は handler (main) のスレッドに束ねてある。
        music.onChange = { onMusicChanged(it) }
        music.start()
        musicActive = music.active
    }

    // ------------------------------------------------------------------
    // 導入
    // ------------------------------------------------------------------

    /**
     * 題材の切り替え (導入でだけ効く)。ループ用の試聴中にライブへ移ったら音を止める —
     * 鳴らし続けると AudioFocus を握ったままになり、ユーザの音楽アプリが再生を始められない。
     */
    fun chooseMaterial(live: Boolean) {
        if (materialLive == live) return
        materialLive = live
        if (live && previewPlaying) {
            player.stop()
            previewPlaying = false
        }
    }

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
        // ライブ題材に一節は無い (再生はユーザのアプリのまま。AudioFocus も取らない)。
        val clip = if (materialLive) null else (loaded ?: return)
        if (session != null || loading) return
        // 画面の門 (startBlockedReason) とは別の実行時ガード — タップの直後に切断される
        // レースがあり、ここを抜けるとスピーカーへ向けてセッションが始まる。
        if (vm.eqOwner != mac) return
        // 同じくタップ直後に音楽が止まるレース。
        if (materialLive && !music.active) return
        val current = vm.config.profileFor(mac)?.eq ?: EqSettings()
        scope.launch {
            loading = true
            base = current
            // EQ を切っている人の基準は「素の音」。バンドを重ねる土台も空にする。
            baseBands = if (current.enabled) current.bands else emptyList()
            axes = EqFinderAxes.default(includeMid)
            prepareLoudness(clip)
            pcmHash = clip?.let { pcmContentHash(it.pcm) } ?: 0L
            session = EqFinderSession.start(
                axes = axes,
                seed = Random.nextLong(),
                startStepDb10 = if (fineTune && fineTuneVisible()) FINE_STEP_DB10 else START_STEP_DB10,
            )
            persist()
            loading = false
            phase = EqFinderPhase.TRIAL
            beginTrial()
            if (clip != null) startPlayer { if (!it) pause = EqFinderPause.FOCUS_LOST }
        }
    }

    /**
     * 中断からの再開。ループなら曲を保存時と同じ一節で読み直す。開けない・中身が違う・
     * 設定が変わった — どれか 1 つでも当てはまれば再開しない ([resumeBlocked])。
     * ライブは一節を持たないので読み直しも照合も無い (SONG_CHANGED はライブでは出ない)。
     */
    fun resume() {
        val record = saved ?: return
        val uri = if (record.live) null else (songUri ?: return)
        if (loading || session != null || resumeBlocked() != null) return
        // begin() と同じレースのガード (タップ直後の切断・音楽の停止)。
        if (vm.eqOwner != mac) return
        if (record.live && !music.active) return
        scope.launch {
            loading = true
            loadFailed = false
            val clip = uri?.let {
                withContext(Dispatchers.IO) {
                    LoopSource.load(context, it, record.startMs.toLong(), record.lengthMs.toLong())
                }.getOrNull()
            }
            if (!record.live) {
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
                pcmHash = hash
            } else {
                pcmHash = 0L
            }
            val restored = eqFinderRestore(record)
            if (restored == null) {
                // 読めない記録は再開できない。壊れたまま出し続けるより、新規の導入に戻す。
                store.clear()
                saved = null
                loading = false
                return@launch
            }
            loaded = clip
            songLoaded = clip != null
            base = restored.base
            baseBands = restored.baseBands
            includeMid = record.includeMid // 表示用。軸の真実はセッション側 (eqFinderRestore)
            fineTune = record.fineTune
            startMs = record.startMs
            materialLive = record.live
            axes = restored.axes
            prepareLoudness(clip)
            session = restored.session
            loading = false
            phase = EqFinderPhase.TRIAL
            beginTrial()
            if (clip != null) startPlayer { if (!it) pause = EqFinderPause.FOCUS_LOST }
        }
    }

    /**
     * 聴感の重みとセッション共通トリムを決めて固定する。**begin と resume の唯一の共通経路** —
     * 2 箇所に複製すると片方だけ直る (このリポジトリで繰り返している「同じ値が 2 箇所」)。
     *
     * [clip] が null (ライブ題材) なら重みは既定 (ピンク × K 特性)。トリムは同じ式。
     * ループでは重みとトリムを保存せず毎回ここで計算し直す — 同じ一節から決定的に
     * 同じ値が出る (同じであることは呼び出し側がハッシュで確かめた後)。
     */
    private suspend fun prepareLoudness(clip: Loaded?) {
        val bands = baseBands
        val ax = axes
        val prep = withContext(compute) {
            val w = if (clip == null) EqLoudness.defaultWeights() else weightsOf(clip)
            val corners = eqFinderCornerOverlays(ax)
                .map { EqFinderMaterialize.candidateBands(bands, ax, it) }
            w to EqLoudness.sessionTrimDb(corners, w)
        }
        weights = prep.first
        trimDb = prep.second
    }

    /** 保存済みセッションを消して新規の導入に戻す (確認は画面側で済んでいる)。 */
    fun startOver() {
        store.clear()
        saved = null
        resumeSongBlocked = false
        materialLive = false
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
        aHeard = heardNow()
        bHeard = false
        pushOverlay(trial.aOverlayDb10)
    }

    /** A/B カードのタップ = その候補を即プッシュ。何度でも。 */
    fun listen(candidate: EqFinderCandidate) {
        if (pause != EqFinderPause.NONE) return
        val trial = session?.currentTrial() ?: return
        selected = candidate
        when (candidate) {
            EqFinderCandidate.A -> pushOverlay(trial.aOverlayDb10)
            EqFinderCandidate.B -> pushOverlay(trial.bOverlayDb10)
        }
        if (heardNow()) markHeard(candidate)
    }

    /**
     * いま押した候補は実際に聞こえるか。ループは常に真 (自分で鳴らしている)。
     * ライブは音楽が本当に鳴っている間 (デバウンス無しの生値) だけ —
     * 無音のまま「聴いた」ことにすると、聴いていない音への回答が解錠される。
     */
    private fun heardNow(): Boolean = !materialLive || music.rawActive

    private fun markHeard(candidate: EqFinderCandidate) {
        when (candidate) {
            EqFinderCandidate.A -> aHeard = true
            EqFinderCandidate.B -> bHeard = true
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
        // 変わり続ける状態を Default のスレッドから読まないよう、材料はここで写し取る。
        val theBase = base
        val theAxes = axes
        val theBaseBands = baseBands
        val theOverlay = overlay
        scope.launch {
            val built = withContext(compute) {
                val plan = eqFinderBakePlan(theBase, theAxes, theOverlay)
                plan to EqFinderResultUi(
                    axes = theAxes.mapIndexed { i, axis ->
                        EqFinderAxisDelta(axisKind(axis), theOverlay.getOrElse(i) { 0 })
                    },
                    beforeDb = eqFinderResponseDb(theBaseBands),
                    afterDb = eqFinderResponseDb(afterBands),
                    consistencyWarning = r.consistencyWarning,
                    startBeaten = r.startBeatenInValidation,
                    bandChoices = if (theBase.mode == EqMode.GRAPHIC) {
                        EqSettings.BAND_COUNTS.mapNotNull { count ->
                            plan[count]?.let {
                                EqFinderBandChoice(
                                    count,
                                    Math.round(it.maxErrorDb * EqUnits.GAIN_SCALE).toInt(),
                                )
                            }
                        }
                    } else {
                        null
                    },
                )
            }
            bakePlan = built.first
            bakeBandCount = theBase.bandCount
            bakeFailed = false
            result = built.second
            phase = EqFinderPhase.RESULT
        }
    }

    // ------------------------------------------------------------------
    // 結果
    // ------------------------------------------------------------------

    /**
     * 焼き込むバンド数を選ぶ。**プレビューは押し直さない** — バンド構成が変わる push は
     * クリックレス切替 (ゲイン差のみ) の条件を外れ、耳で選んだ after と別物の聴感になる。
     * 選択は [apply] が保存する実体にだけ効く。
     */
    fun chooseBakeBandCount(count: Int) {
        if (count in bakePlan) bakeBandCount = count
    }

    /**
     * 適用して保存。**新しいスロットに着地する** (`llmdocs/eq-slot-design.md` §1
     * 「外から来る曲線は必ず新しいスロットに着地する」)。開始点だったスロットはそのまま残るので、
     * 探索の前後をチップ 1 タップで聴き比べられる。**名前は付けない** — 既定名は表示側の文言で、
     * データに焼くと端末の言語を替えたときに嘘になる。
     *
     * 焼けなかった (パラメトリックが満杯) ときだけ false を返す。画面はこれを見て、
     * 成功したときだけ閉じる。
     */
    fun apply(): Boolean {
        if (result == null) return false
        val baked = bakePlan[bakeBandCount]?.settings
        if (baked == null) {
            bakeFailed = true
            return false
        }
        // 永続化はここ 1 回だけ。押し込みは updateEq → pushEqParams が担い、直後の
        // setEqPreview(null) で最後の押しが「プレビュー無し + 新しい永続値」になる。
        // 「選んだバンド数で焼き込んだ EqSettings を作る」(bakePlan) までが不変の資産で、
        // 保存先を知るのはこの 1 行だけ。
        vm.landInNewSlot(mac, baked)
        vm.setEqPreview(null)
        store.clear()
        return true
    }

    /** 破棄。永続設定の音へ戻す (画面側が確認済み)。 */
    fun discard() {
        store.clear()
        vm.setEqPreview(null)
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
            if (materialLive) {
                // ライブはプレイヤーを持たない — play() の成否 (ここでは常に false) を見ると
                // 永久に DISCONNECTED のまま残る。音楽の有無だけ見て直接戻す。
                pause = if (music.active) EqFinderPause.NONE else EqFinderPause.NO_MUSIC
                // ⚠️ 一時停止を解く判定 (デバウンス済み) と heard の判定 (生値) は別物。
                // 切断 → 音楽アプリが自動停止 → デバウンスの窓が閉じる前に再接続、の順で
                // 戻ると、まだ何も鳴っていないのに解錠されてしまう。
                if (pause == EqFinderPause.NONE && heardNow()) markHeard(selected)
            } else if (player.play()) {
                pause = EqFinderPause.NONE
            }
        }
    }

    /**
     * 出口が消える予告 (BT 切断・有線抜き) を受けた。**[onConnectionChanged] より先に来る** —
     * `AudioDeviceCallback` 経由で `vm.eqOwner` が変わるまでの短い窓のあいだ、ループが
     * スピーカーから鳴りうるので、まず音を止める。持ち主の変化が後から届いたときの
     * 状態合わせは従来どおり [onConnectionChanged] が行う。
     */
    fun onBecomingNoisy() {
        player.stop()
        previewPlaying = false
        if (phase == EqFinderPhase.TRIAL && pause == EqFinderPause.NONE) {
            pause = EqFinderPause.DISCONNECTED
        }
    }

    /** AudioFocus を失った。プレイヤーは自分で止まっているので、状態だけ追いつかせる。 */
    private fun onFocusLost() {
        previewPlaying = false
        if (phase == EqFinderPhase.TRIAL && pause == EqFinderPause.NONE) {
            pause = EqFinderPause.FOCUS_LOST
        }
    }

    /**
     * 音楽の有無 (デバウンス済み) が変わった。ライブ題材の試行だけが反応する —
     * 自動で立てて自動で下ろす。再開ボタンは無い (他人のアプリは再開させられない)。
     * 切断が先に立っているときは上書きしない (戻し先は [onConnectionChanged] が決める)。
     */
    private fun onMusicChanged(active: Boolean) {
        musicActive = active
        if (!materialLive || phase != EqFinderPhase.TRIAL) return
        if (!active) {
            if (pause == EqFinderPause.NONE) pause = EqFinderPause.NO_MUSIC
        } else if (pause == EqFinderPause.NO_MUSIC) {
            pause = EqFinderPause.NONE
            // 選択中の候補は押し込まれたまま音楽が戻った = ここで初めて聴けている。
            // 判定は他の 3 経路と同じ heardNow() を通す (heard の出どころを 1 本に保つ)。
            if (heardNow()) markHeard(selected)
        }
    }

    /** 一時停止 (フォーカス喪失) からの再開ボタン。ライブでは到達しない (フォーカスを取らない)。 */
    fun resumePlayback() {
        if (player.play()) pause = EqFinderPause.NONE
    }

    /** 画面を離れるときに必ず呼ぶ。**終了経路はすべて setEqPreview(null) を通す。** */
    fun dispose() {
        music.stop()
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
        val uri = songUri?.toString()
        // ループの記録に一節の URI は必須 (無ければ再開で開けない)。ライブは持たない。
        if (!materialLive && uri == null) return
        store.save(
            EqFinderSaved(
                mac = mac,
                live = materialLive,
                uri = if (materialLive) null else uri,
                startMs = if (materialLive) 0 else startMs,
                lengthMs = if (materialLive) 0 else EQ_FINDER_LOOP_MS,
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

/** 再開に要るセッション状態の組み立て結果。 */
internal class EqFinderResumeState(
    val session: EqFinderSession,
    val axes: List<EqFinderAxis>,
    val base: EqSettings,
    val baseBands: List<EqBand>,
)

/**
 * 保存した記録から再開の状態を組み立てる。読めない記録は null。
 *
 * **軸はセッション JSON を真とする。**record.includeMid から `EqFinderAxes.default` で
 * 引き直すと同じ情報の 2 箇所持ちになり、既定の軸定義が変わった版で旧セッションを再開した
 * とき、保存済みのオーバーレイが別のバンドに実体化する (軸数が食い違えば候補の組み立ての
 * require がアプリを落とす)。includeMid は表示用の旗でしかない。
 */
internal fun eqFinderRestore(record: EqFinderSaved): EqFinderResumeState? {
    val session = EqFinderSession.fromJson(record.session) ?: return null
    return EqFinderResumeState(
        session = session,
        axes = session.axes,
        base = record.base,
        baseBands = if (record.base.enabled) record.base.bands else emptyList(),
    )
}

/** 焼き込み 1 通り分: 保存する実体と、試聴した応答からの最大乖離 (dB)。 */
internal class EqFinderBaked(val settings: EqSettings, val maxErrorDb: Double)

/**
 * 結果画面の焼き込み候補をまとめて作る。グラフィックは選べるバンド数の全部
 * ([EqSettings.BAND_COUNTS])、パラメトリックは元の形 1 通り (満杯で焼けなければ値が null)。
 * 副題に出す忠実度と apply が保存する実体を同じ計算から出すための入口 —
 * ここ以外で [EqFinderMaterialize.bake] を呼ばない。
 *
 * ### バンド数を変える焼き込みは「摘みの折れ線を運ぶ」([reband] → bake の順)
 *
 * 旧バンドの真の応答を新しい中心でサンプルすると、解の残差 (中心間の起伏) を次の目標に
 * 焼き込む — 全摘み +12 が 12.4 になる、reband が折れ線方式で直したのと同じ壊れ方が
 * ここに再発する。先に [reband] で摘みの折れ線を新しい並びへ運び、その応答 (中心では
 * 摘みの値そのもの) を bake が読む。
 *
 * ### 忠実度は全帯域の格子で測る
 *
 * バンド中心だけで測ると solve が厳密に合わせる点を測ることになり、常にほぼ 0 の
 * 何も言わない数字になる。試聴した応答 (base + オーバーレイ。土台の規則は
 * セッションと同じ「enabled なら bands、切ってあれば素の音」) と焼き込み後の応答の
 * max|差| を [eqFinderResponseDb] の 120 点で取る。
 *
 * **31 バンドが 15 バンドより悪い数字になることがあるが、バグではない** — 最上バンド
 * (20 kHz 中心) が fs 48 kHz の Nyquist で潰れる分で、[EqSolver.defaultQ] の表の
 * 31 バンド行に書かれた既知の挙動。Q でもシェルフでも消えないことは実測済みなので、
 * 事実のまま副題に出す。
 */
internal fun eqFinderBakePlan(
    base: EqSettings,
    axes: List<EqFinderAxis>,
    overlayDb10: List<Int>,
): Map<Int, EqFinderBaked?> {
    val heardBands = EqFinderMaterialize.candidateBands(
        if (base.enabled) base.bands else emptyList(),
        axes,
        overlayDb10,
    )
    val heardDb = eqFinderResponseDb(heardBands)
    val counts = if (base.mode == EqMode.GRAPHIC) EqSettings.BAND_COUNTS else listOf(base.bandCount)
    return counts.associateWith { count ->
        val seed = if (base.mode == EqMode.GRAPHIC) reband(base, count) else base
        EqFinderMaterialize.bake(seed, axes, overlayDb10)?.let { baked ->
            val bakedDb = eqFinderResponseDb(baked.bands)
            EqFinderBaked(baked, heardDb.indices.maxOf { abs(heardDb[it] - bakedDb[it]) })
        }
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
    // **セッション中の A/B は必ず標準 (biquad)。**候補はシェルフのオーバーレイで、そこでは
    // biquad が定義どおりの厳密値なので高精度にする利得が無い。一方で切り替えのたびに
    // FIR の組み直し (0.3〜0.6 s) が挟まり、**即時に切り替わることを前提にした聴き比べが壊れる。**
    // 焼き込んだ結果 (apply → landInNewSlot) は base の選択のまま鳴る。
    precision = EqPrecision.STANDARD,
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
        // 出口が消える予告 (BT 切断・有線抜き)。AudioDeviceCallback → vm.eqOwner の変化より
        // 先に届くので、これを受けて先に止めないと、その窓のあいだ一節がスピーカーから鳴る。
        // システム放送なので NOT_EXPORTED でよい (他アプリから受ける必要が無い)。
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(from: Context?, intent: Intent?) {
                if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                    controller.onBecomingNoisy()
                }
            }
        }
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose {
            runCatching { appContext.unregisterReceiver(receiver) }
            controller.dispose()
        }
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
                    // ライブ題材は音楽が流れていないと始められない (検知は試行と同じもの)。
                    controller.materialLive && !controller.musicActive ->
                        stringResource(R.string.eq_finder_no_music)

                    else -> null
                }
                EqFinderIntroContent(
                    ui = EqFinderIntroUi(
                        materialLive = controller.materialLive,
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
                            EqFinderResumeUi(
                                done = it.done,
                                live = it.live,
                                blocked = controller.resumeBlocked(),
                            )
                        },
                    ),
                    onMaterial = controller::chooseMaterial,
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
                    selectedBandCount = controller.bakeBandCount,
                    onBandCount = controller::chooseBakeBandCount,
                    bakeFailed = controller.bakeFailed,
                    // 着いた先はスロットのチップ行 (戻り先の音響処理の画面の一番上) に出る。
                    // 焼けなかったときは理由を出したまま留まる。
                    onApply = { if (controller.apply()) onBack() },
                    onDiscard = {
                        controller.discard()
                        onBack()
                    },
                )
            }
        }
    }
}
