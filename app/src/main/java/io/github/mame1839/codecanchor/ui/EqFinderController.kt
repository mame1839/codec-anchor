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

enum class EqFinderPhase { INTRO, TRIAL, RESULT }

class EqFinderController(
    private val context: Context,
    private val vm: MainViewModel,
    val mac: String,
    private val scope: CoroutineScope,
    private val music: MusicPlaybackMonitor = MusicPlaybackMonitor(
        runCatching { context.getSystemService(AudioManager::class.java) }.getOrNull(),
        Handler(Looper.getMainLooper()),
    ),
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) {
    private val store = EqFinderStore(context)
    private val player = LoopPlayer(
        runCatching { context.getSystemService(AudioManager::class.java) }.getOrNull(),
    ).also { p ->
        p.onFocusLost = { scope.launch { onFocusLost() } }
    }

    var phase by mutableStateOf(EqFinderPhase.INTRO)
        private set

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
    var startMs by mutableIntStateOf(0)
    var includeMid by mutableStateOf(false)
    var fineTune by mutableStateOf(false)
    var previewPlaying by mutableStateOf(false)
        private set

    var saved by mutableStateOf(store.load()?.takeIf { it.mac == mac })
        private set

    var materialLive by mutableStateOf(saved?.live == true)
        private set

    var musicActive by mutableStateOf(false)
        private set

    private var resumeSongBlocked by mutableStateOf(false)

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

    private var session: EqFinderSession? = null
    private var axes: List<EqFinderAxis> = emptyList()
    private var base: EqSettings = EqSettings()
    private var baseBands: List<EqBand> = emptyList()
    private var weights: DoubleArray = DoubleArray(0)
    private var baseLevelDb = 0.0
    private var pcmHash = 0L

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

    var result by mutableStateOf<EqFinderResultUi?>(null)
        private set

    var bakeBandCount by mutableIntStateOf(10)
        private set

    var bakeFailed by mutableStateOf(false)
        private set

    private var overlay: List<Int> = emptyList()

    private var bakePlan: Map<Int, EqFinderBaked?> = emptyMap()

    init {
        music.onChange = { onMusicChanged(it) }
        music.start()
        musicActive = music.active
    }

    fun chooseMaterial(live: Boolean) {
        if (materialLive == live) return
        materialLive = live
        if (live && previewPlaying) {
            player.stop()
            previewPlaying = false
        }
    }

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

    fun begin() {
        val clip = if (materialLive) null else (loaded ?: return)
        if (session != null || loading) return
        if (vm.eqOwner != mac) return
        if (materialLive && !music.active) return
        val current = vm.config.profileFor(mac)?.eq ?: EqSettings()
        scope.launch {
            loading = true
            base = current
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

    fun resume() {
        val record = saved ?: return
        val uri = if (record.live) null else (songUri ?: return)
        if (loading || session != null || resumeBlocked() != null) return
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
                store.clear()
                saved = null
                loading = false
                return@launch
            }
            loaded = clip
            songLoaded = clip != null
            base = restored.base
            baseBands = restored.baseBands
            includeMid = record.includeMid
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

    private suspend fun prepareLoudness(clip: Loaded?) {
        val theBase = base
        val prep = withContext(compute) {
            val w = if (clip == null) EqLoudness.defaultWeights() else weightsOf(clip)
            w to EqLoudness.baseLevelDb(theBase, w)
        }
        weights = prep.first
        baseLevelDb = prep.second
    }

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

    private fun weightsOf(clip: Loaded): DoubleArray {
        val (hz, db) = Spectrum.averageSpectrumDb(clip.monoMix(), clip.sampleRate)
        if (hz.isEmpty()) return EqLoudness.defaultWeights()
        return EqLoudness.weightsFromSpectrumDb(Spectrum.resampleDb(hz, db, EqLoudness.GRID_HZ))
    }

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
        selected = EqFinderCandidate.A
        aHeard = heardNow()
        bHeard = false
        pushOverlay(trial.aOverlayDb10)
    }

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

    fun finishNow() {
        if (done > 0) showResult()
    }

    private fun showResult() {
        val s = session ?: return
        val r = s.result()
        overlay = r.overlayDb10
        val afterBands = EqFinderMaterialize.candidateBands(baseBands, axes, overlay)
        pushBands(afterBands)
        val theBase = base
        val theAxes = axes
        val theBaseBands = baseBands
        val theOverlay = overlay
        val theWeights = weights
        val theBaseLevelDb = baseLevelDb
        scope.launch {
            val built = withContext(compute) {
                val plan = eqFinderBakePlan(theBase, theAxes, theOverlay, theWeights, theBaseLevelDb)
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

    fun chooseBakeBandCount(count: Int) {
        if (count in bakePlan) bakeBandCount = count
    }

    fun apply(): Boolean {
        if (result == null) return false
        val baked = bakePlan[bakeBandCount]?.settings
        if (baked == null) {
            bakeFailed = true
            return false
        }
        vm.landInNewSlot(mac, baked)
        vm.setEqPreview(null)
        store.clear()
        return true
    }

    fun discard() {
        store.clear()
        vm.setEqPreview(null)
    }

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
        if (phase == EqFinderPhase.TRIAL && pause == EqFinderPause.DISCONNECTED) {
            if (materialLive) {
                pause = if (music.active) EqFinderPause.NONE else EqFinderPause.NO_MUSIC
                if (pause == EqFinderPause.NONE && heardNow()) markHeard(selected)
            } else if (player.play()) {
                pause = EqFinderPause.NONE
            }
        }
    }

    fun onBecomingNoisy() {
        player.stop()
        previewPlaying = false
        if (phase == EqFinderPhase.TRIAL && pause == EqFinderPause.NONE) {
            pause = EqFinderPause.DISCONNECTED
        }
    }

    private fun onFocusLost() {
        previewPlaying = false
        if (phase == EqFinderPhase.TRIAL && pause == EqFinderPause.NONE) {
            pause = EqFinderPause.FOCUS_LOST
        }
    }

    private fun onMusicChanged(active: Boolean) {
        musicActive = active
        if (!materialLive || phase != EqFinderPhase.TRIAL) return
        if (!active) {
            if (pause == EqFinderPause.NONE) pause = EqFinderPause.NO_MUSIC
        } else if (pause == EqFinderPause.NO_MUSIC) {
            pause = EqFinderPause.NONE
            if (heardNow()) markHeard(selected)
        }
    }

    fun resumePlayback() {
        if (player.play()) pause = EqFinderPause.NONE
    }

    fun dispose() {
        music.stop()
        player.release()
        vm.setEqPreview(null)
    }

    private fun startPlayer(onResult: (Boolean) -> Unit) {
        val clip = loaded ?: return onResult(false)
        val ok = player.prepare(clip.pcm, clip.channels, clip.sampleRate) && player.play()
        onResult(ok)
    }

    private fun pushOverlay(overlayDb10: List<Int>) {
        pushBands(EqFinderMaterialize.candidateBands(baseBands, axes, overlayDb10))
    }

    private fun pushBands(bands: List<EqBand>) {
        vm.setEqPreview(
            EqSessionPreview(mac, eqFinderCandidateSettings(base, bands, weights, baseLevelDb)),
        )
    }

    private fun persist() {
        val s = session ?: return
        val uri = songUri?.toString()
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
        const val START_STEP_DB10 = 40

        const val FINE_STEP_DB10 = 10
    }
}

internal class EqFinderResumeState(
    val session: EqFinderSession,
    val axes: List<EqFinderAxis>,
    val base: EqSettings,
    val baseBands: List<EqBand>,
)

internal fun eqFinderRestore(record: EqFinderSaved): EqFinderResumeState? {
    val session = EqFinderSession.fromJson(record.session) ?: return null
    return EqFinderResumeState(
        session = session,
        axes = session.axes,
        base = record.base,
        baseBands = if (record.base.enabled) record.base.bands else emptyList(),
    )
}

internal class EqFinderBaked(val settings: EqSettings, val maxErrorDb: Double)

internal fun eqFinderBakePlan(
    base: EqSettings,
    axes: List<EqFinderAxis>,
    overlayDb10: List<Int>,
    weights: DoubleArray,
    baseLevelDb: Double,
): Map<Int, EqFinderBaked?> {
    val heardBands = EqFinderMaterialize.candidateBands(
        if (base.enabled) base.bands else emptyList(),
        axes,
        overlayDb10,
    )
    val heardDb = eqFinderResponseDb(heardBands)
    val heardPreampDb10 = EqLoudness.preampDb10(heardBands, weights, baseLevelDb)
    val counts = if (base.mode == EqMode.GRAPHIC) EqSettings.BAND_COUNTS else listOf(base.bandCount)
    return counts.associateWith { count ->
        val seed = if (base.mode == EqMode.GRAPHIC) reband(base, count) else base
        EqFinderMaterialize.bake(seed, axes, overlayDb10, heardPreampDb10)?.let { baked ->
            val bakedDb = eqFinderResponseDb(baked.bands)
            EqFinderBaked(baked, heardDb.indices.maxOf { abs(heardDb[it] - bakedDb[it]) })
        }
    }
}

internal fun eqFinderCandidateSettings(
    base: EqSettings,
    bands: List<EqBand>,
    weights: DoubleArray,
    baseLevelDb: Double,
): EqSettings = base.copy(
    enabled = true,
    bands = bands,
    preampDb10 = EqLoudness.preampDb10(bands, weights, baseLevelDb),
    precision = EqPrecision.STANDARD,
)

internal fun axisKind(axis: EqFinderAxis): EqFinderAxisKind = when (axis.type) {
    EqBandType.LOW_SHELF -> EqFinderAxisKind.BASS
    EqBandType.HIGH_SHELF -> EqFinderAxisKind.TREBLE
    else -> EqFinderAxisKind.MID
}

internal fun pcmContentHash(pcm: FloatArray): Long {
    var h = -0x340d631b7bdddcdbL
    for (f in pcm) {
        h = (h xor f.toRawBits().toLong()) * 0x100000001b3L
    }
    return h
}

@Composable
fun EqFinderScreen(
    vm: MainViewModel,
    mac: String,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
) {
    val profile = vm.config.profileFor(mac)
    if (profile == null) {
        LaunchedEffect(mac) { onBack() }
        return
    }

    val appContext = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val key = remember(mac) { EqDevices.normalizeMac(mac) ?: mac.uppercase() }
    val controller = remember { EqFinderController(appContext, vm, key, scope) }
    DisposableEffect(controller) {
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
