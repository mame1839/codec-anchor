package io.github.mame1839.codecanchor.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.mame1839.codecanchor.R
import io.github.mame1839.codecanchor.core.EqAvailability

/**
 * **呼び出し先: EqSection のカードの先頭で 1 行呼ぶ。**
 *
 * 理由はセクション全体に掛かる — 中の 2 つのトグル (登録 / イコライザー) は理由によって
 * 片方だけ押せなくなるので、どちらかの直後に置くともう片方の説明が消える。
 *
 * トグルは `enabled = availability.allowsEditing` で押せなくするが、
 * **項目そのものは伸ばしたまま残してこの理由を出す。**使えない項目を隠さないのが要求。
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
        // すぐ下に登録のトグルが並ぶので、ここでは操作を繰り返さず「届いていない」だけを言う。
        EqAvailability.DEVICE_NOT_REGISTERED -> R.string.eq_unavailable_device
        EqAvailability.HOOK_TOO_OLD -> R.string.eq_unavailable_hook_old
    }
    NoticeRow(icon = R.drawable.ic_info, text = stringResource(reason), modifier = modifier)
}
