package io.github.mame1839.codecanchor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.mame1839.codecanchor.R
import io.github.mame1839.codecanchor.core.EqAvailability
import io.github.mame1839.codecanchor.core.EqDevices
import io.github.mame1839.codecanchor.core.EqDevicesOutcome

private val NOTICE_PADDING = PaddingValues(horizontal = 16.dp, vertical = 8.dp)

/**
 * 「このイヤホンで音響処理を使う」。**この画面で唯一の重い操作。**
 *
 * `audio_effects.xml` を作り直して audioserver を再起動するので、**再生中の音が一瞬切れる。**
 * だから押す前に確認を挟み、走っている間は押せなくし、終わったら成功も失敗も必ず出す。
 *
 * 切るときも同じ重さ (XML を作り直して再起動する) なので、確認は両方向で出す。
 */
@Composable
fun EqRegisterRow(vm: MainViewModel, mac: String, availability: EqAvailability) {
    // 進行中と結果は MAC で突き合わせる。表記が揺れると別の機器のものを出すので、
    // 揃わない MAC (null) はどちらにも一致させない。
    val key = EqDevices.normalizeMac(mac)
    val registered = vm.eqRegistered(mac)
    val running = key != null && vm.eqRegisterRunning == key
    val report = vm.eqRegisterReport?.takeIf { it.mac == key }

    // 確認待ちの向き。null なら聞いていない。
    var pending by rememberSaveable { mutableStateOf<Boolean?>(null) }

    SwitchRow(
        title = stringResource(R.string.eq_device_register),
        description = stringResource(R.string.eq_device_register_desc),
        checked = registered,
        onChange = { pending = it },
        // 押せなくするのは 2 つだけ。走っている間 (他機器の操作でも同じ。同時には 1 つしか走らない) と、
        // エフェクトがそもそも登録されていない端末 (su を呼んでも「モジュールが無い」にしかならない)。
        // オフロード中は音に届かないが、登録そのものは成立するので止めない — 止めると
        // 「オフロードを切ってから登録する」しかできなくなる。
        enabled = vm.eqRegisterRunning == null && availability != EqAvailability.EFFECT_NOT_REGISTERED,
    )

    if (running) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(NOTICE_PADDING),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.eq_device_running), style = MaterialTheme.typography.bodyMedium)
        }
    } else if (report != null) {
        val ok = report.result.outcome == EqDevicesOutcome.OK
        Column(Modifier.fillMaxWidth()) {
            NoticeRow(
                icon = if (ok) R.drawable.ic_check_circle else R.drawable.ic_warning,
                text = registerMessage(report),
                contentPadding = NOTICE_PADDING,
            )
            // 失敗したときだけ、スクリプトと su が出した最後の数行をそのまま添える。
            // 訳せる文言ではないが、su の拒否理由はここにしか出ない。
            val diagnostics = if (ok) "" else report.result.diagnostics()
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

    val direction = pending
    if (direction != null) {
        AlertDialog(
            onDismissRequest = { pending = null },
            title = {
                Text(
                    stringResource(
                        if (direction) R.string.eq_device_confirm_on_title else R.string.eq_device_confirm_off_title,
                    ),
                )
            },
            text = { Text(stringResource(R.string.eq_device_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pending = null
                        vm.setEqRegistered(mac, direction)
                    },
                ) {
                    Text(stringResource(R.string.action_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/**
 * 結果の文言。**未知の終了コードは数値ごと出す** — 後からスクリプト側がコードを増やしたときに、
 * 黙って「成功」にも「原因不明」にもしないため。
 */
@Composable
private fun registerMessage(report: EqRegisterReport): String = when (report.result.outcome) {
    EqDevicesOutcome.OK ->
        stringResource(if (report.turnedOn) R.string.eq_device_result_on else R.string.eq_device_result_off)

    EqDevicesOutcome.NO_MODULE -> stringResource(R.string.eq_device_failed_no_module)
    EqDevicesOutcome.ROOT_DENIED -> stringResource(R.string.eq_device_failed_root)
    EqDevicesOutcome.TIMEOUT -> stringResource(R.string.eq_device_failed_timeout)
    EqDevicesOutcome.SCRIPT_FAILED -> when (report.result.exitCode) {
        EqDevices.EXIT_BAD_INPUT -> stringResource(R.string.eq_device_failed_input)
        EqDevices.EXIT_NO_STATE -> stringResource(R.string.eq_device_failed_no_state)
        EqDevices.EXIT_XML_FAILED -> stringResource(R.string.eq_device_failed_xml)
        EqDevices.EXIT_APPLY_FAILED -> stringResource(R.string.eq_device_failed_apply)
        EqDevices.EXIT_AUDIOSERVER_TIMEOUT -> stringResource(R.string.eq_device_failed_audioserver)
        else -> stringResource(R.string.eq_device_failed_unknown, report.result.exitCode)
    }
}
