package io.github.mame1839.codecanchor.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mame1839.codecanchor.R
import io.github.mame1839.codecanchor.core.DeviceSlots
import io.github.mame1839.codecanchor.core.EqSlot
import io.github.mame1839.codecanchor.core.EqSlotBook

internal fun eqSlotNumber(slot: EqSlot, index: Int): Int = slot.id.toIntOrNull() ?: (index + 1)

@Composable
internal fun EqSlotRow(vm: MainViewModel, mac: String) {
    val device = vm.slotsOf(mac)

    var menuFor by rememberSaveable { mutableStateOf<String?>(null) }
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var savingPreset by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var draft by rememberSaveable { mutableStateOf("") }

    val menuIcon: @Composable () -> Unit = {
        Icon(
            painter = painterResource(R.drawable.ic_edit),
            contentDescription = stringResource(R.string.cd_eq_slot_menu),
            modifier = Modifier.size(FilterChipDefaults.IconSize),
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = device.active == EqSlotBook.FLAT_ID,
            onClick = { vm.selectSlot(mac, EqSlotBook.FLAT_ID) },
            label = { Text(stringResource(R.string.eq_slot_flat)) },
        )

        device.slots.forEachIndexed { index, slot ->
            key(slot.id) {
                val selected = slot.id == device.active
                Box {
                    FilterChip(
                        selected = selected,
                        onClick = {
                            if (selected) menuFor = slot.id else vm.selectSlot(mac, slot.id)
                        },
                        label = { Text(eqSlotLabel(slot, index)) },
                        trailingIcon = if (selected) menuIcon else null,
                    )
                    DropdownMenu(expanded = menuFor == slot.id, onDismissRequest = { menuFor = null }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.eq_slot_rename)) },
                            onClick = {
                                menuFor = null
                                draft = slot.name
                                renaming = slot.id
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.eq_slot_duplicate)) },
                            onClick = {
                                menuFor = null
                                vm.duplicateSlot(mac, slot.id)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.eq_slot_save_preset)) },
                            onClick = {
                                menuFor = null
                                draft = slot.name
                                savingPreset = slot.id
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = stringResource(R.string.action_delete),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                menuFor = null
                                deleting = slot.id
                            },
                        )
                    }
                }
            }
        }

        AssistChip(
            onClick = { vm.addSlot(mac) },
            label = {
                Icon(
                    painter = painterResource(R.drawable.ic_add),
                    contentDescription = stringResource(R.string.cd_eq_slot_add),
                    modifier = Modifier.size(AssistChipDefaults.IconSize),
                )
            },
        )
    }

    val renameTarget = renaming?.let { device.slot(it) }
    if (renameTarget != null) {
        val index = device.slots.indexOfFirst { it.id == renameTarget.id }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.eq_slot_rename_title)) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text(stringResource(R.string.eq_preset_name)) },
                    placeholder = { Text(eqSlotLabel(renameTarget, index)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        renaming = null
                        vm.renameSlot(mac, renameTarget.id, draft)
                    },
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    val presetTarget = savingPreset?.let { device.slot(it) }
    if (presetTarget != null) {
        AlertDialog(
            onDismissRequest = { savingPreset = null },
            title = { Text(stringResource(R.string.eq_preset_save_title)) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text(stringResource(R.string.eq_preset_name)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        savingPreset = null
                        vm.savePreset(draft, presetTarget.eq)
                    },
                    enabled = draft.isNotBlank(),
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { savingPreset = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    val deleteTarget = deleting?.let { device.slot(it) }
    if (deleteTarget != null) {
        val index = device.slots.indexOfFirst { it.id == deleteTarget.id }
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.eq_slot_delete_title)) },
            text = { Text(stringResource(R.string.eq_slot_delete_body, eqSlotLabel(deleteTarget, index))) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting = null
                        vm.deleteSlot(mac, deleteTarget.id)
                    },
                ) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
internal fun eqSlotLabel(slot: EqSlot, index: Int): String =
    slot.name.ifBlank { stringResource(R.string.eq_slot_default, eqSlotNumber(slot, index)) }

@Composable
internal fun eqActiveSlotLabel(device: DeviceSlots): String {
    val index = device.slots.indexOfFirst { it.id == device.active }
    val slot = device.slots.getOrNull(index) ?: return stringResource(R.string.eq_slot_flat)
    return eqSlotLabel(slot, index)
}
