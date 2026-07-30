package io.github.mame1839.codecanchor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mame1839.codecanchor.R
import io.github.mame1839.codecanchor.core.DeviceProfile
import io.github.mame1839.codecanchor.core.DeviceStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceListScreen(
    vm: MainViewModel,
    snackbarHostState: SnackbarHostState,
    onOpenDevice: (String) -> Unit,
    onRequestPermission: () -> Unit,
    onNotify: (String) -> Unit,
) {
    val audioRows = vm.bondedRows.filter { it.audio }
    val otherRows = vm.bondedRows.filterNot { it.audio }
    val orphanRows = vm.orphanRows
    var othersExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Codec Anchor") },
                actions = {
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(painterResource(R.drawable.ic_refresh), contentDescription = "状態を再確認")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = inner.calculateTopPadding() + 8.dp,
                bottom = inner.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ModuleCard(
                    vm = vm,
                    onPush = {
                        vm.pushConfig()
                        onNotify("設定を送り直しました")
                    },
                )
            }

            item {
                SettingsCard(title = "全体設定") {
                    SwitchRow(
                        title = "自動適用を有効にする",
                        description = "オフにすると、すべての機器で何もしません",
                        checked = vm.config.enabled,
                        onChange = { value -> vm.update { it.copy(enabled = value) } },
                    )
                    SwitchRow(
                        title = "他アプリによる変更を上書きする",
                        description = "システムや他アプリがコーデックを変えたら、設定した内容に戻します",
                        checked = vm.config.enforce,
                        onChange = { value -> vm.update { it.copy(enforce = value) } },
                        enabled = vm.config.enabled,
                    )
                    SwitchRow(
                        title = "詳細ログを出す",
                        description = "うまく切り替わらないときの調査用",
                        checked = vm.config.verbose,
                        onChange = { value -> vm.update { it.copy(verbose = value) } },
                    )
                }
            }

            if (!vm.connectGranted) {
                item {
                    SettingsCard(container = MaterialTheme.colorScheme.tertiaryContainer) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text("Bluetooth の権限が必要です", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "許可すると、ペアリング済みの機器と名前を読み取れます。",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(10.dp))
                            Button(onClick = onRequestPermission) { Text("権限を許可する") }
                        }
                    }
                }
            }

            if (!vm.bluetoothOn) {
                item {
                    SettingsCard(container = MaterialTheme.colorScheme.surfaceContainerHighest) {
                        NoticeRow(
                            icon = R.drawable.ic_bluetooth,
                            text = "Bluetooth がオフです。オンにするとペアリング済みの機器が表示されます。",
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }
            }

            item { SectionHeader("オーディオ機器") }

            if (audioRows.isEmpty()) {
                item {
                    SettingsCard {
                        Text(
                            text = if (vm.connectGranted) {
                                "ペアリング済みのオーディオ機器が見つかりません。"
                            } else {
                                "権限がないため機器を読み取れません。"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }
            } else {
                items(audioRows, key = { it.mac }) { row ->
                    DeviceCard(
                        row = row,
                        profile = vm.config.profileFor(row.mac),
                        status = vm.statusOf(row.mac),
                        codecNames = vm.codecNames,
                        onClick = { onOpenDevice(row.mac) },
                    )
                }
            }

            if (otherRows.isNotEmpty()) {
                item {
                    SettingsCard {
                        ExpandableHeader(
                            title = "その他の機器 (${otherRows.size})",
                            expanded = othersExpanded,
                            onToggle = { othersExpanded = !othersExpanded },
                        )
                    }
                }
                if (othersExpanded) {
                    items(otherRows, key = { it.mac }) { row ->
                        DeviceCard(
                            row = row,
                            profile = vm.config.profileFor(row.mac),
                            status = vm.statusOf(row.mac),
                            codecNames = vm.codecNames,
                            onClick = { onOpenDevice(row.mac) },
                        )
                    }
                }
            }

            if (orphanRows.isNotEmpty()) {
                item { SectionHeader("ペアリング一覧に無い設定") }
                items(orphanRows, key = { it.mac }) { row ->
                    DeviceCard(
                        row = row,
                        profile = vm.config.profileFor(row.mac),
                        status = vm.statusOf(row.mac),
                        codecNames = vm.codecNames,
                        onClick = { onOpenDevice(row.mac) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ModuleCard(vm: MainViewModel, onPush: () -> Unit) {
    val report = vm.report
    val container = when (vm.moduleState) {
        ModuleState.INACTIVE -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }
    SettingsCard(container = container) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            when (vm.moduleState) {
                ModuleState.CHECKING -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(12.dp))
                        Text("モジュールの状態を確認しています", style = MaterialTheme.typography.titleSmall)
                    }
                }

                ModuleState.ACTIVE -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(R.drawable.ic_check_circle),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.size(10.dp))
                        Text("モジュールは動作中", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "バージョン ${report?.moduleVersion.orEmpty().ifBlank { "?" }} · " +
                            "注入先 ${report?.hostPackage.orEmpty().ifBlank { "?" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "最終報告 ${clockLabel(report?.timestamp ?: 0L)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    when {
                        report?.configLoaded != true -> SyncLine("モジュールはまだ設定を読み込んでいません", onPush)
                        vm.configSynced -> Text(
                            text = "設定は同期済み",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        else -> SyncLine("編集した内容がモジュールに届いていません", onPush)
                    }
                }

                ModuleState.INACTIVE -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(R.drawable.ic_warning),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.size(10.dp))
                        Text("モジュールが動いていません", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Bluetooth プロセスから応答がありません。次を確認してください。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    listOf(
                        "1. Vector で Codec Anchor を有効にする",
                        "2. スコープに Bluetooth を追加する",
                        "3. Bluetooth をオフにして、もう一度オンにする",
                    ).forEach { step ->
                        Text(step, style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "この状態でも設定の編集と保存はできます。モジュールが動き出したときに反映されます。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = { vm.requestStatus() }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text("もう一度確認する")
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncLine(message: String, onPush: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onPush, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("送り直す") }
    }
}

@Composable
private fun DeviceCard(
    row: DeviceRow,
    profile: DeviceProfile?,
    status: DeviceStatus?,
    codecNames: Map<Int, String>,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                painter = painterResource(if (row.audio) R.drawable.ic_headphones else R.drawable.ic_bluetooth),
                contentDescription = null,
                tint = if (status?.connected == true) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = row.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = row.mac,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (status?.connected == true) Tag("接続中")
                    if (status?.active == true) {
                        Tag(
                            text = "再生中",
                            container = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    if (profile == null) {
                        Tag(
                            text = "設定なし",
                            container = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (!profile.enabled) {
                        Tag(
                            text = "自動適用オフ",
                            container = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!row.bonded) {
                        Tag(
                            text = "未検出",
                            container = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (profile != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "固定: ${profileSummary(profile, codecNames)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                status?.current?.let { current ->
                    Text(
                        text = "現在: ${current.summary()}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
