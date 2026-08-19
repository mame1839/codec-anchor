package io.github.mame1839.codecanchor.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.mame1839.codecanchor.R
import io.github.mame1839.codecanchor.core.AutoEqParser
import io.github.mame1839.codecanchor.core.DeviceProfile
import io.github.mame1839.codecanchor.core.EqBand
import io.github.mame1839.codecanchor.core.EqBandType
import io.github.mame1839.codecanchor.core.EqMode
import io.github.mame1839.codecanchor.core.EqPrecision
import io.github.mame1839.codecanchor.core.EqSettings
import io.github.mame1839.codecanchor.core.EqSlotBook
import io.github.mame1839.codecanchor.core.EqSolver

internal val GAIN_SCALE = EqScale.Linear(EQ_GAIN_RANGE, EQ_GAIN_STEP)

internal val PREAMP_SCALE = EqScale.Linear(EqSettings.PREAMP_RANGE, 1)

private val FREQ_SCALE = EqScale.Log(20..20_000)

private val Q_SCALE = EqScale.Log(10..1_000)

private const val NEW_BAND_HZ = 1_000
private const val NEW_BAND_Q100 = 141

private val PARAMETRIC_START_HZ = listOf(100, 1_000, 10_000)

@Composable
fun EqSection(vm: MainViewModel, mac: String, profile: DeviceProfile) {
    val eq = profile.eq
    val availability = vm.eqAvailability(mac)
    var presetsExpanded by rememberSaveable { mutableStateOf(false) }
    var confirmGraphic by rememberSaveable { mutableStateOf(false) }
    var confirmReset by rememberSaveable { mutableStateOf(false) }

    val dbUnit = stringResource(R.string.eq_unit_db)
    val hzUnit = stringResource(R.string.eq_unit_hz)
    val kiloHzUnit = stringResource(R.string.eq_unit_khz)
    val gainText: (Int) -> String = { eqGainText(it, dbUnit) }
    val frequencyText: (Int) -> String = { eqFrequencyText(it, hzUnit, kiloHzUnit) }

    SettingsCard {
        EqUnavailableNotice(vm = vm, availability = availability)
        EqDeliveryNotice(vm = vm, mac = mac, availability = availability)

        EqRegisterRow(vm = vm, mac = mac, availability = availability)

        RowDivider()

        SwitchRow(
            title = stringResource(R.string.eq_enabled),
            checked = eq.enabled,
            onChange = { value ->
                vm.updateEq(mac) { current ->
                    val next = current.copy(enabled = value)
                    if (value) withStartingBands(next) else next
                }
            },
            enabled = availability.allowsEditing || eq.enabled,
        )
        if (!eq.enabled) return@SettingsCard

        RowDivider()

        EqSlotRow(vm = vm, mac = mac)

        val flat = vm.slotsOf(mac).active == EqSlotBook.FLAT_ID

        RowDivider()

        if (!flat) {
            ChoiceRow(
                title = stringResource(R.string.eq_mode),
                options = listOf(
                    EqMode.GRAPHIC to stringResource(R.string.eq_mode_graphic),
                    EqMode.PARAMETRIC to stringResource(R.string.eq_mode_parametric),
                ),
                optionDescriptions = mapOf(
                    EqMode.GRAPHIC to stringResource(R.string.eq_mode_graphic_desc),
                    EqMode.PARAMETRIC to stringResource(R.string.eq_mode_parametric_desc),
                ),
                selected = eq.mode,
                onSelect = { value ->
                    when {
                        value == eq.mode -> Unit
                        value == EqMode.GRAPHIC && !vm.eqRoundingConfirmed -> confirmGraphic = true
                        value == EqMode.GRAPHIC -> vm.updateEq(mac) { toGraphic(it) }
                        else -> vm.updateEq(mac) { toParametric(it) }
                    }
                },
            )

            if (eq.mode == EqMode.GRAPHIC) {
                ChoiceRow(
                    title = stringResource(R.string.eq_band_count),
                    description = stringResource(R.string.eq_band_count_desc),
                    options = EqSettings.BAND_COUNTS.map { it to eqCountText(it) },
                    selected = eq.bandCount,
                    onSelect = { value -> vm.updateEq(mac) { reband(it, value) } },
                )

                ChoiceRow(
                    title = stringResource(R.string.eq_precision),
                    options = listOf(
                        EqPrecision.STANDARD to stringResource(R.string.eq_precision_standard),
                        EqPrecision.HIGH to stringResource(R.string.eq_precision_high),
                    ),
                    optionDescriptions = mapOf(
                        EqPrecision.STANDARD to stringResource(R.string.eq_precision_standard_desc),
                        EqPrecision.HIGH to stringResource(R.string.eq_precision_high_desc),
                    ),
                    selected = eq.precision,
                    onSelect = { value -> vm.updateEq(mac) { it.copy(precision = value) } },
                )

                EqPrecisionFallbackNotice(
                    eq = eq,
                    delivery = vm.eqDelivery(mac),
                    report = vm.eqParamsReport,
                    mac = mac,
                )
            }

            RowDivider()
        }

        EqPreviewHost {
            EqCurve(eq)
            if (!flat) {
                if (eq.mode == EqMode.GRAPHIC) {
                    GraphicBands(vm = vm, mac = mac, eq = eq, gainText = gainText, frequencyText = frequencyText)
                } else {
                    ParametricBands(vm = vm, mac = mac, eq = eq, gainText = gainText, frequencyText = frequencyText)
                }
            }
        }

        if (!flat) {
            TextButton(
                onClick = { confirmReset = true },
                enabled = eq.bands.any { it.gainDb10 != 0 } || eq.preampDb10 != 0,
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Text(stringResource(R.string.eq_reset))
            }

            RowDivider()

            EqSliderRow(
                label = stringResource(R.string.eq_preamp),
                value = eq.preampDb10,
                scale = PREAMP_SCALE,
                valueText = gainText,
                onCommit = { value -> vm.updateEq(mac) { it.copy(preampDb10 = value) } },
            )
        }

        RowDivider()

        NoticeRow(
            icon = R.drawable.ic_info,
            text = stringResource(R.string.eq_latency_zero),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        )

        RowDivider()

        ExpandableHeader(
            title = stringResource(R.string.eq_presets),
            expanded = presetsExpanded,
            onToggle = { presetsExpanded = !presetsExpanded },
        )
        if (presetsExpanded) {
            EqPresetRows(vm = vm, mac = mac, eq = eq)
        }
    }

    if (confirmGraphic) {
        RoundingDialog(
            onDismiss = { confirmGraphic = false },
            onConfirm = { quiet ->
                confirmGraphic = false
                if (quiet) vm.confirmEqRounding()
                vm.updateEq(mac) { toGraphic(it) }
            },
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.eq_reset_title)) },
            text = { Text(stringResource(R.string.eq_reset_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmReset = false
                        vm.updateEq(mac) { resetToZero(it) }
                    },
                ) {
                    Text(stringResource(R.string.action_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun GraphicBands(
    vm: MainViewModel,
    mac: String,
    eq: EqSettings,
    gainText: (Int) -> String,
    frequencyText: (Int) -> String,
) {
    val targets = remember(eq.bands) { EqSolver.graphicTargetsDb10(eq.bands) }
    eq.bands.forEachIndexed { index, band ->
        EqSliderRow(
            label = frequencyText(band.freqHz),
            value = targets.getOrElse(index) { band.gainDb10 },
            scale = GAIN_SCALE,
            valueText = gainText,
            onCommit = { value ->
                vm.updateEq(mac) { it.copy(bands = EqSolver.withGraphicTarget(it.bands, index, value)) }
            },
            previewKey = EqPreviewTarget(index, EqField.GRAPHIC_GAIN),
        )
    }
}

@Composable
internal fun ParametricBands(
    vm: MainViewModel,
    mac: String,
    eq: EqSettings,
    gainText: (Int) -> String,
    frequencyText: (Int) -> String,
) {
    var openIndex by rememberSaveable { mutableIntStateOf(-1) }
    val typeLabels = listOf(
        EqBandType.PEAKING to stringResource(R.string.eq_type_peaking),
        EqBandType.LOW_SHELF to stringResource(R.string.eq_type_low_shelf),
        EqBandType.HIGH_SHELF to stringResource(R.string.eq_type_high_shelf),
    )

    if (eq.bands.isEmpty()) {
        NoticeRow(
            icon = R.drawable.ic_info,
            text = stringResource(R.string.eq_band_none),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        )
    }

    eq.bands.forEachIndexed { index, band ->
        key(index) {
            val typeLabel = typeLabels.firstOrNull { it.first == band.type }?.second.orEmpty()
            val open = openIndex == index
            ExpandableHeader(
                title = listOf(frequencyText(band.freqHz), typeLabel, gainText(band.gainDb10))
                    .joinToString(" · "),
                expanded = open,
                onToggle = { openIndex = if (open) -1 else index },
            )
            if (open) {
                ChoiceRow(
                    title = stringResource(R.string.eq_band_type),
                    options = typeLabels,
                    selected = band.type,
                    onSelect = { value ->
                        vm.updateEq(mac) { it.mapBand(index) { current -> current.copy(type = value) } }
                    },
                )
                EqSliderRow(
                    label = stringResource(R.string.eq_band_frequency),
                    value = band.freqHz,
                    scale = FREQ_SCALE,
                    valueText = frequencyText,
                    onCommit = { value ->
                        vm.updateEq(mac) { it.mapBand(index) { current -> current.copy(freqHz = value) } }
                    },
                    previewKey = EqPreviewTarget(index, EqField.FREQUENCY),
                )
                EqSliderRow(
                    label = stringResource(R.string.eq_band_q),
                    value = band.q100,
                    scale = Q_SCALE,
                    valueText = ::eqQText,
                    onCommit = { value ->
                        vm.updateEq(mac) { it.mapBand(index) { current -> current.copy(q100 = value) } }
                    },
                    previewKey = EqPreviewTarget(index, EqField.Q),
                )
                EqSliderRow(
                    label = stringResource(R.string.eq_band_gain),
                    value = band.gainDb10,
                    scale = GAIN_SCALE,
                    valueText = gainText,
                    onCommit = { value ->
                        vm.updateEq(mac) { it.mapBand(index) { current -> current.copy(gainDb10 = value) } }
                    },
                    previewKey = EqPreviewTarget(index, EqField.GAIN),
                )
                TextButton(
                    onClick = {
                        openIndex = -1
                        vm.updateEq(mac) { it.copy(bands = it.bands.filterIndexed { i, _ -> i != index }) }
                    },
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text(stringResource(R.string.eq_band_remove), color = MaterialTheme.colorScheme.error)
                }
                RowDivider()
            }
        }
    }

    TextButton(
        onClick = {
            vm.updateEq(mac) {
                it.copy(bands = it.bands + EqBand(NEW_BAND_HZ, NEW_BAND_Q100, 0))
            }
            openIndex = eq.bands.size
        },
        enabled = eq.bands.size < EqSettings.MAX_BANDS,
        modifier = Modifier.padding(start = 8.dp),
    ) {
        Text(stringResource(R.string.eq_band_add))
    }
}

@Composable
private fun EqPresetRows(vm: MainViewModel, mac: String, eq: EqSettings) {
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }

    var exporting by rememberSaveable { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val preset = vm.presets.presets.firstOrNull { it.name == exporting }
        exporting = null
        if (uri != null && preset != null) vm.exportPreset(uri, preset)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::importPreset)
    }
    val autoEqLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.importAutoEq(it, mac, eq.bandCount) }
    }

    RowDivider()

    if (vm.presets.presets.isEmpty()) {
        Text(
            text = stringResource(R.string.eq_preset_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
    vm.presets.presets.forEach { preset ->
        val fileName = stringResource(R.string.preset_filename, safeFileName(preset.name))
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp)) {
            Text(preset.name, style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { vm.applyPreset(mac, preset.name) }) {
                    Text(stringResource(R.string.eq_preset_apply))
                }
                TextButton(
                    onClick = {
                        exporting = preset.name
                        exportLauncher.launch(fileName)
                    },
                ) {
                    Text(stringResource(R.string.eq_preset_export))
                }
                TextButton(onClick = { deleting = preset.name }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.eq_preset_import))
            }
            OutlinedButton(
                onClick = { autoEqLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.eq_autoeq_import))
            }
        }
        Text(
            text = stringResource(R.string.eq_autoeq_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    val target = deleting
    if (target != null) {
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.eq_preset_delete_title)) },
            text = { Text(stringResource(R.string.eq_preset_delete_body, target)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting = null
                        vm.deletePreset(target)
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
private fun RoundingDialog(onDismiss: () -> Unit, onConfirm: (Boolean) -> Unit) {
    var quiet by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.eq_round_title)) },
        text = {
            Column {
                Text(stringResource(R.string.eq_round_body))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(value = quiet, role = Role.Checkbox, onValueChange = { quiet = it })
                        .padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = quiet, onCheckedChange = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.eq_round_quiet))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(quiet) }) { Text(stringResource(R.string.action_continue)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

private fun EqSettings.mapBand(index: Int, transform: (EqBand) -> EqBand): EqSettings =
    copy(bands = bands.mapIndexed { i, band -> if (i == index) transform(band) else band })

internal fun reband(settings: EqSettings, bandCount: Int): EqSettings {
    val count = EqSettings.normalizeBandCount(bandCount)
    val freqs = EqSolver.centerFrequencies(count)
    val target = if (matchesGraphicGrid(settings)) {
        val knobs = EqSolver.graphicTargetsDb10(settings.bands)
        val points = settings.bands.mapIndexed { i, band ->
            band.freqHz.toDouble() to knobs[i].toDouble() / 10.0
        }
        DoubleArray(freqs.size) { AutoEqParser.interpolate(points, freqs[it].toDouble()) }
    } else {
        DoubleArray(freqs.size) {
            EqSolver.combinedResponseDb(settings.bands, freqs[it].toDouble())
        }
    }
    return settings.copy(
        bandCount = count,
        bands = EqSolver.solveBands(target, freqs, EqSolver.defaultQ(count)),
    )
}

private fun matchesGraphicGrid(settings: EqSettings): Boolean {
    val freqs = EqSolver.centerFrequencies(settings.bandCount)
    return settings.bands.size == freqs.size &&
        settings.bands.indices.all { settings.bands[it].freqHz == freqs[it] } &&
        settings.bands.all { it.type == EqBandType.PEAKING }
}

private fun toGraphic(settings: EqSettings): EqSettings =
    reband(settings.copy(mode = EqMode.GRAPHIC), settings.bandCount)

internal fun toParametric(settings: EqSettings): EqSettings {
    val next = settings.copy(mode = EqMode.PARAMETRIC)
    return if (next.bands.all { it.gainDb10 == 0 }) next.copy(bands = startingBands()) else next
}

internal fun resetToZero(settings: EqSettings): EqSettings = when (settings.mode) {
    EqMode.GRAPHIC -> reband(settings.copy(bands = emptyList()), settings.bandCount).copy(preampDb10 = 0)
    else -> settings.copy(bands = settings.bands.map { it.copy(gainDb10 = 0) }, preampDb10 = 0)
}

internal fun flatEq(base: EqSettings): EqSettings = withStartingBands(
    base.copy(mode = EqMode.GRAPHIC, bands = emptyList(), preampDb10 = 0),
)

internal fun withStartingBands(settings: EqSettings): EqSettings = when {
    settings.mode == EqMode.GRAPHIC -> withGraphicGrid(settings)
    settings.bands.isEmpty() -> settings.copy(bands = startingBands())
    else -> settings
}

private fun startingBands(): List<EqBand> =
    PARAMETRIC_START_HZ.map { EqBand(freqHz = it, q100 = NEW_BAND_Q100, gainDb10 = 0) }

internal fun withGraphicGrid(settings: EqSettings): EqSettings {
    if (settings.mode != EqMode.GRAPHIC) return settings
    val freqs = EqSolver.centerFrequencies(settings.bandCount)
    val matches = settings.bands.size == freqs.size &&
        settings.bands.indices.all { settings.bands[it].freqHz == freqs[it] }
    return if (matches) settings else reband(settings, settings.bandCount)
}

private fun safeFileName(name: String): String =
    name.replace(Regex("""[^\p{L}\p{N}._-]"""), "-").take(48).ifBlank { "preset" }
