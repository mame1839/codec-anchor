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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mame1839.codecanchor.R
import io.github.mame1839.codecanchor.core.DeviceProfile
import io.github.mame1839.codecanchor.core.DeviceStatus

/**
 * 機器タブ。**イヤホンの一覧と、一覧が使えない理由だけ**を置く。
 *
 * 権限と Bluetooth の注意をここに残しているのは、どちらも**一覧が空である理由そのもの**だから。
 * 状態タブへ移すと、空の一覧だけを見せて理由は別のタブ、という形になる。
 *
 * アプリ全体の設定は [SettingsTab]、端末側の事情は [StatusTab] が持つ。
 */
@Composable
fun DeviceListTab(
    vm: MainViewModel,
    contentPadding: PaddingValues,
    onOpenDevice: (String) -> Unit,
    onRequestPermission: () -> Unit,
) {
    val audioRows = vm.bondedRows.filter { it.audio }
    val otherRows = vm.bondedRows.filterNot { it.audio }
    val orphanRows = vm.orphanRows
    var othersExpanded by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = screenPadding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!vm.connectGranted) {
            item {
                SettingsCard(container = MaterialTheme.colorScheme.tertiaryContainer) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(
                            text = stringResource(R.string.permission_title),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.permission_body),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = onRequestPermission) {
                            Text(stringResource(R.string.action_grant))
                        }
                    }
                }
            }
        }

        if (!vm.bluetoothOn) {
            item {
                SettingsCard(container = MaterialTheme.colorScheme.surfaceContainerHighest) {
                    NoticeRow(
                        icon = R.drawable.ic_bluetooth,
                        text = stringResource(R.string.bluetooth_off),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        }

        item { SectionHeader(stringResource(R.string.section_audio_devices)) }

        if (audioRows.isEmpty()) {
            item {
                SettingsCard {
                    Text(
                        text = when {
                            !vm.connectGranted -> stringResource(R.string.devices_no_permission)
                            !vm.bluetoothOn -> stringResource(R.string.devices_bluetooth_off)
                            else -> stringResource(R.string.devices_empty)
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
                        title = stringResource(R.string.section_other_devices, otherRows.size),
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
            item { SectionHeader(stringResource(R.string.section_orphan_profiles)) }
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

@Composable
private fun DeviceCard(
    row: DeviceRow,
    profile: DeviceProfile?,
    status: DeviceStatus?,
    codecNames: Map<Int, String>,
    onClick: () -> Unit,
) {
    val resources = LocalContext.current.resources
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
                    text = bidiIsolate(row.mac),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (status?.connected == true) Tag(stringResource(R.string.tag_connected))
                    if (status?.active == true) {
                        Tag(
                            text = stringResource(R.string.tag_active),
                            container = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    if (profile == null) {
                        Tag(
                            text = stringResource(R.string.tag_no_profile),
                            container = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (!profile.enabled) {
                        Tag(
                            text = stringResource(R.string.tag_profile_off),
                            container = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!row.bonded) {
                        Tag(
                            text = stringResource(R.string.tag_not_found),
                            container = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (profile != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(
                            R.string.device_target,
                            profileSummary(resources, profile, codecNames),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                status?.current?.let { current ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.device_current, codecSummary(current)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
