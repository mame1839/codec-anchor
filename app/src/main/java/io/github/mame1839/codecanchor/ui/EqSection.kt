package io.github.mame1839.codecanchor.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import io.github.mame1839.codecanchor.core.DeviceProfile
import io.github.mame1839.codecanchor.core.EqAvailability
import io.github.mame1839.codecanchor.core.EqBand
import io.github.mame1839.codecanchor.core.EqBandType
import io.github.mame1839.codecanchor.core.EqMode
import io.github.mame1839.codecanchor.core.EqSettings
import io.github.mame1839.codecanchor.core.EqSolver

// ±12.0 dB を 0.5 dB 刻み。範囲は EqCurve.kt が持つ (絵の縦軸と同じ値を見るため)。
private val GAIN_SCALE = EqScale.Linear(EQ_GAIN_RANGE, EQ_GAIN_STEP)

// プリアンプは下げる方向だけ。上げると解いたゲインの持ち上がりと足し合わさって歪む。
private val PREAMP_SCALE = EqScale.Linear(-300..0, 5)

// 可聴帯域。AutoEQ が出す fc もこの中に収まる。
private val FREQ_SCALE = EqScale.Log(20..20_000)

// Q は 0.10 〜 10.00。シェルフは 0.7 を超えるとオーバーシュートするが、
// 種別ごとに範囲を変えると種別を切り替えた瞬間に値が動くので、範囲は 1 つにする。
private val Q_SCALE = EqScale.Log(10..1_000)

// バンドを足したときの初期値。1 kHz / Q 1.41 / 0 dB は EqBand.fromJson の既定と同じ。
private const val NEW_BAND_HZ = 1_000
private const val NEW_BAND_Q100 = 141

// パラメトリックを何も無いところから始めるときの並び。低・中・高に 1 本ずつ (1 桁ごと)。
// 自由に足したり消したりできるので、最初から埋めておく理由が無い。
private val PARAMETRIC_START_HZ = listOf(100, 1_000, 10_000)

/**
 * 音響処理の中身。入口はここ 1 つで、[EqScreen] から呼ぶ。
 *
 * **ここが扱うのは軽い操作だけ。** EQ のオン・オフと値の変更は共有メモリ越しなので音は切れない。
 * 「このイヤホンを登録する」(`audio_effects.xml` の書き換えと audioserver の再起動を伴い、
 * 再生中の音が一瞬切れる) は重い操作で、別の導線になる。**依頼の経路がまだ無いので、
 * この画面の設定は音に届かない。**経路ができたら、押す前に「音が一瞬途切れます」を出す
 * ボタンをここに足す。
 */
@Composable
fun EqSection(vm: MainViewModel, mac: String, profile: DeviceProfile) {
    val eq = profile.eq
    val availability = vm.eqAvailability
    var presetsExpanded by rememberSaveable { mutableStateOf(false) }
    var confirmGraphic by rememberSaveable { mutableStateOf(false) }

    // 書式は stringResource で取ってから数字を流し込む。新しいファイルで
    // LocalContext.current.resources を書くと lint の LocalContextResourcesRead で落ちる。
    val dbUnit = stringResource(R.string.eq_unit_db)
    val hzUnit = stringResource(R.string.eq_unit_hz)
    val kiloHzUnit = stringResource(R.string.eq_unit_khz)
    val gainText: (Int) -> String = { eqGainText(it, dbUnit) }
    val frequencyText: (Int) -> String = { eqFrequencyText(it, hzUnit, kiloHzUnit) }

    // 見出しは画面の上 (TopAppBar) が持っているので、カードには付けない。
    SettingsCard {
        SwitchRow(
            title = stringResource(R.string.eq_enabled),
            description = stringResource(R.string.eq_enabled_desc),
            checked = eq.enabled,
            onChange = { value ->
                vm.updateEq(mac) { current ->
                    val next = current.copy(enabled = value)
                    if (value) withStartingBands(next) else next
                }
            },
            // 使えないときに新しく入れることはできないが、入っているものを切ることはできる。
            // 切れないと、あとから使えなくなった時点 (オフロードが入った・フックが古い) で
            // 設定が固定されてしまう。
            enabled = availability == EqAvailability.OK || eq.enabled,
        )
        // 使えないときも項目は伸ばしたまま残して理由を出す。OK なら何も出ない。
        EqUnavailableNotice(availability)
        if (!eq.enabled) return@SettingsCard

        RowDivider()

        // グラフィックは「fc と Q を固定したパラメトリック」なので内部表現は 1 つ。
        ChoiceRow(
            title = stringResource(R.string.eq_mode),
            description = stringResource(R.string.eq_mode_desc),
            options = listOf(
                EqMode.GRAPHIC to stringResource(R.string.eq_mode_graphic),
                EqMode.PARAMETRIC to stringResource(R.string.eq_mode_parametric),
            ),
            selected = eq.mode,
            onSelect = { value ->
                when {
                    value == eq.mode -> Unit
                    // グラフィックへ移ると fc と Q が固定値へ丸められる。戻せないので一度だけ確認する。
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
        }

        RowDivider()

        // 絵はスライダーと同じ EqPreviewHost の下に置く。ドラッグ中の値が絵にだけ流れる。
        EqPreviewHost {
            EqCurve(eq)
            if (eq.mode == EqMode.GRAPHIC) {
                GraphicBands(vm = vm, mac = mac, eq = eq, gainText = gainText, frequencyText = frequencyText)
            } else {
                ParametricBands(vm = vm, mac = mac, eq = eq, gainText = gainText, frequencyText = frequencyText)
            }
        }

        RowDivider()

        // 自動のときも値を出す。出さないと何 dB 引かれているのか分からない。
        // 求めた値は保存しない — preampDb10 は手動で決めた値の置き場で、自動のときは
        // 適用する側が同じ式で解く (EqSolver.autoPreampDb10)。両方に書くと出どころが 2 つになる。
        val autoPreamp = remember(eq.bands) { EqSolver.autoPreampDb10(eq.bands) }
        SwitchRow(
            title = stringResource(R.string.eq_preamp_auto),
            description = stringResource(R.string.eq_preamp_auto_desc, gainText(autoPreamp)),
            checked = eq.preampAuto,
            onChange = { value -> vm.updateEq(mac) { it.copy(preampAuto = value) } },
        )
        if (!eq.preampAuto) {
            EqSliderRow(
                label = stringResource(R.string.eq_preamp),
                value = eq.preampDb10,
                scale = PREAMP_SCALE,
                valueText = gainText,
                onCommit = { value -> vm.updateEq(mac) { it.copy(preampDb10 = value) } },
            )
        }

        RowDivider()

        // 追加遅延は設定によらず 0 ms なので常時出す (eq-spec.md §9)。
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
}

@Composable
private fun GraphicBands(
    vm: MainViewModel,
    mac: String,
    eq: EqSettings,
    gainText: (Int) -> String,
    frequencyText: (Int) -> String,
) {
    eq.bands.forEachIndexed { index, band ->
        EqSliderRow(
            label = frequencyText(band.freqHz),
            value = band.gainDb10,
            scale = GAIN_SCALE,
            valueText = gainText,
            onCommit = { value ->
                vm.updateEq(mac) { it.mapBand(index) { current -> current.copy(gainDb10 = value) } }
            },
            previewKey = EqPreviewTarget(index, EqField.GAIN),
        )
    }
}

// private ではなく internal なのは、バンドを開き閉じする回帰テストから直接呼ぶため。
@Composable
internal fun ParametricBands(
    vm: MainViewModel,
    mac: String,
    eq: EqSettings,
    gainText: (Int) -> String,
    frequencyText: (Int) -> String,
) {
    // 開いたバンドの番号。-1 は全部閉じている。
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

    // **1 バンドぶんを key() で包み、開いたときの中身を if の本体に置く。**この 2 つで、
    // 1 回のくり返しが吐くグループとスロットの並びが必ず一定になる。
    //
    // Compose のループはくり返しごとの目印を持たない — `forEachIndexed` 全体が 1 つの
    // replaceGroup で、その中に全バンドのスロットが平らに並び、位置だけで前回と突き合わされる。
    // ここで**末尾以外**のバンドを開くと、そのバンドが吐く量だけ後ろのバンドのスロットがずれ、
    // 覚えてある値を別の型として読んで落ちる (`Integer cannot be cast to KFunction`)。
    // 末尾なら後ろに兄弟がいないので露見しない。
    //
    // `return@forEachIndexed` での打ち切りは、この「量が変わる」ことを Compose の
    // コンパイラから隠す — if の本体に composable が入っていないとグループが作られない。
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
    var naming by rememberSaveable { mutableStateOf(false) }
    var newName by rememberSaveable { mutableStateOf("") }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }

    // 書き出しの対象は名前で持つ。ファイル選択の間にプロセスが作り直されても復元できる。
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
        OutlinedButton(
            onClick = {
                newName = ""
                naming = true
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.eq_preset_save))
        }
        Spacer(Modifier.height(10.dp))
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

    if (naming) {
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text(stringResource(R.string.eq_preset_save_title)) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(stringResource(R.string.eq_preset_name)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        naming = false
                        vm.savePreset(newName, eq)
                    },
                    enabled = newName.isNotBlank(),
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { naming = false }) { Text(stringResource(R.string.action_cancel)) }
            },
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

/**
 * グラフィックの並びに合わせる。**バンド数を変えたときも、パラメトリックから移ってきたときも
 * 同じ 1 本を通す。**
 *
 * 今の合成応答を新しい中心周波数で測り直してから解くので、作り込んだ曲線の形が残る。
 * 捨てて 0 にすると、バンド数を変えただけで音が消える。
 *
 * **解けなくても拒まない。** `EqSolver.solve()` が Q を上げて解き直し、それでも収まらなければ
 * 素朴な値 (古い曲線を新しい中心で評価しただけ) に戻る。素朴な値は必ず計算できるので、
 * ここで必ず止まる。1〜3 dB ずれるだけで形はおおむね保たれるし、
 * ユーザがバンド数を変えると言っているのに拒むほうが悪い。**通知も出さない** —
 * 操作のたびに消せない警告が出る画面になる。
 *
 * private ではなく internal なのは単体テストから呼ぶため。ここは黙って曲線を壊しうる唯一の場所で、
 * 壊れても画面上は「なんとなく音が変わった」にしか見えない。
 */
internal fun reband(settings: EqSettings, bandCount: Int): EqSettings {
    val count = EqSettings.normalizeBandCount(bandCount)
    val freqs = EqSolver.centerFrequencies(count)
    val target = DoubleArray(freqs.size) {
        EqSolver.combinedResponseDb(settings.bands, freqs[it].toDouble())
    }
    return settings.copy(
        bandCount = count,
        bands = EqSolver.solveBands(target, freqs, EqSolver.defaultQ(count)),
    )
}

private fun toGraphic(settings: EqSettings): EqSettings =
    reband(settings.copy(mode = EqMode.GRAPHIC), settings.bandCount)

/**
 * パラメトリックへ移る。**曲線があるならそのまま引き継ぐ** — グラフィックの並びは
 * パラメトリックとしてそのまま読めるので、丸めも解き直しも起きない。
 *
 * **例外は「まだ何も無いとき」だけ。**グラフィックでユーザが決められるのは**ゲインだけ**で、
 * 並びも Q もアプリが作ったもの。全部 0 dB なら曲線は平らで、引き継ぐ情報が 1 つも無い。
 * そこに 0 dB のバンドが 10 本並んでいても操作の邪魔にしかならないので、[PARAMETRIC_START_HZ]
 * の 3 本から始める。
 *
 * **オンにするとき ([withStartingBands]) は同じ条件にしない。**あちらは「1 本も無いとき」だけ。
 * パラメトリックでは fc と Q をユーザが決めるので、**「置いたがゲインはまだ 0」**という状態が
 * 実在する。そこで「平ら」を条件にすると、EQ を切って入れ直しただけでその作業が消える。
 */
internal fun toParametric(settings: EqSettings): EqSettings {
    val next = settings.copy(mode = EqMode.PARAMETRIC)
    return if (next.bands.all { it.gainDb10 == 0 }) next.copy(bands = startingBands()) else next
}

/**
 * EQ をオンにしたときに、バンドが 1 本も出ない状態を避ける。
 *
 * グラフィックは並びが中心周波数と一致している必要があるので [withGraphicGrid] へ、
 * パラメトリックは**空のときだけ** 3 本を置く (値のあるバンドには触らない)。
 */
internal fun withStartingBands(settings: EqSettings): EqSettings = when {
    settings.mode == EqMode.GRAPHIC -> withGraphicGrid(settings)
    settings.bands.isEmpty() -> settings.copy(bands = startingBands())
    else -> settings
}

private fun startingBands(): List<EqBand> =
    PARAMETRIC_START_HZ.map { EqBand(freqHz = it, q100 = NEW_BAND_Q100, gainDb10 = 0) }

/**
 * グラフィックなのに並びが中心周波数と食い違っていたら作り直す。
 *
 * 起きるのは EQ を初めてオンにしたとき (既定の `bands` は空) と、手で書いたプリセットを
 * 読み込んだとき。並びが合っているときは触らない — 解き直すと値がわずかに動くので、
 * オンにするたびに設定が変わったことになる。
 */
internal fun withGraphicGrid(settings: EqSettings): EqSettings {
    if (settings.mode != EqMode.GRAPHIC) return settings
    val freqs = EqSolver.centerFrequencies(settings.bandCount)
    val matches = settings.bands.size == freqs.size &&
        settings.bands.indices.all { settings.bands[it].freqHz == freqs[it] }
    return if (matches) settings else reband(settings, settings.bandCount)
}

// SAF は自由な名前を受け付けるが、区切り文字が入るとプロバイダによって扱いが変わる。
private fun safeFileName(name: String): String =
    name.replace(Regex("""[^\p{L}\p{N}._-]"""), "-").take(48).ifBlank { "preset" }
