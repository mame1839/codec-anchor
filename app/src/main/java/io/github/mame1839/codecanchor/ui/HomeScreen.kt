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

enum class HomeTab { DEVICES, SETTINGS, STATUS }

private val HomeTab.labelRes: Int
    get() = when (this) {
        HomeTab.DEVICES -> R.string.tab_devices
        HomeTab.SETTINGS -> R.string.tab_settings
        HomeTab.STATUS -> R.string.tab_status
    }

private val HomeTab.iconRes: Int
    get() = when (this) {
        HomeTab.DEVICES -> R.drawable.ic_headphones
        HomeTab.SETTINGS -> R.drawable.ic_settings
        HomeTab.STATUS -> R.drawable.ic_monitor_heart
    }

internal fun needsAttention(moduleState: ModuleState, configBroken: Boolean): Boolean =
    moduleState == ModuleState.INACTIVE || configBroken

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
    val stateHolder = rememberSaveableStateHolder()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val title = if (tab == HomeTab.DEVICES) R.string.app_name else tab.labelRes
                    Text(stringResource(title), maxLines = 1)
                },
                actions = {
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
            val label = stringResource(entry.labelRes)
            val icon = entry.iconRes
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
