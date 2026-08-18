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

/**
 * **オフロードの切り方。困っている画面のその場に出す。**
 *
 * 出す場所は 2 つある — 状態タブ (オフロードが効いている) と音響処理の画面
 * (そのせいで使えない)。**以前は後者が「切り方は機器一覧の案内に書いてあります」と
 * 前者を指していた**が、指し先が読み手に分からず (実際には状態タブなのに「機器一覧」と
 * 書いてあった)、案内を読むだけで画面を 1 つ移る必要があった。**文言は 1 つのまま、
 * 出す場所を増やす**のがここの役目。
 *
 * **なぜ困るかはここには書かない。**LDAC のビットレートと音響処理では理由が違うので、
 * 1 つの文にまとめると必ずどちらかで嘘になる。理由は呼び出し側が 1 行持つ。
 *
 * 前の行が [NoticeRow] であることを前提に、アイコンぶんの 46 dp を空けて字下げを揃える
 * (16 dp の余白 + 20 dp のアイコン + 10 dp の間隔)。
 */
@Composable
fun OffloadTurnOffLines(vm: MainViewModel, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        OffloadLine(stringResource(R.string.offload_turn_off))
        // トグルが塞がれているかは端末側からは読めない。設定アプリに入ったフックが名乗ってきたか
        // どうかで代える — 名乗りが無いのは「スコープ未追加」か「設定アプリをまだ開いていない」の
        // どちらかで、どちらでもこの案内が当たる。
        //
        // 解放そのものを切っているときは出さない。スコープを足しても解放されないので、
        // 足せと言うと外れた案内になる (切ったのはユーザ自身なので、理由は設定タブにある)。
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
