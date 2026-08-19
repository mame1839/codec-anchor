package io.github.mame1839.codecanchor.xposed

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Build
import android.os.Handler

/**
 * 他プロセスの中から exported なレシーバを登録する。**Bluetooth プロセス ([A2dpHook]) と
 * 設定アプリ ([SettingsHook]) の両方が使うので、書き方はここ 1 つだけ。**
 *
 * **androidx を使わない。**`ContextCompat.registerReceiver` なら分岐ごと 1 行で書けるが、
 * フックのコードは**相手のプロセスの中で解決される** — `com.android.settings` 自身も
 * `androidx.core` を積んでいて、どちらが勝つかは読み込み側の委譲順で決まる。
 * 6 引数の `registerReceiver` を持たない版が勝つと `NoSuchMethodError` になり、
 * 呼び出し側の `runCatching` が飲んで**登録できないまま静かに残る** (更新が永久に届かない)。
 * 素の API だけで書けば、この問いごと消える。
 *
 * `@SuppressLint` は minSdk 31 のため — T 未満の `registerReceiver` には渡す旗が無い。
 * 宛先は送る側が `setPackage` で絞り、受け取る側は [permission] で送信元を絞っている。
 */
@SuppressLint("UnspecifiedRegisterReceiverFlag")
internal fun registerExported(
    ctx: Context,
    receiver: BroadcastReceiver,
    filter: IntentFilter,
    permission: String?,
    handler: Handler?,
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ctx.registerReceiver(receiver, filter, permission, handler, Context.RECEIVER_EXPORTED)
    } else {
        ctx.registerReceiver(receiver, filter, permission, handler)
    }
}
