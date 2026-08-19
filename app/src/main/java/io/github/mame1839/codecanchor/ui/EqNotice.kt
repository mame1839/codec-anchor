package io.github.mame1839.codecanchor.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.mame1839.codecanchor.R
import io.github.mame1839.codecanchor.core.EqAvailability
import io.github.mame1839.codecanchor.core.EqDelivery
import io.github.mame1839.codecanchor.core.EqDevices
import io.github.mame1839.codecanchor.core.EqFir
import io.github.mame1839.codecanchor.core.EqParamsOutcome
import io.github.mame1839.codecanchor.core.EqSettings

private val NOTICE_PADDING = PaddingValues(horizontal = 16.dp, vertical = 8.dp)

@Composable
fun EqUnavailableNotice(vm: MainViewModel, availability: EqAvailability, modifier: Modifier = Modifier) {
    val reason = when (availability) {
        EqAvailability.OK -> return
        EqAvailability.EFFECT_NOT_REGISTERED -> R.string.eq_unavailable_not_registered
        EqAvailability.OFFLOAD_ENABLED -> R.string.eq_unavailable_offload
        EqAvailability.DEVICE_NOT_REGISTERED -> R.string.eq_unavailable_device
        EqAvailability.HOOK_TOO_OLD -> R.string.eq_unavailable_hook_old
    }
    if (availability == EqAvailability.OFFLOAD_ENABLED) {
        Column(modifier.fillMaxWidth()) {
            NoticeRow(icon = R.drawable.ic_info, text = stringResource(reason))
            OffloadTurnOffLines(vm = vm, modifier = Modifier.padding(bottom = 4.dp))
        }
        return
    }
    NoticeRow(icon = R.drawable.ic_info, text = stringResource(reason), modifier = modifier)
}

@Composable
fun EqDeliveryNotice(
    vm: MainViewModel,
    mac: String,
    availability: EqAvailability,
    modifier: Modifier = Modifier,
) {
    if (availability != EqAvailability.OK) return
    when (vm.eqDelivery(mac)) {
        EqDelivery.IDLE -> return
        EqDelivery.OTHER -> {
            NoticeRow(
                icon = R.drawable.ic_info,
                text = stringResource(R.string.eq_delivery_other),
                contentPadding = NOTICE_PADDING,
                modifier = modifier,
            )
            return
        }

        EqDelivery.AMBIGUOUS -> {
            NoticeRow(
                icon = R.drawable.ic_warning,
                text = stringResource(R.string.eq_delivery_ambiguous),
                contentPadding = NOTICE_PADDING,
                modifier = modifier,
            )
            return
        }

        EqDelivery.LIVE -> Unit
    }

    val report = vm.eqParamsReport?.takeIf { it.mac == EqDevices.normalizeMac(mac) } ?: return
    val message = deliveryMessage(report) ?: return
    Column(modifier.fillMaxWidth()) {
        NoticeRow(icon = R.drawable.ic_warning, text = message, contentPadding = NOTICE_PADDING)
        val diagnostics = report.result.diagnostics()
        if (diagnostics.isNotEmpty()) {
            Text(
                text = diagnostics,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 46.dp, end = 16.dp, bottom = 8.dp),
            )
        }
    }
}

@Composable
internal fun deliveryMessage(report: EqParamsReport): String? = when (report.result.outcome) {
    EqParamsOutcome.APPLIED -> null

    EqParamsOutcome.NO_LIVE_SLOT -> null

    EqParamsOutcome.SLOTS_FULL -> stringResource(R.string.eq_delivery_slots_full)

    EqParamsOutcome.AMBIGUOUS_SLOT -> stringResource(R.string.eq_delivery_ambiguous)

    EqParamsOutcome.ROOT_DENIED, EqParamsOutcome.NO_SU ->
        stringResource(R.string.eq_delivery_failed_root)

    EqParamsOutcome.NO_SHM -> stringResource(R.string.eq_delivery_failed_shm)
    EqParamsOutcome.VERSION_MISMATCH -> stringResource(R.string.eq_delivery_failed_version)
    EqParamsOutcome.REJECTED -> stringResource(R.string.eq_delivery_failed_rejected)
    EqParamsOutcome.TIMEOUT -> stringResource(R.string.eq_delivery_failed_timeout)
    EqParamsOutcome.BAD_INPUT -> stringResource(R.string.eq_delivery_failed_input)
    EqParamsOutcome.UNKNOWN -> stringResource(R.string.eq_delivery_failed_unknown, report.result.exitCode)
}

@Composable
fun EqPrecisionFallbackNotice(
    eq: EqSettings,
    delivery: EqDelivery,
    report: EqParamsReport?,
    mac: String,
    modifier: Modifier = Modifier,
) {
    if (!eq.firRequested) return
    if (delivery != EqDelivery.LIVE) return
    val fresh = report?.takeIf { it.mac == EqDevices.normalizeMac(mac) } ?: return
    if (!EqFir.fellBackToStandard(fresh.result.stdout)) return
    NoticeRow(icon = R.drawable.ic_info, text = stringResource(R.string.eq_precision_fallback), modifier = modifier)
}
