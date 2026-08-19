package io.github.mame1839.codecanchor.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.mame1839.codecanchor.BuildConfig
import io.github.mame1839.codecanchor.core.Bridge
import java.lang.reflect.Method

// 設定アプリの開発者向けオプションにある「Bluetooth A2DP ハードウェア オフロードの無効化」は、
// Xiaomi の改造で常に操作できなくなっている。判定を担う isA2dpOffloadPreferenceEnable は
// persist.bluetooth.a2dp_offload.disabled を "false" へ書き戻してから「操作不可」を返し、
// これが画面を開くたび (DashboardFragment の updatePreferenceStates) に走る。
// 判定に true を入れるとグレーアウトが外れ、元のメソッドごと飛ぶので書き戻しも起きない。
// この改造の無い ROM ではクラスもメソッドも見つからないので、何もせずに通る。
//
// **この介入はアプリの設定で切れる** (Bridge.PREFS_KEY_FREE_OFFLOAD_SWITCH)。切られているときは
// 判定に触らず元のメソッドをそのまま走らせる — ROM の挙動が出るだけで、フック自体は載っている
// (設置しないと、アプリで戻したときに設定アプリのプロセスが死ぬまで効かない)。
internal object SettingsHook {
    private const val CLASS_A2DP_CONTROLLER =
        "com.android.settings.development.BluetoothA2dpHwOffloadPreferenceController"
    private const val METHOD_A2DP_GATE = "isA2dpOffloadPreferenceEnable"

    // 同じ画面の再表示ごとに走るので、アプリのプロセスを起こす合図は間引く。
    private const val ANNOUNCE_INTERVAL_MS = 30_000L

    @Volatile
    private var announcedAt = 0L

    // 既定は解放する。読めなかったときもここに倒す — 元の挙動を保つほうが、ユーザの指定を
    // 取り違えて黙って介入をやめるより安全 (取り違えても、下の要求への返事ですぐ直る)。
    @Volatile
    private var freeOffloadSwitch = Bridge.FREE_OFFLOAD_SWITCH_DEFAULT

    // Context そのものは持たない (静的な参照になって漏れる)。開いたかどうかだけ覚える —
    // 解除はしないので、Context を取っておく用事も無い。
    @Volatile
    private var bridgeOpen = false

    fun install(classLoader: ClassLoader) {
        val clazz = XposedHelpers.findClassIfExists(CLASS_A2DP_CONTROLLER, classLoader)
        if (clazz == null) {
            XLog.i("設定アプリに $CLASS_A2DP_CONTROLLER が無いので何もしない")
            return
        }
        // 1 回目の描画はブロードキャストの往復より先に来る。ファイルから種を入れておかないと、
        // 切っているのに 1 回だけ解放される (しかも announce まで飛んでアプリが誤解する)。
        loadFromPrefs()
        var hooked = 0
        booleanMethods(clazz, METHOD_A2DP_GATE).forEach { method ->
            runCatching { XposedBridge.hookMethod(method, offloadGate) }
                .onSuccess { hooked++ }
                .onFailure { XLog.e("$METHOD_A2DP_GATE を差し替えられない", it) }
        }
        if (hooked == 0) {
            XLog.i("$METHOD_A2DP_GATE が無いので何もしない (この ROM は改造されていない)")
            return
        }
        XLog.i("オフロードのトグルの解放を設置した (module=${BuildConfig.VERSION_NAME})")
    }

    // 差し替えが実際に走った時点で名乗る。「フックを設置した」ではなく「開発者向けオプションが
    // 解放された状態で描かれた」を伝えるので、アプリ側はこれをそのまま案内の出し分けに使える。
    //
    // 引数の Context は改造されたメソッドが受け取っているもの (Xiaomi 側は使っていない)。
    private val offloadGate = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val ctx = param.args.getOrNull(0) as? Context
            runCatching { openBridge(ctx) }
            // 介入を切られているときは何もしない。元のメソッドが走り、ROM の判定がそのまま出る。
            if (!freeOffloadSwitch) return
            // 結果を入れた時点で元のメソッドは走らない = プロパティの書き戻しも起きない。
            param.result = true
            runCatching { announce(ctx) }
        }
    }

    /**
     * アプリからの更新を受け取る口を開き、いまの値を要求する。**設置時ではなくここで開く** —
     * handleLoadPackage の時点では Application がまだ無く、Context が取れない。
     *
     * applicationContext に登録して解除しない。設定アプリには A2dpHook の cleanup にあたる
     * 後始末の合図が無く、Activity の Context に付けると画面ごとに漏れる。
     */
    private fun openBridge(context: Context?) {
        if (bridgeOpen) return
        // **受け口が開くまでに届いた分は取りこぼしている。**設定アプリのプロセスは何かのついでに
        // 立ち上がって生き続けるので、install() の種は古くなりうる — ここで読み直さないと、
        // 温まったままのプロセスで「切った直後の 1 回」が効かない。
        // 登録に失敗したときは bridgeOpen が立たないので、次の呼び出しでまた読み直す
        // (受け口が持てない端末では、これが唯一の追従手段になる)。
        loadFromPrefs()
        val ctx = context?.applicationContext ?: return
        val filter = IntentFilter(Bridge.ACTION_PUSH_SETTINGS_HOOK)
        // レシーバは設定アプリのメインスレッドで走らせる (handler = null)。判定を差し替える
        // ゲートも同じスレッドなので、1 回の判定の途中で値が入れ替わることが無い。
        val opened = runCatching {
            registerExported(ctx, receiver, filter, Bridge.PERMISSION, null)
        }.onFailure { XLog.e("設定アプリ側の受け口を作れない", it) }.isSuccess
        if (!opened) return
        bridgeOpen = true
        requestSettingsHook(ctx)
    }

    // レシーバで投げた例外は設定アプリのプロセスを落とす。
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            runCatching {
                if (intent?.action != Bridge.ACTION_PUSH_SETTINGS_HOOK) return
                val value = intent.getBooleanExtra(
                    Bridge.EXTRA_FREE_OFFLOAD_SWITCH,
                    Bridge.FREE_OFFLOAD_SWITCH_DEFAULT,
                )
                if (value == freeOffloadSwitch) return
                freeOffloadSwitch = value
                XLog.i("オフロードのトグルの解放: $value")
            }
        }
    }

    private fun requestSettingsHook(ctx: Context) {
        val intent = Intent(Bridge.ACTION_REQUEST_SETTINGS_HOOK).apply {
            setClassName(Bridge.PKG, Bridge.HOOK_REQUEST_RECEIVER)
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
        }
        runCatching { ctx.sendBroadcast(intent) }.onFailure { XLog.d("設定を要求できない: ${it.message}") }
    }

    // ブロードキャストを取り逃したときの保険。主経路は上の受け口。
    private fun loadFromPrefs() {
        runCatching {
            val prefs = XSharedPreferences(Bridge.PKG, Bridge.PREFS_NAME)
            if (!prefs.file.canRead()) {
                XLog.d("設定ファイルを読めない: ${prefs.file.path}")
                return
            }
            freeOffloadSwitch = prefs.getBoolean(
                Bridge.PREFS_KEY_FREE_OFFLOAD_SWITCH,
                Bridge.FREE_OFFLOAD_SWITCH_DEFAULT,
            )
        }.onFailure { XLog.d("設定ファイルを読めない: ${it.message}") }
    }

    private fun announce(context: Context?) {
        val ctx = context ?: return
        val now = SystemClock.uptimeMillis()
        if (announcedAt != 0L && now - announcedAt < ANNOUNCE_INTERVAL_MS) return
        announcedAt = now
        val intent = Intent(Bridge.ACTION_SETTINGS_HOOKED).apply {
            setClassName(Bridge.PKG, Bridge.HOOK_REQUEST_RECEIVER)
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        }
        runCatching { ctx.sendBroadcast(intent) }.onFailure { XLog.d("合図を送れない: ${it.message}") }
    }

    // 戻り値が boolean のものだけ差し替える。同名で別の形のメソッドを持つ ROM に定数を返させない。
    private fun booleanMethods(clazz: Class<*>, name: String): List<Method> = runCatching {
        clazz.declaredMethods.filter {
            it.name == name && it.returnType == Boolean::class.javaPrimitiveType
        }
    }.getOrDefault(emptyList())
}
