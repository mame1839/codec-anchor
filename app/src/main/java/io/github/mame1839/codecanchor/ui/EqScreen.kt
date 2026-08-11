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

/**
 * 2 行の題のまわりに要る余白。バーの高さから行の高さを引いた残り。
 *
 * **既定の文字サイズで測って決めた値** (`EqScreenTitleTest`)。行の高さのほうは下で足す。
 */
private val EQ_TITLE_BAR_PADDING = 36.dp

/**
 * 題を 2 行 (音響処理 + イヤホン名) にしたときのバーの高さ。
 *
 * **既定の 64.dp は 1 行ぶんしか無く、2 行を積むと下の行が切れる。**
 *
 * **⚠️ dp の定数で決めてはいけない。**バーの高さは dp、中身の行の高さは sp なので、
 * 端末の文字サイズを上げると**必ずどこかで 2 行目がはみ出す** (76.dp 固定にしていたときは
 * 文字サイズ 200% で 2dp はみ出していた)。だから**いまの行の高さから毎回組み立てる。**
 * `lineHeight` は sp なので、`toDp()` が端末の文字サイズを織り込んでくれる。
 *
 * `EqScreenTitleTest` が文字サイズ 100% / 130% / 200% で実際に測って固定している。
 */
internal val eqTitleBarHeight: Dp
    @Composable get() = with(LocalDensity.current) {
        MaterialTheme.typography.titleMedium.lineHeight.toDp() +
            MaterialTheme.typography.bodySmall.lineHeight.toDp() +
            EQ_TITLE_BAR_PADDING
    }

/**
 * 音響処理の画面の題。**2 行目はどのイヤホンを編集しているか**で、この画面ではここにしか出ない
 * (下は EQ の値だけ)。
 *
 * **1 行目に `titleMedium` を当てているのは 2 行を収めるため。**`TopAppBar` の既定は
 * `titleLarge` (22sp) で、2 行にするとバーが縦に伸びすぎる。詳細画面の題より一段小さくなるのは
 * その代償で、意図したもの。
 *
 * private ではなく internal なのは、**高さを測るテストが同じものを組むため。**
 * テスト側に同じ見た目を書き写すと、片方だけ直したときに測っているものが実物とずれる。
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
 * 音響処理の画面。詳細画面の行 (`EqSummaryCard`) から開く。
 *
 * 詳細画面の中に置くと縦に長すぎるので分けてある。**遷移の状態を持つのは `MainActivity` 側**で、
 * ここは開かれている間だけ組まれる (`BackHandler` を 1 つに保つため。理由は `MainActivity` に書いてある)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqScreen(
    vm: MainViewModel,
    mac: String,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
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
                // どのイヤホンを編集しているかは、ここにしか出ない (下は EQ の値だけ)。
                //
                // **⚠️ Material3 1.4.0 の `subtitle` 付き `TopAppBar` は internal なので使えない。**
                // (JVM の署名は public に見えるが Kotlin の可視性が internal。バイトコードだけ見ると
                // 使えると誤読する。) 自前で積むしかないので、**高さを明示して詰まりを防ぐ。**
                // 既定の 64.dp は 1 行ぶんで、2 行だと下が切れる。
                // 値は `EqScreenTitleTest` が測って固定している。
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
        }
    }
}
