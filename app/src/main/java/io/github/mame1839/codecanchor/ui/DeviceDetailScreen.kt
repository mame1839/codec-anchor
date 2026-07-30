package io.github.mame1839.codecanchor.ui

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.mame1839.codecanchor.R
import io.github.mame1839.codecanchor.core.ApplyOutcome
import io.github.mame1839.codecanchor.core.CodecKeys
import io.github.mame1839.codecanchor.core.DeviceProfile
import io.github.mame1839.codecanchor.core.DeviceStatus

private val DELAY_STEPS = listOf(0, 500, 1000, 1500, 2000, 3000, 5000, 8000)
private val RETRY_STEPS = listOf(1, 2, 3, 4, 5, 8, 10)
private val RETRY_DELAY_STEPS = listOf(500, 1000, 1500, 2000, 3000, 5000)

// ネイティブのダンプが書く品質モードの表記。
private const val ABR_MODE = "ABR"

// 実効ビットレートは ABR のときだけ動く。固定の音質では上のコーデック行と同じ値になるので出さないし、
// 取り直す意味もない。オフロードで読めなかったときはモードが分からないため、設定した音質で判断する。
private fun DeviceStatus.isLdacAbr(): Boolean =
    CodecKeys.isLdac(current?.displayName()) &&
        (ldacQualityMode.equals(ABR_MODE, ignoreCase = true) || current?.codecSpecific1 == CodecKeys.LDAC_ABR)

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

    val resources = LocalContext.current.resources
    val status = vm.statusOf(mac)
    val name = vm.nameOf(mac)
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }

    // 取り直しが要るのは動く値があるときだけ。固定の音質では開いていても問い合わせない。
    val watchBitrate = status?.connected == true && status.isLdacAbr()
    LifecycleResumeEffect(watchBitrate) {
        if (watchBitrate) vm.startWatching()
        onPauseOrDispose { vm.stopWatching() }
    }

    val applyMessage = when {
        vm.moduleState != ModuleState.ACTIVE -> stringResource(R.string.msg_apply_module_inactive)
        status?.connected != true -> stringResource(R.string.msg_apply_not_connected)
        else -> stringResource(R.string.msg_apply_requested)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name, maxLines = 1) },
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
            DeviceHeader(name = name, mac = mac, status = status, offloadEnabled = vm.a2dpOffloadEnabled)

            SettingsCard {
                SwitchRow(
                    title = stringResource(R.string.detail_auto_apply),
                    description = stringResource(R.string.detail_auto_apply_desc),
                    checked = profile.enabled,
                    onChange = { value -> vm.updateProfile(mac) { it.copy(enabled = value) } },
                )
            }

            TargetCard(vm = vm, mac = mac, profile = profile, status = status)

            SettingsCard {
                ExpandableHeader(
                    title = stringResource(R.string.section_advanced),
                    expanded = advancedExpanded,
                    onToggle = { advancedExpanded = !advancedExpanded },
                )
                if (advancedExpanded) {
                    RowDivider()
                    SwitchRow(
                        title = stringResource(R.string.adv_force),
                        description = stringResource(R.string.adv_force_desc),
                        checked = profile.force,
                        onChange = { value -> vm.updateProfile(mac) { it.copy(force = value) } },
                    )
                    SwitchRow(
                        title = stringResource(R.string.adv_via_sbc),
                        description = stringResource(R.string.adv_via_sbc_desc),
                        checked = profile.viaSbc,
                        onChange = { value -> vm.updateProfile(mac) { it.copy(viaSbc = value) } },
                    )
                    SwitchRow(
                        title = stringResource(R.string.adv_auto_hd),
                        description = stringResource(R.string.adv_auto_hd_desc),
                        checked = profile.autoEnableHd,
                        onChange = { value -> vm.updateProfile(mac) { it.copy(autoEnableHd = value) } },
                    )
                    RowDivider()
                    ChoiceRow(
                        title = stringResource(R.string.adv_delay),
                        options = numberOptions(DELAY_STEPS, profile.delayMs) { millisLabel(resources, it) },
                        selected = profile.delayMs,
                        onSelect = { value -> vm.updateProfile(mac) { it.copy(delayMs = value) } },
                        description = stringResource(R.string.adv_delay_desc),
                    )
                    ChoiceRow(
                        title = stringResource(R.string.adv_retries),
                        options = numberOptions(RETRY_STEPS, profile.retries) { retryLabel(resources, it) },
                        selected = profile.retries,
                        onSelect = { value -> vm.updateProfile(mac) { it.copy(retries = value) } },
                    )
                    ChoiceRow(
                        title = stringResource(R.string.adv_retry_delay),
                        options = numberOptions(RETRY_DELAY_STEPS, profile.retryDelayMs) {
                            millisLabel(resources, it)
                        },
                        selected = profile.retryDelayMs,
                        onSelect = { value -> vm.updateProfile(mac) { it.copy(retryDelayMs = value) } },
                    )
                }
            }

            Button(
                onClick = {
                    vm.applyNow(mac)
                    onNotify(applyMessage)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(painterResource(R.drawable.ic_bolt), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.action_apply_now))
            }

            OutlinedButton(
                onClick = { confirmDelete = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Icon(painterResource(R.drawable.ic_delete), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.action_delete_profile))
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_confirm_title)) },
            text = { Text(stringResource(R.string.delete_confirm_body, name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        vm.removeProfile(mac)
                        onBack()
                    },
                ) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun DeviceHeader(name: String, mac: String, status: DeviceStatus?, offloadEnabled: Boolean) {
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
                    Tag(stringResource(R.string.tag_connected))
                } else {
                    Tag(
                        text = stringResource(R.string.tag_disconnected),
                        container = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (status?.active == true) {
                    Tag(
                        text = stringResource(R.string.tag_active),
                        container = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            val unknown = stringResource(R.string.value_unknown)
            Text(
                text = stringResource(R.string.detail_current_codec, status?.current?.summary() ?: unknown),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (status != null && status.isLdacAbr()) {
                if (status.ldacBitrateKbps > 0) {
                    Text(
                        text = stringResource(
                            R.string.ldac_bitrate,
                            status.ldacBitrateKbps,
                            status.ldacQualityMode.ifBlank { unknown },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else if (offloadEnabled) {
                    Text(
                        text = stringResource(R.string.ldac_bitrate_offload),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val outcomeText = when (status?.outcome) {
                ApplyOutcome.APPLIED ->
                    stringResource(R.string.outcome_applied, status.outcomeValue.ifBlank { unknown })

                ApplyOutcome.FAILED ->
                    stringResource(R.string.outcome_failed, status.outcomeValue.ifBlank { unknown })

                ApplyOutcome.UNDECIDED -> stringResource(R.string.outcome_undecided)
                else -> null
            }
            if (outcomeText != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.detail_note, outcomeText),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (status != null && status.updatedAt > 0) {
                Text(
                    text = stringResource(R.string.detail_updated_at, clockLabel(LocalContext.current, status.updatedAt)),
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
    val resources = LocalContext.current.resources
    val keep = stringResource(R.string.value_keep)
    val selectable = status?.selectable.orEmpty()
    val connected = status?.connected == true

    val codecOptions = buildList {
        add(CodecKeys.KEEP_INT to keep)
        if (selectable.isNotEmpty()) {
            addAll(
                selectable.map { it.codecType to it.codecName.ifBlank { codecLabel(it.codecType, vm.codecNames) } }
                    .distinctBy { it.first },
            )
        } else {
            addAll(vm.codecNames.entries.sortedBy { it.key }.map { it.key to it.value })
        }
        if (profile.codecType != CodecKeys.KEEP_INT && none { it.first == profile.codecType }) {
            add(profile.codecType to codecLabel(profile.codecType, vm.codecNames))
        }
    }

    val capability = if (profile.codecType == CodecKeys.KEEP_INT) null else status?.capabilityOf(profile.codecType)
    // 行の有無が報告の到着で変わると、開いているダイアログが閉じたり下のボタンが動いたりする。
    // 判断材料は非同期に変わらない profile だけに限る。
    val ldacEnabled = profile.codecType == CodecKeys.KEEP_INT ||
        CodecKeys.isLdac(codecLabel(profile.codecType, vm.codecNames)) ||
        profile.codecSpecific1 != CodecKeys.KEEP_LONG

    SettingsCard(title = stringResource(R.string.section_target)) {
        // 未接続なら「読めなかった」ではなく「まだ読めない」ので、警告にはしない。
        if (selectable.isEmpty()) {
            NoticeRow(
                icon = if (connected) R.drawable.ic_warning else R.drawable.ic_bluetooth,
                text = if (connected) {
                    stringResource(R.string.target_no_capability)
                } else {
                    stringResource(R.string.target_not_connected)
                },
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        ChoiceRow(
            title = stringResource(R.string.label_codec),
            options = codecOptions,
            selected = profile.codecType,
            onSelect = { value -> vm.updateProfile(mac) { it.copy(codecType = value) } },
        )
        ChoiceRow(
            title = stringResource(R.string.label_sample_rate),
            options = maskOptions(CodecKeys.SAMPLE_RATES, capability?.sampleRate ?: 0, profile.sampleRate, keep),
            selected = profile.sampleRate,
            onSelect = { value -> vm.updateProfile(mac) { it.copy(sampleRate = value) } },
        )
        ChoiceRow(
            title = stringResource(R.string.label_bit_depth),
            options = maskOptions(CodecKeys.BIT_DEPTHS, capability?.bitsPerSample ?: 0, profile.bitsPerSample, keep),
            selected = profile.bitsPerSample,
            onSelect = { value -> vm.updateProfile(mac) { it.copy(bitsPerSample = value) } },
        )
        ChoiceRow(
            title = stringResource(R.string.label_channel),
            options = maskOptions(channelModes(resources), capability?.channelMode ?: 0, profile.channelMode, keep),
            selected = profile.channelMode,
            onSelect = { value -> vm.updateProfile(mac) { it.copy(channelMode = value) } },
        )
        ChoiceRow(
            title = stringResource(R.string.label_ldac_quality),
            options = ldacOptions(resources, profile.codecSpecific1, keep),
            selected = profile.codecSpecific1,
            onSelect = { value -> vm.updateProfile(mac) { it.copy(codecSpecific1 = value) } },
            enabled = ldacEnabled,
        )
    }
}

private fun maskOptions(
    table: List<Pair<Int, String>>,
    capability: Int,
    selected: Int,
    keepLabel: String,
): List<Pair<Int, String>> {
    val allowed = CodecKeys.options(table, capability).map { it.first }.toMutableSet()
    if (selected != CodecKeys.KEEP_MASK) allowed += selected
    return listOf(CodecKeys.KEEP_MASK to keepLabel) + table.filter { it.first in allowed }
}

private fun ldacOptions(res: Resources, selected: Long, keepLabel: String): List<Pair<Long, String>> = buildList {
    add(CodecKeys.KEEP_LONG to keepLabel)
    CodecKeys.LDAC_QUALITIES.forEach { (value, label) -> add(value to ldacQualityLabel(res, value, label)) }
    if (selected != CodecKeys.KEEP_LONG && none { it.first == selected }) {
        add(selected to CodecKeys.ldacQualityLabel(selected))
    }
}

private fun numberOptions(steps: List<Int>, selected: Int, label: (Int) -> String): List<Pair<Int, String>> {
    val values = if (selected in steps) steps else (steps + selected).sorted()
    return values.map { it to label(it) }
}
