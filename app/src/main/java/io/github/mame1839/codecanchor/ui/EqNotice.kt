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
fun EqUnavailableNotice(vm: MainViewModel, availability: EqAvailability, modifier: Modifier = Modifier) {
    val reason = when (availability) {
        EqAvailability.OK -> return
        EqAvailability.EFFECT_NOT_REGISTERED -> R.string.eq_unavailable_not_registered
        EqAvailability.OFFLOAD_ENABLED -> R.string.eq_unavailable_offload
        // すぐ下に登録のトグルが並ぶので、ここでは操作を繰り返さず「届いていない」だけを言う。
        EqAvailability.DEVICE_NOT_REGISTERED -> R.string.eq_unavailable_device
        EqAvailability.HOOK_TOO_OLD -> R.string.eq_unavailable_hook_old
    }
    // オフロードだけは、この画面から直せる相手ではないのに**直し方が短い**。以前は状態タブの
    // 案内を指していたが、指し先を読み手が特定できないので、切り方をその場に出す。
    if (availability == EqAvailability.OFFLOAD_ENABLED) {
        Column(modifier.fillMaxWidth()) {
            NoticeRow(icon = R.drawable.ic_info, text = stringResource(reason))
            OffloadTurnOffLines(vm = vm, modifier = Modifier.padding(bottom = 4.dp))
        }
        return
    }
    NoticeRow(icon = R.drawable.ic_info, text = stringResource(reason), modifier = modifier)
}

/**
 * **値がいま音に届いているか。**[EqUnavailableNotice] のすぐ下に 1 行置く。
 *
 * 別の composable に分けてあるのは軸が違うから — あちらは「音響処理が使えるか」(端末とモジュールの
 * 状態)、こちらは**「いまこの瞬間、この機器の音になるか」**(接続と `su` の結果)。
 * 同じ enum に混ぜると、繋いでいないあいだ一覧の全機器が「使えません」になる。
 *
 * ### 何を出して、何を黙るか
 *
 * 基準は原因ではなく**「次に何をすればよいか」**。
 *
 * | 状態 | 出すか |
 * |---|---|
 * | 出口の機器が無い ([EqDelivery.IDLE]) | **黙る。**イヤホンを繋いでいないだけで異常ではない |
 * | 枠の持ち主が別の機器 ([EqDelivery.OTHER]) | **出す。**「この機器を繋ぐ」と書ける |
 * | 出口が 2 台以上 ([EqDelivery.AMBIGUOUS]) | **出す。**「1 台にする」と書ける |
 * | 枠がまだ無い ([EqParamsOutcome.NO_LIVE_SLOT]) | **黙る。**押し直しの引き金が勝手に直す (下記) |
 * | 書きに行って失敗した | **出す。**ここを黙らせると症状が「値を変えたのに音が変わらない」に戻る |
 *
 * [availability] が OK でないときは何も出さない — 上の行が既に「音に届かない」と言っているので、
 * 同じことを 2 行に分けて重ねない。
 */
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

    // 結果は書いた相手で突き合わせる。枠の持ち主は画面で開いている機器とは限らないので、
    // ここを見ないと別の機器の失敗をこの機器の画面に出すことになる。
    val report = vm.eqParamsReport?.takeIf { it.mac == EqDevices.normalizeMac(mac) } ?: return
    val message = deliveryMessage(report) ?: return
    Column(modifier.fillMaxWidth()) {
        NoticeRow(icon = R.drawable.ic_warning, text = message, contentPadding = NOTICE_PADDING)
        // su が拒否した理由はここにしか出ない。訳せる文言ではないが、消すと原因を追う手が無くなる。
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

/**
 * 書きに行った結果の文言。**null は「何も出さない」** (書けたときと、出さない判断の結果)。
 *
 * **未知の終了コードは数値ごと出す。**`caeqset` が後からコードを増やしたときに、黙って
 * 「成功」にも「原因不明」にもしないため。
 *
 * internal なのはテストのため — 出す / 出さないの判断はここに集まっていて、
 * 上の [EqDeliveryNotice] は状態を [MainViewModel] からしか読めない。
 */
@Composable
internal fun deliveryMessage(report: EqParamsReport): String? = when (report.result.outcome) {
    EqParamsOutcome.APPLIED -> null

    // ⚠️ **表示が抜けているのではない。何も出さない、が判断** (2026-08-19 のリーダ決定):
    // (1) 切断中にここへは来ない — [EqDeliveryNotice] が [EqDelivery.IDLE] で先に return する。
    // (2) 来るのは LIVE のときだけ = 接続直後などインスタンス生成前の短い窓で、
    //     そこで「繋いでください」「再生してください」は嘘になる。
    // (3) ユーザに求める行動が無い — 既存の押し直しの引き金 (前面復帰・出口の変化・
    //     登録完了・摘みの操作) のどれでも勝手に解消する。
    EqParamsOutcome.NO_LIVE_SLOT -> null

    // ⚠️ **[EqParamsOutcome.NO_LIVE_SLOT] と同じ「黙る」側に倒さないこと。**あちらと違い、
    // 繋がっていて再生中でも起き、押し直しでは直らない (`caeqset` が死んだ枠を回収した後でも
    // 空かなかった状態)。登録のトグルは audioserver を作り直す (`module/eq_devices.sh` §7)
    // ので、詰まった枠は持ち主のプロセスごと消える — 文言の「オフ→オン」はそれを指す。
    EqParamsOutcome.SLOTS_FULL -> stringResource(R.string.eq_delivery_slots_full)

    // 端末側とアプリ側で「2 台ある」の見え方が違うだけなので、文言は 1 つで足りる。
    EqParamsOutcome.AMBIGUOUS_SLOT -> stringResource(R.string.eq_delivery_ambiguous)

    // root が無い端末と拒否された端末で、ユーザにできることは同じ (root マネージャで許可する)。
    EqParamsOutcome.ROOT_DENIED, EqParamsOutcome.NO_SU ->
        stringResource(R.string.eq_delivery_failed_root)

    EqParamsOutcome.NO_SHM -> stringResource(R.string.eq_delivery_failed_shm)
    EqParamsOutcome.VERSION_MISMATCH -> stringResource(R.string.eq_delivery_failed_version)
    // ここだけは原因を名指しして次の行動が書ける唯一の失敗 (バンドのゲインを下げる)。
    // 丸めて通す側に倒さない — 黙って減衰不足になって歪むより、落ちて理由が出るほうが正しい。
    EqParamsOutcome.REJECTED -> stringResource(R.string.eq_delivery_failed_rejected)
    EqParamsOutcome.TIMEOUT -> stringResource(R.string.eq_delivery_failed_timeout)
    EqParamsOutcome.BAD_INPUT -> stringResource(R.string.eq_delivery_failed_input)
    EqParamsOutcome.UNKNOWN -> stringResource(R.string.eq_delivery_failed_unknown, report.result.exitCode)
}

/**
 * **「高精度」を選んでいるのに、いま標準 (biquad) で鳴っているか。**処理方式の行の直下に置く
 * (main の承認済みの置き場)。
 *
 * [EqUnavailableNotice] とも [EqDeliveryNotice] とも軸が違う**第 3 の軸** — 値は届いていて
 * 音も出ていて、**曲線の再現精度だけが選択と違う。**上の 2 つに混ぜると、「音に届いていない」
 * という文と並んで読まれて、EQ ごと効いていないように見える。
 *
 * 判断の材料は直近の押し込みの stdout ([MainViewModel.eqParamsReport] 経由の `CA_EQ_FIR` 行)
 * だけで、**`fs` からアプリが推測しない** — どのブロック長が FIR に向くかの規則は `.so` 側の
 * 1 箇所に置く (アプリ側にも持つと、規則が 2 箇所になって片方だけ変わる)。
 *
 * 出すのは [EqFir.REPORTABLE] の 3 つだけで、**常設の警告にしない** (理由はあちらの KDoc)。
 * [EqDelivery.LIVE] 以外では出さない — 持ち主でない機器の画面に、別の機器の押し込みの
 * 結果を映さないため。古い報告は次の押し込み (前面復帰・出口の変化・摘みの操作) が上書きする。
 *
 * 状態は引数で受ける ([EqUnavailableNotice] が `availability` を受けるのと同じ形) —
 * [MainViewModel] の該当プロパティは外から書けないので、vm を読む形だと出す / 出さないの
 * 判断が検査できない。
 */
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
