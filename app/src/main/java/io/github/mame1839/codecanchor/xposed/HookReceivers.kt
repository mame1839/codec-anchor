package io.github.mame1839.codecanchor.xposed

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Build
import android.os.Handler

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
