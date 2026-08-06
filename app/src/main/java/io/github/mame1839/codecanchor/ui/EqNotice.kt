package io.github.mame1839.codecanchor.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.mame1839.codecanchor.R
import io.github.mame1839.codecanchor.core.EqAvailability

/**
 * **呼び出し先: EqSection の先頭 — 音響処理のトグル (SwitchRow) の直後で 1 行呼ぶ。**
 * まだ EqSection が無いので、いまはどこからも呼ばれていない。**未使用に見えても消さないこと。**
 *
 * トグルは `enabled = vm.eqAvailability == EqAvailability.OK` で押せなくするが、
 * **項目そのものは伸ばしたまま残してこの理由を出す。**使えない項目を隠さないのが要求。
 *
 * ```
 * SwitchRow(..., enabled = vm.eqAvailability == EqAvailability.OK)
 * EqUnavailableNotice(vm.eqAvailability)
 * ```
 *
 * OK のときは何も出さない (呼び出し側で分岐しなくてよい)。
 */
@Composable
fun EqUnavailableNotice(availability: EqAvailability, modifier: Modifier = Modifier) {
    val reason = when (availability) {
        EqAvailability.OK -> return
        EqAvailability.EFFECT_NOT_REGISTERED -> R.string.eq_unavailable_not_registered
        // 切り方は一覧画面の offload_hint が持っているので、ここでは繰り返さずそちらを指す。
        EqAvailability.OFFLOAD_ENABLED -> R.string.eq_unavailable_offload
        EqAvailability.HOOK_TOO_OLD -> R.string.eq_unavailable_hook_old
    }
    NoticeRow(icon = R.drawable.ic_info, text = stringResource(reason), modifier = modifier)
}
