package io.github.mame1839.codecanchor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mame1839.codecanchor.R

/** 2 行の題のまわりに要る余白 (既定の文字サイズで測って決めた値。EqScreenTitleTest)。 */
private val EQ_TITLE_BAR_PADDING = 36.dp

/**
 * 題を 2 行 (音響処理 + イヤホン名) にしたときのバーの高さ。機序・実測は ui-notes.md §1。
 *
 * ⚠️ dp の定数で決めない — 端末の文字サイズを上げると 2 行目がはみ出す。lineHeight (sp) から
 * toDp() で毎回組み立てること。⚠️ Typography の lineHeight は sp のままにする (em だと落ちる)。
 */
internal val eqTitleBarHeight: Dp
    @Composable get() = with(LocalDensity.current) {
        MaterialTheme.typography.titleMedium.lineHeight.toDp() +
            MaterialTheme.typography.bodySmall.lineHeight.toDp() +
            EQ_TITLE_BAR_PADDING
    }

/**
 * 音響処理の画面の題 (2 行目はイヤホン名、ここにしか出ない)。1 行目を titleMedium にしている
 * 理由と internal (private でない) の理由は ui-notes.md §1 — EqScreenTitleTest が同じ
 * Composable を組んで高さを測る。
 */
@Composable
internal fun EqScreenTitle(name: String) {
    Column {
        Text(
            text = stringResource(R.string.section_eq),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
        )
        Text(
            text = name,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 音響処理の画面。詳細画面の行 (`EqSummaryCard`) から開く。詳細画面の中に置くと縦に長すぎるので
 * 分けてある。遷移の状態は `MainActivity` 側が持つ (`BackHandler` を 1 つに保つため)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqScreen(
    vm: MainViewModel,
    mac: String,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onOpenFinder: () -> Unit,
) {
    val profile = vm.config.profileFor(mac)
    // 機器の設定を消したときは、この画面が組まれたまま参照先だけが消える。詳細画面と同じ形で戻す。
    if (profile == null) {
        LaunchedEffect(mac) { onBack() }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                // 題を自前で 2 行積んでいる理由と、高さを渡す理由は eqTitleBarHeight にある。
                title = { EqScreenTitle(vm.nameOf(mac)) },
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
            EqSection(vm = vm, mac = mac, profile = profile)
            EqFinderEntryCard(vm = vm, mac = mac, onOpen = onOpenFinder)
        }
    }
}
