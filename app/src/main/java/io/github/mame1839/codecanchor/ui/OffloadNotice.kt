package io.github.mame1839.codecanchor.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mame1839.codecanchor.R

@Composable
fun OffloadTurnOffLines(vm: MainViewModel, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        OffloadLine(stringResource(R.string.offload_turn_off))
        if (vm.freeOffloadSwitch && !vm.settingsHooked) {
            OffloadLine(stringResource(R.string.offload_hint_scope))
        }
    }
}

@Composable
private fun OffloadLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 46.dp, end = 16.dp, top = 4.dp),
    )
}
