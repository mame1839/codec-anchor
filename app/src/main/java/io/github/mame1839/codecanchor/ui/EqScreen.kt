package io.github.mame1839.codecanchor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mame1839.codecanchor.R

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
                title = {
                    // どのイヤホンを編集しているかは、ここにしか出ない (下は EQ の値だけ)。
                    Column {
                        Text(stringResource(R.string.section_eq), maxLines = 1)
                        Text(
                            text = vm.nameOf(mac),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                },
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
        val layoutDirection = LocalLayoutDirection.current
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(
                start = inner.calculateStartPadding(layoutDirection) + 16.dp,
                end = inner.calculateEndPadding(layoutDirection) + 16.dp,
                top = inner.calculateTopPadding() + 8.dp,
                bottom = inner.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            EqSection(vm = vm, mac = mac, profile = profile)
        }
    }
}
