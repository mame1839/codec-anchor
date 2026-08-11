package io.github.mame1839.codecanchor.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mame1839.codecanchor.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 設定タブ。**機器を選ばずに決めるものだけ**を置く。
 *
 * 機器ごとの設定は詳細画面が持つ。端末側の事情 (モジュール・オフロード・版) は
 * 選ぶものではないので [StatusTab] へ。
 */
@Composable
fun SettingsTab(vm: MainViewModel, contentPadding: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(screenPadding(contentPadding)),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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

        BackupCard(vm = vm)
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
