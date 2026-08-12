package io.github.mame1839.codecanchor.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mame1839.codecanchor.R
import io.github.mame1839.codecanchor.bridge.EqFinderStore
import io.github.mame1839.codecanchor.core.EqAvailability
import io.github.mame1839.codecanchor.core.EqBand
import io.github.mame1839.codecanchor.core.EqDevices
import io.github.mame1839.codecanchor.core.EqSolver
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

/** ループ再生する一節の長さ。固定 (仕様)。 */
internal const val EQ_FINDER_LOOP_MS = 20_000

// ---------------------------------------------------------------------------
// UI の状態。EqFinderController が組み立て、ここの composable は表示するだけ。
// ---------------------------------------------------------------------------

enum class EqFinderCandidate { A, B }

enum class EqFinderAnswer { A, SAME, B }

enum class EqFinderPause { NONE, DISCONNECTED, FOCUS_LOST }

/** 探索する軸。表示名だけの区別で、周波数や種別はエンジン側が持つ。 */
enum class EqFinderAxisKind { BASS, TREBLE, MID }

/**
 * 導入画面の状態。曲を選ぶ → 読めたら一節を決める → 始める、の順に進む。
 *
 * [resume] があるときは新規の入力は出さず、「続きから」を主にする
 * (保存された曲が開けないときだけ、選び直しの行が出る)。
 */
data class EqFinderIntroUi(
    val songName: String? = null,
    val loading: Boolean = false,
    val loadFailed: Boolean = false,
    /** 一節が読めて再生できる状態か。長さ ([trackDurationMs]) とは別 — 長さは取れない形式がある。 */
    val loaded: Boolean = false,
    val trackDurationMs: Int = 0,
    val startMs: Int = 0,
    val previewPlaying: Boolean = false,
    val includeMid: Boolean = false,
    val fineTuneVisible: Boolean = false,
    val fineTune: Boolean = false,
    /** null なら始められる。文字列は理由 1 行 (接続が無い等)。 */
    val startBlockedReason: String? = null,
    val resume: EqFinderResumeUi? = null,
)

/**
 * 「続きから」を出せない理由。null なら再開できる。
 *
 * どちらも**黙って続けると回答と結果の意味が壊れる**食い違い — 設定が変わっていれば、
 * 探索は古い土台の上で進むのに確定はその古い土台で現行設定を上書きする (中間の編集が消える)。
 * 一節が変わっていれば、これまでの回答は別の音についてのもの。だから警告ではなく遮断にする。
 */
enum class EqFinderResumeBlocked { SETTINGS_CHANGED, SONG_CHANGED }

data class EqFinderResumeUi(
    val done: Int,
    val blocked: EqFinderResumeBlocked? = null,
)

/**
 * 試行画面の状態。
 *
 * **候補のゲイン値を意図して持たない。**目隠し A/B は中身が見えないことが成立条件で、
 * ここに値が無ければ画面が誤って出すこともない (型で守る)。
 */
data class EqFinderTrialUi(
    val done: Int,
    val total: Int,
    val selected: EqFinderCandidate,
    val bothHeard: Boolean,
    val pause: EqFinderPause = EqFinderPause.NONE,
)

data class EqFinderAxisDelta(val kind: EqFinderAxisKind, val deltaDb10: Int)

/**
 * 結果画面の状態。[beforeDb] / [afterDb] は [eqFinderResponseDb] で標本化した応答
 * (data class にしないのは、配列の equals が参照比較で意味を持たないため)。
 */
class EqFinderResultUi(
    val axes: List<EqFinderAxisDelta>,
    val beforeDb: DoubleArray,
    val afterDb: DoubleArray,
    val consistencyWarning: Boolean,
    /** 検証段で結果が開始点に勝ったか。null = 検証まで進まずに終えた。 */
    val startBeaten: Boolean?,
    val bakeFailed: Boolean = false,
    /** 適用が済んで、プリセットとしても保存するかを聞いている。 */
    val presetOffer: Boolean = false,
)

// ---------------------------------------------------------------------------
// 入口 (EqScreen のカード)
// ---------------------------------------------------------------------------

/**
 * 音響処理の画面に置く入口。開けるのは値がいまこの機器の音に届くときだけ —
 * 目隠し比較は候補を実際に鳴らせないと成立しないので、押せない理由を 1 行で出す。
 */
@Composable
fun EqFinderEntryCard(vm: MainViewModel, mac: String, onOpen: () -> Unit) {
    val availability = vm.eqAvailability(mac)
    val key = EqDevices.normalizeMac(mac)
    val connected = key != null && key == vm.eqOwner
    val openable = availability == EqAvailability.OK && connected

    // 中断セッションの有無。この画面は探索から戻ると組み直されるので、そのたびに読み直される。
    val context = LocalContext.current
    val saved = remember(mac) {
        EqFinderStore(context).load()?.takeIf { it.mac == key }
    }

    SettingsCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = openable, onClick = onOpen)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val disabled = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.eq_finder_title),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (openable) Color.Unspecified else disabled,
                )
                Text(
                    text = stringResource(
                        if (saved != null) R.string.eq_finder_entry_resume else R.string.eq_finder_entry_desc,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (openable) MaterialTheme.colorScheme.primary else disabled,
                )
                if (!openable) {
                    Text(
                        text = stringResource(
                            if (availability != EqAvailability.OK) {
                                R.string.eq_finder_entry_unavailable
                            } else {
                                R.string.eq_finder_entry_disconnected
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 骨格
// ---------------------------------------------------------------------------

/**
 * 探索画面の枠。題は 2 行 (機能名 + イヤホン名) で、組み方と高さの理由は
 * [EqScreenTitle] / [eqTitleBarHeight] と同じ。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EqFinderScaffold(
    deviceName: String,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.eq_finder_title),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                        )
                        Text(
                            text = deviceName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                expandedHeight = eqTitleBarHeight,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(screenPadding(inner)),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            content()
        }
    }
}

// ---------------------------------------------------------------------------
// 導入
// ---------------------------------------------------------------------------

@Composable
internal fun EqFinderIntroContent(
    ui: EqFinderIntroUi,
    onPickSong: () -> Unit,
    onStartMs: (Int) -> Unit,
    onStartMsChosen: () -> Unit,
    onPreviewToggle: () -> Unit,
    onIncludeMid: (Boolean) -> Unit,
    onFineTune: (Boolean) -> Unit,
    onBegin: () -> Unit,
    onResume: () -> Unit,
    onStartOver: () -> Unit,
) {
    val resume = ui.resume
    if (resume != null) {
        ResumeCard(ui, resume, onResume, onStartOver)
        NotesCard()
        return
    }

    SettingsCard {
        SongRow(ui, onPickSong)
        if (ui.loaded) {
            RowDivider()
            PassageRows(ui, onStartMs, onStartMsChosen, onPreviewToggle)
        }
    }

    SettingsCard {
        SwitchRow(
            title = stringResource(R.string.eq_finder_include_mid),
            description = stringResource(R.string.eq_finder_include_mid_desc),
            checked = ui.includeMid,
            onChange = onIncludeMid,
        )
        if (ui.fineTuneVisible) {
            SwitchRow(
                title = stringResource(R.string.eq_finder_fine_tune),
                checked = ui.fineTune,
                onChange = onFineTune,
            )
        }
    }

    NotesCard()

    Button(
        onClick = onBegin,
        enabled = ui.loaded && !ui.loading && ui.startBlockedReason == null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.eq_finder_begin))
    }
    if (ui.startBlockedReason != null) {
        Text(
            text = ui.startBlockedReason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun ResumeCard(
    ui: EqFinderIntroUi,
    resume: EqFinderResumeUi,
    onResume: () -> Unit,
    onStartOver: () -> Unit,
) {
    var confirmStartOver by rememberSaveable { mutableStateOf(false) }

    SettingsCard {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = stringResource(R.string.eq_finder_resume),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = pluralStringResource(R.plurals.eq_finder_resume_progress, resume.done, resume.done),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (resume.blocked != null) {
            NoticeRow(
                icon = R.drawable.ic_warning,
                text = stringResource(
                    when (resume.blocked) {
                        EqFinderResumeBlocked.SETTINGS_CHANGED -> R.string.eq_finder_resume_blocked_settings
                        EqFinderResumeBlocked.SONG_CHANGED -> R.string.eq_finder_resume_blocked_song
                    },
                ),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(onClick = { confirmStartOver = true }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.eq_finder_start_over))
            }
            // 遮断されたら「続ける」は出さない。押せないボタンを残すと、直せば押せるように読める。
            if (resume.blocked == null) {
                Button(
                    onClick = onResume,
                    enabled = !ui.loading && ui.startBlockedReason == null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.action_continue))
                }
            }
        }
        if (ui.startBlockedReason != null) {
            Text(
                text = ui.startBlockedReason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
            )
        }
    }

    if (confirmStartOver) {
        AlertDialog(
            onDismissRequest = { confirmStartOver = false },
            title = { Text(stringResource(R.string.eq_finder_start_over_title)) },
            text = { Text(stringResource(R.string.eq_finder_start_over_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmStartOver = false
                        onStartOver()
                    },
                ) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmStartOver = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun SongRow(ui: EqFinderIntroUi, onPickSong: () -> Unit) {
    NavigationRow(
        title = stringResource(R.string.eq_finder_song),
        value = when {
            ui.loading -> stringResource(R.string.eq_finder_song_loading)
            ui.songName != null -> ui.songName
            else -> stringResource(R.string.eq_finder_song_none)
        },
        onClick = onPickSong,
    )
    if (ui.loadFailed) {
        Text(
            text = stringResource(R.string.eq_finder_song_failed),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun PassageRows(
    ui: EqFinderIntroUi,
    onStartMs: (Int) -> Unit,
    onStartMsChosen: () -> Unit,
    onPreviewToggle: () -> Unit,
) {
    val last = max(0, ui.trackDurationMs - EQ_FINDER_LOOP_MS)
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.eq_finder_passage),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    // 長さの取れない形式では開始位置だけを出す (0 を「0:00 まで」と読ませない)。
                    text = if (ui.trackDurationMs > 0) {
                        stringResource(
                            R.string.eq_finder_passage_value,
                            eqFinderTimeText(ui.startMs),
                            eqFinderTimeText(ui.trackDurationMs),
                        )
                    } else {
                        eqFinderTimeText(ui.startMs)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            OutlinedButton(onClick = onPreviewToggle, enabled = !ui.loading) {
                Text(
                    stringResource(
                        if (ui.previewPlaying) R.string.eq_finder_preview_stop else R.string.eq_finder_preview_play,
                    ),
                )
            }
        }
        Text(
            text = stringResource(R.string.eq_finder_passage_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = ui.startMs.toFloat(),
            onValueChange = { onStartMs(((it / 1_000f).roundToInt() * 1_000).coerceIn(0, last)) },
            onValueChangeFinished = onStartMsChosen,
            valueRange = 0f..last.toFloat(),
            enabled = last > 0 && !ui.loading,
        )
    }
}

@Composable
private fun NotesCard() {
    SettingsCard {
        Text(
            text = stringResource(R.string.eq_finder_notes),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp),
        )
        NoticeRow(icon = R.drawable.ic_info, text = stringResource(R.string.eq_finder_note_volume))
        NoticeRow(icon = R.drawable.ic_headphones, text = stringResource(R.string.eq_finder_note_earphones))
        NoticeRow(
            icon = R.drawable.ic_info,
            text = stringResource(R.string.eq_finder_note_length),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// 試行
// ---------------------------------------------------------------------------

@Composable
internal fun EqFinderTrialContent(
    ui: EqFinderTrialUi,
    onListen: (EqFinderCandidate) -> Unit,
    onAnswer: (EqFinderAnswer) -> Unit,
    onFinishNow: () -> Unit,
    onSuspend: () -> Unit,
    onResumePlayback: () -> Unit,
) {
    val active = ui.pause == EqFinderPause.NONE

    // 進捗。総数は見込みなので「~」を付けて出す。
    Column(Modifier.padding(horizontal = 4.dp)) {
        Text(
            text = stringResource(
                R.string.eq_finder_progress_value,
                eqCountText(ui.done),
                eqCountText(ui.total),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { if (ui.total > 0) (ui.done.toFloat() / ui.total).coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CandidateCard(
            label = stringResource(R.string.eq_finder_a),
            selected = ui.selected == EqFinderCandidate.A,
            enabled = active,
            onClick = { onListen(EqFinderCandidate.A) },
        )
        CandidateCard(
            label = stringResource(R.string.eq_finder_b),
            selected = ui.selected == EqFinderCandidate.B,
            enabled = active,
            onClick = { onListen(EqFinderCandidate.B) },
        )
    }

    Text(
        text = stringResource(
            if (ui.bothHeard) R.string.eq_finder_trial_hint else R.string.eq_finder_answer_locked,
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AnswerButton(stringResource(R.string.eq_finder_answer_a), ui.bothHeard && active) {
            onAnswer(EqFinderAnswer.A)
        }
        AnswerButton(stringResource(R.string.eq_finder_answer_same), ui.bothHeard && active) {
            onAnswer(EqFinderAnswer.SAME)
        }
        AnswerButton(stringResource(R.string.eq_finder_answer_b), ui.bothHeard && active) {
            onAnswer(EqFinderAnswer.B)
        }
    }

    if (ui.pause != EqFinderPause.NONE) {
        SettingsCard {
            NoticeRow(
                icon = R.drawable.ic_warning,
                text = stringResource(
                    if (ui.pause == EqFinderPause.DISCONNECTED) {
                        R.string.eq_finder_paused_disconnected
                    } else {
                        R.string.eq_finder_paused_focus
                    },
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            )
            if (ui.pause == EqFinderPause.FOCUS_LOST) {
                OutlinedButton(
                    onClick = onResumePlayback,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                ) {
                    Text(stringResource(R.string.eq_finder_resume_playback))
                }
            }
        }
    }

    Row(modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = onSuspend) {
            Text(stringResource(R.string.eq_finder_suspend))
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onFinishNow, enabled = ui.done > 0) {
            Text(stringResource(R.string.eq_finder_finish_now))
        }
    }
}

/**
 * 候補 1 枚。中身 (ゲイン) は出さず、鳴っている側の印だけを出す。
 * タップ = その候補を試聴 (何度でも切り替えられる)。
 */
@Composable
private fun RowScope.CandidateCard(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier
            .weight(1f)
            .height(132.dp),
    ) {
        Box(Modifier.fillMaxSize()) {
            Text(
                text = label,
                style = MaterialTheme.typography.displayMedium,
                modifier = Modifier.align(Alignment.Center),
            )
            if (selected) {
                Tag(
                    text = stringResource(R.string.eq_finder_now_playing),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun RowScope.AnswerButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.weight(1f)) {
        Text(text, textAlign = TextAlign.Center)
    }
}

// ---------------------------------------------------------------------------
// 結果
// ---------------------------------------------------------------------------

@Composable
internal fun EqFinderResultContent(
    ui: EqFinderResultUi,
    onApply: () -> Unit,
    onDiscard: () -> Unit,
    onSavePreset: (String) -> Unit,
    onDismissPresetOffer: () -> Unit,
) {
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }

    SettingsCard {
        EqFinderCurve(ui.beforeDb, ui.afterDb)
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LegendSwatch(MaterialTheme.colorScheme.onSurfaceVariant, stringResource(R.string.eq_finder_before))
            LegendSwatch(MaterialTheme.colorScheme.primary, stringResource(R.string.eq_finder_after))
        }
        RowDivider()
        val dbUnit = stringResource(R.string.eq_unit_db)
        ui.axes.forEach { axis ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(
                        when (axis.kind) {
                            EqFinderAxisKind.BASS -> R.string.eq_finder_axis_bass
                            EqFinderAxisKind.TREBLE -> R.string.eq_finder_axis_treble
                            EqFinderAxisKind.MID -> R.string.eq_finder_axis_mid
                        },
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = eqGainText(axis.deltaDb10, dbUnit),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }

    SettingsCard {
        NoticeRow(
            icon = if (ui.startBeaten == true) R.drawable.ic_check_circle else R.drawable.ic_info,
            text = stringResource(
                when (ui.startBeaten) {
                    true -> R.string.eq_finder_validated_win
                    false -> R.string.eq_finder_validated_lose
                    null -> R.string.eq_finder_not_validated
                },
            ),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 10.dp,
                bottom = if (ui.consistencyWarning) 4.dp else 10.dp,
            ),
        )
        if (ui.consistencyWarning) {
            NoticeRow(
                icon = R.drawable.ic_info,
                text = stringResource(R.string.eq_finder_consistency),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp),
            )
        }
    }

    if (ui.bakeFailed) {
        Text(
            text = stringResource(R.string.eq_finder_bake_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = { confirmDiscard = true }, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.eq_finder_discard))
        }
        Button(onClick = onApply, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.eq_finder_apply))
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.eq_finder_discard_title)) },
            text = { Text(stringResource(R.string.eq_finder_discard_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDiscard = false
                        onDiscard()
                    },
                ) {
                    Text(stringResource(R.string.eq_finder_discard), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (ui.presetOffer) {
        var name by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = onDismissPresetOffer,
            title = { Text(stringResource(R.string.eq_finder_preset_title)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.eq_preset_name)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = { onSavePreset(name) }, enabled = name.isNotBlank()) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissPresetOffer) {
                    Text(stringResource(R.string.eq_finder_preset_skip))
                }
            },
        )
    }
}

@Composable
private fun LegendSwatch(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(width = 16.dp, height = 3.dp).background(color))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

// ---------------------------------------------------------------------------
// 前後の曲線。EqCurve.kt には触らない約束なので、ここに小さな専用の絵を持つ
// (あちらは編集用でドラッグの受け皿が絡む。こちらは 2 本の応答を見せるだけ)。
// ---------------------------------------------------------------------------

/** 曲線の標本数。2 本を 1 回描くだけなので、編集画面 (毎フレーム) より粗くてよい。 */
internal const val EQ_FINDER_CURVE_SAMPLES = 120

private val CURVE_HEIGHT = 132.dp
private val CURVE_LABEL_GAP = 6.dp

// 対数軸の両端。可聴帯域 (EqCurve.kt と同じ値だが、契約ではない — あちらの軸が変わっても
// この絵は前後比較の絵として独立に成り立つ)。
private const val CURVE_LO_HZ = 20.0
private const val CURVE_HI_HZ = 20_000.0

private val CURVE_TICKS_HZ = listOf(100, 1_000, 10_000)

/** 応答を対数の周波数格子で標本化する。バンドが無ければ 0 dB の平ら。 */
internal fun eqFinderResponseDb(bands: List<EqBand>, samples: Int = EQ_FINDER_CURVE_SAMPLES): DoubleArray {
    val span = ln(CURVE_HI_HZ / CURVE_LO_HZ)
    return DoubleArray(samples) { i ->
        EqSolver.combinedResponseDb(bands, exp(ln(CURVE_LO_HZ) + span * i / (samples - 1)))
    }
}

/** 縦軸の段。両方の曲線が収まる最小の段を選ぶ。 */
internal fun eqFinderPlotRange(before: DoubleArray, after: DoubleArray): Double {
    val peak = max(
        before.maxOfOrNull { abs(it) } ?: 0.0,
        after.maxOfOrNull { abs(it) } ?: 0.0,
    )
    return listOf(12.0, 18.0, 24.0, 40.0).firstOrNull { peak <= it } ?: 40.0
}

@Composable
private fun EqFinderCurve(before: DoubleArray, after: DoubleArray) {
    val description = stringResource(R.string.eq_finder_result_curve_cd)
    val measurer = rememberTextMeasurer()
    val colors = MaterialTheme.colorScheme
    val labelStyle = MaterialTheme.typography.labelSmall.copy(
        // 軸のラベルは常に左→右で読む数字なので、RTL の段落から切り離す (EqCurve と同じ理由)。
        textDirection = TextDirection.Ltr,
        letterSpacing = 0.sp,
        color = colors.onSurfaceVariant,
    )
    val range = remember(before, after) { eqFinderPlotRange(before, after) }

    val dbUnit = stringResource(R.string.eq_unit_db)
    val kiloShort = stringResource(R.string.eq_unit_khz_short)
    val yTicks = remember(range, labelStyle, dbUnit) {
        listOf(range, 0.0, -range).map { db ->
            db to measurer.measure(eqGainTick(db.toInt(), if (db == range) dbUnit else null), labelStyle)
        }
    }
    val xTicks = remember(labelStyle, kiloShort) {
        CURVE_TICKS_HZ.map { hz ->
            val fraction = (ln(hz / CURVE_LO_HZ) / ln(CURVE_HI_HZ / CURVE_LO_HZ)).toFloat()
            fraction to measurer.measure(eqFrequencyShort(hz, kiloShort), labelStyle)
        }
    }
    val rowHeight = xTicks.maxOf { it.second.size.height }

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(CURVE_HEIGHT)
            .padding(horizontal = 16.dp)
            .semantics { contentDescription = description },
    ) {
        drawEqFinderCurves(
            before, after, range, yTicks, xTicks, rowHeight.toFloat(),
            accent = colors.primary,
            muted = colors.onSurfaceVariant,
        )
    }
}

/**
 * 前後の 2 本を描く。描くのはここだけで、composable 側は測って渡すだけ —
 * 分けてある理由は [drawGraphicPlot] と同じ (ビットマップへ流して画素で確かめるため)。
 */
@Suppress("LongParameterList")
internal fun DrawScope.drawEqFinderCurves(
    before: DoubleArray,
    after: DoubleArray,
    range: Double,
    yTicks: List<Pair<Double, TextLayoutResult>>,
    xTicks: List<Pair<Float, TextLayoutResult>>,
    rowHeight: Float,
    accent: Color,
    muted: Color,
) {
    val gap = CURVE_LABEL_GAP.toPx()
    val gutter = yTicks.maxOf { it.second.size.width } + gap
    val plot = Rect(gutter, 0f, size.width, size.height - rowHeight - gap)
    val yOf = { db: Double -> plot.top + ((1.0 - db / range) / 2.0).toFloat() * plot.height }

    yTicks.forEach { (db, layout) ->
        val y = yOf(db)
        drawLine(
            muted.copy(alpha = if (db == 0.0) 0.28f else 0.14f),
            Offset(plot.left, y),
            Offset(plot.right, y),
            1.dp.toPx(),
        )
        drawText(layout, topLeft = Offset(plot.left - gap - layout.size.width, y - layout.size.height / 2f))
    }

    val polyline = { series: DoubleArray ->
        Path().apply {
            for (i in series.indices) {
                val x = plot.left + plot.width * i / (series.size - 1f)
                val y = yOf(series[i])
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
    }
    // 前を下に細く、後を上に太く。どちらが結果かは太さと色の両方で分かるようにする。
    drawPath(polyline(before), muted, style = Stroke(width = 1.5.dp.toPx()))
    drawPath(polyline(after), accent, style = Stroke(width = 2.5.dp.toPx()))

    xTicks.forEach { (fraction, layout) ->
        val x = plot.left + plot.width * fraction
        drawText(
            layout,
            topLeft = Offset(
                (x - layout.size.width / 2f).coerceIn(0f, max(0f, size.width - layout.size.width)),
                size.height - rowHeight,
            ),
        )
    }
}

// ---------------------------------------------------------------------------

/**
 * 一節の位置の表示 ("3:41")。秒は必ず 2 桁 — `%02d` は Formatter がロケールの数字で
 * 埋めるので、固有数字のロケールでもそのまま通る。RTL では数字と区切りが入れ替わるので囲む。
 */
internal fun eqFinderTimeText(ms: Int): String {
    val totalSeconds = (ms.coerceAtLeast(0)) / 1_000
    return bidiIsolate(
        String.format(Locale.getDefault(), "%d:%02d", totalSeconds / 60, totalSeconds % 60),
    )
}
