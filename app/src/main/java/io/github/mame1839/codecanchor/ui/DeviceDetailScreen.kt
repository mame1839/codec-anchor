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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.mame1839.codecanchor.R
import io.github.mame1839.codecanchor.core.CodecKeys
import io.github.mame1839.codecanchor.core.DeviceProfile
import io.github.mame1839.codecanchor.core.DeviceStatus

private val DELAY_STEPS = listOf(0, 500, 1000, 1500, 2000, 3000, 5000, 8000)
private val RETRY_STEPS = listOf(1, 2, 3, 4, 5, 8, 10)
private val RETRY_DELAY_STEPS = listOf(500, 1000, 1500, 2000, 3000, 5000)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDetailScreen(
    vm: MainViewModel,
    mac: String,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onNotify: (String) -> Unit,
) {
    val profile = vm.config.profileFor(mac)
    if (profile == null) {
        LaunchedEffect(mac) { onBack() }
        return
    }

    val status = vm.statusOf(mac)
    val name = vm.nameOf(mac)
    var confirmDelete by remember { mutableStateOf(false) }
    var advancedExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "戻る")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { inner ->
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(
                start = 16.dp,
                end = 16.dp,
                top = inner.calculateTopPadding() + 8.dp,
                bottom = inner.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DeviceHeader(name = name, mac = mac, status = status)

            SettingsCard {
                SwitchRow(
                    title = "このイヤホンに自動適用する",
                    description = "接続したときに、下の設定へ切り替えます",
                    checked = profile.enabled,
                    onChange = { value -> vm.updateProfile(mac) { it.copy(enabled = value) } },
                )
            }

            TargetCard(vm = vm, mac = mac, profile = profile, status = status)

            SettingsCard {
                ExpandableHeader(
                    title = "詳細設定",
                    expanded = advancedExpanded,
                    onToggle = { advancedExpanded = !advancedExpanded },
                )
                if (advancedExpanded) {
                    RowDivider()
                    SwitchRow(
                        title = "強制適用",
                        description = "機器が対応を申告していない組み合わせも試します",
                        checked = profile.force,
                        onChange = { value -> vm.updateProfile(mac) { it.copy(force = value) } },
                    )
                    SwitchRow(
                        title = "SBC を経由して切り替える",
                        description = "いったん SBC に落としてから目的のコーデックにします",
                        checked = profile.viaSbc,
                        onChange = { value -> vm.updateProfile(mac) { it.copy(viaSbc = value) } },
                    )
                    SwitchRow(
                        title = "HD オーディオを自動で有効化",
                        description = "システム側で HD オーディオがオフのときにオンにします",
                        checked = profile.autoEnableHd,
                        onChange = { value -> vm.updateProfile(mac) { it.copy(autoEnableHd = value) } },
                    )
                    RowDivider()
                    ChoiceRow(
                        title = "適用までの待ち時間",
                        options = numberOptions(DELAY_STEPS, profile.delayMs, ::millisLabel),
                        selected = profile.delayMs,
                        onSelect = { value -> vm.updateProfile(mac) { it.copy(delayMs = value) } },
                        description = "接続直後は切り替えを受け付けない機器があります",
                    )
                    ChoiceRow(
                        title = "リトライ回数",
                        options = numberOptions(RETRY_STEPS, profile.retries) { "$it 回" },
                        selected = profile.retries,
                        onSelect = { value -> vm.updateProfile(mac) { it.copy(retries = value) } },
                    )
                    ChoiceRow(
                        title = "リトライ間隔",
                        options = numberOptions(RETRY_DELAY_STEPS, profile.retryDelayMs, ::millisLabel),
                        selected = profile.retryDelayMs,
                        onSelect = { value -> vm.updateProfile(mac) { it.copy(retryDelayMs = value) } },
                    )
                }
            }

            Button(
                onClick = {
                    vm.applyNow(mac)
                    onNotify(
                        when {
                            vm.moduleState != ModuleState.ACTIVE -> "モジュールが動いていないため届きません"
                            status?.connected != true -> "機器が接続されていないため、いまは適用できません"
                            else -> "適用を要求しました"
                        }
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(painterResource(R.drawable.ic_bolt), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("今すぐ適用")
            }

            OutlinedButton(
                onClick = { confirmDelete = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Icon(painterResource(R.drawable.ic_delete), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("この機器の設定を削除")
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("設定を削除しますか") },
            text = { Text("$name の設定を削除します。この機器には何もしなくなります。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        vm.removeProfile(mac)
                        onBack()
                    },
                ) {
                    Text("削除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("やめる") }
            },
        )
    }
}

@Composable
private fun DeviceHeader(name: String, mac: String, status: DeviceStatus?) {
    SettingsCard {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            Text(
                text = mac,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (status?.connected == true) {
                    Tag("接続中")
                } else {
                    Tag(
                        text = "未接続",
                        container = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (status?.active == true) {
                    Tag(
                        text = "再生中",
                        container = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "現在のコーデック: ${status?.current?.summary() ?: "不明"}",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (status != null && status.note.isNotBlank()) {
                Text(
                    text = "直近の結果: ${status.note}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (status != null && status.updatedAt > 0) {
                Text(
                    text = "${clockLabel(status.updatedAt)} 時点",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TargetCard(
    vm: MainViewModel,
    mac: String,
    profile: DeviceProfile,
    status: DeviceStatus?,
) {
    val selectable = status?.selectable.orEmpty()
    val codecOptions = buildList {
        add(CodecKeys.KEEP_INT to "変更しない")
        if (selectable.isNotEmpty()) {
            addAll(selectable.map { it.codecType to it.codecName }.distinctBy { it.first })
        } else {
            addAll(vm.codecNames.entries.sortedBy { it.key }.map { it.key to it.value })
        }
        if (profile.codecType != CodecKeys.KEEP_INT && none { it.first == profile.codecType }) {
            add(profile.codecType to codecLabel(profile.codecType, vm.codecNames))
        }
    }

    val capability = if (profile.codecType == CodecKeys.KEEP_INT) null else status?.capabilityOf(profile.codecType)
    val codecName = if (profile.codecType == CodecKeys.KEEP_INT) {
        status?.current?.codecName
    } else {
        codecOptions.firstOrNull { it.first == profile.codecType }?.second
    }
    val showLdac = CodecKeys.isLdac(codecName) || profile.codecSpecific1 != CodecKeys.KEEP_LONG

    SettingsCard(title = "固定する内容") {
        if (selectable.isEmpty()) {
            NoticeRow(
                icon = R.drawable.ic_warning,
                text = "機器から選べる組み合わせを取得できていないため、すべての候補を表示しています。",
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        ChoiceRow(
            title = "コーデック",
            options = codecOptions,
            selected = profile.codecType,
            onSelect = { value -> vm.updateProfile(mac) { it.copy(codecType = value) } },
        )
        ChoiceRow(
            title = "サンプルレート",
            options = maskOptions(CodecKeys.SAMPLE_RATES, capability?.sampleRate ?: 0, profile.sampleRate),
            selected = profile.sampleRate,
            onSelect = { value -> vm.updateProfile(mac) { it.copy(sampleRate = value) } },
        )
        ChoiceRow(
            title = "ビット深度",
            options = maskOptions(CodecKeys.BIT_DEPTHS, capability?.bitsPerSample ?: 0, profile.bitsPerSample),
            selected = profile.bitsPerSample,
            onSelect = { value -> vm.updateProfile(mac) { it.copy(bitsPerSample = value) } },
        )
        ChoiceRow(
            title = "チャンネル",
            options = maskOptions(CodecKeys.CHANNEL_MODES, capability?.channelMode ?: 0, profile.channelMode),
            selected = profile.channelMode,
            onSelect = { value -> vm.updateProfile(mac) { it.copy(channelMode = value) } },
        )
        if (showLdac) {
            ChoiceRow(
                title = "LDAC 音質",
                options = ldacOptions(profile.codecSpecific1),
                selected = profile.codecSpecific1,
                onSelect = { value -> vm.updateProfile(mac) { it.copy(codecSpecific1 = value) } },
            )
        }
    }
}

private fun maskOptions(table: List<Pair<Int, String>>, capability: Int, selected: Int): List<Pair<Int, String>> {
    val allowed = CodecKeys.options(table, capability).map { it.first }.toMutableSet()
    if (selected != CodecKeys.KEEP_MASK) allowed += selected
    return listOf(CodecKeys.KEEP_MASK to "変更しない") + table.filter { it.first in allowed }
}

private fun ldacOptions(selected: Long): List<Pair<Long, String>> = buildList {
    add(CodecKeys.KEEP_LONG to "変更しない")
    addAll(CodecKeys.LDAC_QUALITIES)
    if (selected != CodecKeys.KEEP_LONG && none { it.first == selected }) {
        add(selected to CodecKeys.ldacQualityLabel(selected))
    }
}

private fun numberOptions(steps: List<Int>, selected: Int, label: (Int) -> String): List<Pair<Int, String>> {
    val values = if (selected in steps) steps else (steps + selected).sorted()
    return values.map { it to label(it) }
}
