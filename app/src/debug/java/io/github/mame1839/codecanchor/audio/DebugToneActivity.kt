package io.github.mame1839.codecanchor.audio

import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.mame1839.codecanchor.bridge.EqDeviceStore
import io.github.mame1839.codecanchor.bridge.SettingsStore
import io.github.mame1839.codecanchor.core.AudioOutputs
import io.github.mame1839.codecanchor.core.EqParams
import io.github.mame1839.codecanchor.core.EqRoute
import io.github.mame1839.codecanchor.core.EqSettings
import io.github.mame1839.codecanchor.core.Spectrum
import io.github.mame1839.codecanchor.ui.CodecAnchorTheme
import io.github.mame1839.codecanchor.xposed.XLog
import kotlin.concurrent.thread
import kotlin.random.Random

class DebugToneActivity : ComponentActivity() {

    private var player: LoopPlayer? = null

    private var playing by mutableStateOf(false)
    private var status by mutableStateOf("準備中…")
    private var busy by mutableStateOf(false)
    private var lines by mutableStateOf(listOf<String>())

    private var previousFrames = mapOf<Int, Long>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = LoopPlayer(getSystemService(AudioManager::class.java))
        p.onFocusLost = {
            playing = false
            status = "オーディオフォーカスを失って停止"
        }
        player = p
        thread(name = "tone-prepare") {
            val pcm = Spectrum.pinkNoise(TONE_SECONDS * SAMPLE_RATE, Random(TONE_SEED))
            val started = p.prepare(pcm, 1, SAMPLE_RATE) && p.play()
            playing = started
            status = if (started) "ピンクノイズ再生中 (${TONE_SECONDS} s ループ)" else "再生を開始できない"
        }
        setContent {
            CodecAnchorTheme {
                Screen()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }

    private fun togglePlayback() {
        val p = player ?: return
        if (p.isPlaying) {
            p.stop()
            playing = false
            status = "停止"
        } else {
            playing = p.play()
            status = if (playing) "ピンクノイズ再生中 (${TONE_SECONDS} s ループ)" else "再生を開始できない"
        }
    }

    private fun measureSuLatency() {
        if (busy) return
        busy = true
        lines = listOf("計測中… (su を ${LATENCY_RUNS} 回)")
        thread(name = "eq-latency") {
            lines = runCatching { runLatencyMeasurement() }
                .getOrElse { listOf("計測に失敗: ${it.message}") }
            busy = false
        }
    }

    private fun runLatencyMeasurement(): List<String> {
        val dir = applicationInfo.nativeLibraryDir.orEmpty()
        val config = SettingsStore(this).load()
        val outputs = AudioOutputs.a2dp(getSystemService(AudioManager::class.java))
        val owner = EqRoute.owner(outputs, EqDeviceStore(this).load())
        val settings = config?.profileFor(owner)?.eq ?: EqSettings()
        val out = mutableListOf(
            "owner=${owner ?: "なし"} on=${settings.enabled} bands=${settings.bands.size}",
        )
        val times = LongArray(LATENCY_RUNS)
        for (i in 0 until LATENCY_RUNS) {
            val t0 = SystemClock.elapsedRealtime()
            val result = EqParams.apply(dir, settings)
            val ms = SystemClock.elapsedRealtime() - t0
            times[i] = ms
            Log.i(
                XLog.TAG,
                "eq-latency run=${i + 1}/$LATENCY_RUNS ms=$ms outcome=${result.outcome} exit=${result.exitCode}",
            )
            out += "#${i + 1}: $ms ms ${result.outcome}"
        }
        val sorted = times.sorted()
        val median = (sorted[LATENCY_RUNS / 2 - 1] + sorted[LATENCY_RUNS / 2]) / 2
        val summary = "median=${median}ms min=${sorted.first()}ms max=${sorted.last()}ms"
        Log.i(XLog.TAG, "eq-latency summary runs=$LATENCY_RUNS $summary")
        out += summary
        return out
    }

    private fun probeEffectPath() {
        if (busy) return
        busy = true
        lines = listOf("caeqstat を読んでいる…")
        thread(name = "eq-stat") {
            val dir = applicationInfo.nativeLibraryDir.orEmpty()
            lines = EqStat.read(dir).fold(
                onSuccess = { slots ->
                    val previous = previousFrames
                    previousFrames = slots.associate { it.slot to it.frames }
                    if (slots.isEmpty()) {
                        listOf("生きている枠が無い (イヤホン未接続か、モジュールが動いていない)")
                    } else {
                        slots.map { s ->
                            val role = if (s.deviceSession) "device" else "other"
                            val delta = previous[s.slot]?.let { " (+${s.frames - it})" }.orEmpty()
                            val inDb = s.inPeakDbfs?.let { "$it dBFS" } ?: "silent"
                            "slot=${s.slot} [$role] in=$inDb age=${s.age} frames=${s.frames}$delta" +
                                if (s.enabled) "" else " (disabled)"
                        }
                    }
                },
                onFailure = { listOf("caeqstat を読めない: ${it.message}") },
            )
            busy = false
        }
    }

    @Composable
    private fun Screen() {
        Scaffold { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("EQ デバッグトーン", style = MaterialTheme.typography.titleLarge)
                Text(status, style = MaterialTheme.typography.bodyMedium)
                Button(onClick = ::togglePlayback) {
                    Text(if (playing) "停止" else "再生")
                }
                Button(onClick = ::measureSuLatency, enabled = !busy) {
                    Text("su レイテンシ計測 ($LATENCY_RUNS 回)")
                }
                Button(onClick = ::probeEffectPath, enabled = !busy) {
                    Text("経路確認 (caeqstat)")
                }
                lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }

    companion object {
        private const val SAMPLE_RATE = 48_000
        private const val TONE_SECONDS = 30
        private const val TONE_SEED = 20_260_812L
        private const val LATENCY_RUNS = 20
    }
}
