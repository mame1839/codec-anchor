package io.github.mame1839.codecanchor.audio

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler

/**
 * 「端末でいま音楽 (USAGE_MEDIA) が鳴っているか」の検知。
 * 探索セッションのライブ題材 (いま流れている音楽) の開始の門と自動一時停止に使う。
 *
 * 出どころは [AudioManager.registerAudioPlaybackCallback] (minSdk 31 なので無条件に使える)。
 * **[active] は消える方向 (再生 → 無音) だけ [quietDelayMs] 遅らせたデバウンス値** —
 * 曲間で一瞬 inactive になるアプリがあり、生のまま画面へ流すと警告が明滅する。
 * 現れる方向は即時。[rawActive] はデバウンス無しの生値で、「本当に鳴っている間だけ
 * 聴いたと数える」判定 (heard の正直さ) はこちらを見る。
 *
 * [handler] は main looper であること — デバウンスの取り消しと [onChange] の届け先を
 * 1 本のスレッドに束ねる (呼び出し側は Compose の状態を直接書く)。
 * [EqFinderController][io.github.mame1839.codecanchor.ui.EqFinderController] へは注入で渡す形に
 * してあり、Robolectric では AudioManager 無しで作って [onRaw] を直接振ると全遷移を検査できる。
 */
class MusicPlaybackMonitor(
    private val audioManager: AudioManager?,
    private val handler: Handler,
    private val quietDelayMs: Long = QUIET_DELAY_MS,
) {
    /** デバウンス済みの「流れているか」。門と一時停止の表示はこちら。 */
    var active: Boolean = false
        private set

    /** デバウンス無しの生値。無音の瞬間に「聴いた」と数えないための判定はこちら。 */
    var rawActive: Boolean = false
        private set

    /** [active] が変わったときに handler のスレッドで呼ばれる。[start] の初期読みでは呼ばない。 */
    var onChange: ((Boolean) -> Unit)? = null

    private var started = false
    private var pendingQuiet: Runnable? = null

    private val callback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            onRaw(configs.orEmpty().any(::isMedia))
        }
    }

    /** 監視を始め、いまの状態を読み込む。通知はしない — 呼び出し側が [active] を読む。 */
    fun start() {
        if (started) return
        started = true
        runCatching { audioManager?.registerAudioPlaybackCallback(callback, handler) }
        val now = runCatching { audioManager?.activePlaybackConfigurations }
            .getOrNull().orEmpty().any(::isMedia)
        rawActive = now
        active = now
    }

    fun stop() {
        if (!started) return
        started = false
        pendingQuiet?.let { handler.removeCallbacks(it) }
        pendingQuiet = null
        runCatching { audioManager?.unregisterAudioPlaybackCallback(callback) }
    }

    /** 生の遷移。internal なのはテストが直接振るため — 実機では [callback] だけが呼ぶ。 */
    internal fun onRaw(nowActive: Boolean) {
        rawActive = nowActive
        pendingQuiet?.let { handler.removeCallbacks(it) }
        pendingQuiet = null
        if (nowActive) {
            if (!active) {
                active = true
                onChange?.invoke(true)
            }
        } else if (active) {
            val settle = Runnable {
                pendingQuiet = null
                active = false
                onChange?.invoke(false)
            }
            pendingQuiet = settle
            handler.postDelayed(settle, quietDelayMs)
        }
    }

    private fun isMedia(config: AudioPlaybackConfiguration): Boolean =
        runCatching { config.audioAttributes.usage == AudioAttributes.USAGE_MEDIA }
            .getOrDefault(false)

    companion object {
        /** 消える方向のデバウンス。曲間の一瞬の無音より長く、止めたことに気づけない長さにはしない。 */
        const val QUIET_DELAY_MS = 1_500L
    }
}
