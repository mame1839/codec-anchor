package io.github.mame1839.codecanchor.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.mame1839.codecanchor.R

/**
 * 最上位の行き先。**並びがそのまま下部ナビの並び**で、先頭が最初に開くタブ。
 *
 * 機器の詳細と音響処理はここに入れない。**下部ナビは「並列に行き来する行き先」**で、
 * 1 台のイヤホンを開いている最中に別のタブへ跳べると、戻ったときどの機器を見ていたのかが
 * 決められない。詳細と音響処理は今までどおり上に乗せる (`AppNavigation`)。
 */
enum class HomeTab { DEVICES, SETTINGS, STATUS }

/**
 * 状態タブに印を出す条件。
 *
 * **説明の実体は状態タブに 1 つだけ置き、ここは印だけを上げる。**両方に文言を置くと、
 * 片方だけ直したときに黙って食い違う。
 *
 * 拾うのは**アプリが何もできない状態だけ。**モジュールが無効なら適用が 1 件も起きず、
 * 設定が壊れていれば読めた設定が無い。オフロードや版の食い違いは**動いてはいる**ので
 * ここには上げない — 常時点いている印は、点いていることに意味が無くなる。
 *
 * private ではなく internal なのは、条件を固定するテストから直接呼ぶため。
 */
internal fun needsAttention(moduleState: ModuleState, configBroken: Boolean): Boolean =
    moduleState == ModuleState.INACTIVE || configBroken

/**
 * 下部ナビを持つ最上位の画面。
 *
 * **`BackHandler` はここには置かない。**タブを含む画面の位置は [AppNavigation] が平らに持っていて、
 * 戻るの分岐もあちらが 1 箇所で決める。ここに足すと詳細画面を開いている間も生き残り、
 * どちらが先に呼ばれるかが composition の深さで決まる。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: MainViewModel,
    tab: HomeTab,
    onSelectTab: (HomeTab) -> Unit,
    snackbarHostState: SnackbarHostState,
    onOpenDevice: (String) -> Unit,
    onRequestPermission: () -> Unit,
    onNotify: (String) -> Unit,
) {
    val refreshLabel = stringResource(R.string.cd_refresh_status)
    // タブを切り替えても、開いた折りたたみとスクロールの位置を残す。持たないと、
    // 設定を見て戻るたびに機器の一覧が先頭へ跳ぶ。
    val stateHolder = rememberSaveableStateHolder()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (tab) {
                            HomeTab.DEVICES -> stringResource(R.string.app_name)
                            HomeTab.SETTINGS -> stringResource(R.string.tab_settings)
                            HomeTab.STATUS -> stringResource(R.string.tab_status)
                        },
                        maxLines = 1,
                    )
                },
                actions = {
                    // 取り直しが目に見えるのは機器タブだけ。状態タブは自前の「再確認」を持っている。
                    if (tab == HomeTab.DEVICES) {
                        IconButton(
                            onClick = { vm.refresh() },
                            enabled = !vm.probing,
                            modifier = Modifier.semantics { contentDescription = refreshLabel },
                        ) {
                            if (vm.probing) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(painter = painterResource(R.drawable.ic_refresh), contentDescription = null)
                            }
                        }
                    }
                },
            )
        },
        bottomBar = { HomeNavigationBar(vm = vm, tab = tab, onSelectTab = onSelectTab) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { inner ->
        // タブごとに覚えておく箱を分ける。キーが同じだと別のタブの状態を引き継いでしまう。
        stateHolder.SaveableStateProvider(tab) {
            when (tab) {
                HomeTab.DEVICES -> DeviceListTab(
                    vm = vm,
                    contentPadding = inner,
                    onOpenDevice = onOpenDevice,
                    onRequestPermission = onRequestPermission,
                )

                HomeTab.SETTINGS -> SettingsTab(vm = vm, contentPadding = inner)

                HomeTab.STATUS -> StatusTab(vm = vm, contentPadding = inner, onNotify = onNotify)
            }
        }
    }
}

@Composable
private fun HomeNavigationBar(vm: MainViewModel, tab: HomeTab, onSelectTab: (HomeTab) -> Unit) {
    val attention = needsAttention(vm.moduleState, vm.configBroken)
    val attentionLabel = stringResource(R.string.cd_needs_attention)

    NavigationBar {
        HomeTab.entries.forEach { entry ->
            val label = stringResource(
                when (entry) {
                    HomeTab.DEVICES -> R.string.tab_devices
                    HomeTab.SETTINGS -> R.string.tab_settings
                    HomeTab.STATUS -> R.string.tab_status
                },
            )
            val icon = when (entry) {
                HomeTab.DEVICES -> R.drawable.ic_headphones
                HomeTab.SETTINGS -> R.drawable.ic_settings
                HomeTab.STATUS -> R.drawable.ic_monitor_heart
            }
            NavigationBarItem(
                selected = tab == entry,
                onClick = { onSelectTab(entry) },
                icon = {
                    if (entry == HomeTab.STATUS && attention) {
                        BadgedBox(
                            badge = { Badge(modifier = Modifier.semantics { contentDescription = attentionLabel }) },
                        ) {
                            Icon(painter = painterResource(icon), contentDescription = null)
                        }
                    } else {
                        Icon(painter = painterResource(icon), contentDescription = null)
                    }
                },
                label = { Text(label, maxLines = 1) },
            )
        }
    }
}
