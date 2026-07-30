package io.github.mame1839.codecanchor.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mame1839.codecanchor.R
import io.github.mame1839.codecanchor.core.DeviceProfile
import io.github.mame1839.codecanchor.core.DeviceStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    var othersExpanded by rememberSaveable { mutableStateOf(false) }
    val pushedMessage = stringResource(R.string.msg_config_pushed)
    val refreshLabel = stringResource(R.string.cd_refresh_status)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
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
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { inner ->
        val layoutDirection = LocalLayoutDirection.current
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = inner.calculateStartPadding(layoutDirection) + 16.dp,
                end = inner.calculateEndPadding(layoutDirection) + 16.dp,
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
                        onNotify(pushedMessage)
                    },
                )
            }

            item {
                SettingsCard(title = stringResource(R.string.section_general)) {
                    SwitchRow(
                        title = stringResource(R.string.toggle_auto_apply),
                        description = stringResource(R.string.toggle_auto_apply_desc),
                        checked = vm.config.enabled,
                        onChange = { value -> vm.update { it.copy(enabled = value) } },
                    )
                    SwitchRow(
                        title = stringResource(R.string.toggle_enforce),
                        description = stringResource(R.string.toggle_enforce_desc),
                        checked = vm.config.enforce,
                        onChange = { value -> vm.update { it.copy(enforce = value) } },
                        enabled = vm.config.enabled,
                    )
                    SwitchRow(
                        title = stringResource(R.string.toggle_notify),
                        description = stringResource(R.string.toggle_notify_desc),
                        checked = vm.config.notifyChanges,
                        onChange = { value -> vm.update { it.copy(notifyChanges = value) } },
                    )
                    SwitchRow(
                        title = stringResource(R.string.toggle_verbose),
                        description = stringResource(R.string.toggle_verbose_desc),
                        checked = vm.config.verbose,
                        onChange = { value -> vm.update { it.copy(verbose = value) } },
                    )
                }
            }

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

            item { BackupCard(vm = vm) }
        }
    }
}

@Composable
private fun BackupCard(vm: MainViewModel) {
    var confirmImport by rememberSaveable { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        uri?.let(vm::exportConfig)
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::importConfig)
    }

    val stamp = remember { SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) }
    val fileName = stringResource(R.string.backup_filename, stamp)

    SettingsCard(title = stringResource(R.string.section_backup)) {
        Text(
            text = stringResource(R.string.backup_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(onClick = { exportLauncher.launch(fileName) }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.backup_export))
            }
            OutlinedButton(onClick = { confirmImport = true }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.backup_import))
            }
        }
    }

    if (confirmImport) {
        AlertDialog(
            onDismissRequest = { confirmImport = false },
            title = { Text(stringResource(R.string.backup_confirm_title)) },
            text = { Text(stringResource(R.string.backup_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmImport = false
                        importLauncher.launch(arrayOf("*/*"))
                    },
                ) {
                    Text(stringResource(R.string.backup_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmImport = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun ModuleCard(vm: MainViewModel, onPush: () -> Unit) {
    val report = vm.report
    val container = when (vm.moduleState) {
        ModuleState.INACTIVE -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }
    val unknown = stringResource(R.string.value_unknown)
    SettingsCard(container = container) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            when (vm.moduleState) {
                ModuleState.CHECKING -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(12.dp))
                        Text(
                            text = stringResource(R.string.module_checking),
                            style = MaterialTheme.typography.titleSmall,
                        )
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
                        Text(
                            text = stringResource(R.string.module_active),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(
                            R.string.module_meta,
                            report?.moduleVersion.orEmpty().ifBlank { unknown },
                            report?.hostPackage.orEmpty().ifBlank { unknown },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    when {
                        report?.configLoaded != true ->
                            SyncLine(stringResource(R.string.module_config_pending), onPush)

                        vm.configSynced -> Text(
                            text = stringResource(R.string.module_config_synced),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )

                        else -> SyncLine(stringResource(R.string.module_config_stale), onPush)
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
                        Text(
                            text = stringResource(R.string.module_inactive),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.module_inactive_body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    listOf(R.string.module_step_1, R.string.module_step_2, R.string.module_step_3).forEach { step ->
                        Text(stringResource(step), style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.module_inactive_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = { vm.requestStatus() }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text(stringResource(R.string.action_recheck))
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
        TextButton(onClick = onPush, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text(stringResource(R.string.action_resend))
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
                    text = row.mac,
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
                    Text(
                        text = stringResource(R.string.device_current, current.summary()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
