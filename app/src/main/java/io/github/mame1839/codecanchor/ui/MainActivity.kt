package io.github.mame1839.codecanchor.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.mame1839.codecanchor.R
import io.github.mame1839.codecanchor.core.Bridge
import io.github.mame1839.codecanchor.core.StatusReport
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CodecAnchorTheme {
                CodecAnchorApp()
            }
        }
    }
}

@Composable
private fun CodecAnchorApp(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedMac by rememberSaveable { mutableStateOf<String?>(null) }

    // 報告の送信元が Bluetooth プロセス (別 uid) なので EXPORTED で登録する。
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(from: Context?, intent: Intent?) {
                when (intent?.action) {
                    Bridge.ACTION_REPORT ->
                        StatusReport.decode(intent.getStringExtra(Bridge.EXTRA_JSON))?.let(vm::onReport)

                    BluetoothAdapter.ACTION_STATE_CHANGED,
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                    -> vm.refreshDevices()
                }
            }
        }
        val filter = IntentFilter(Bridge.ACTION_REPORT).apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }

    val notify: (String) -> Unit = { message ->
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }

    // ViewModel は画面より長生きするので、書き出し / 復元の結果はここで受け取って消費済みにする。
    val pendingMessage = vm.pendingMessage
    LaunchedEffect(pendingMessage) {
        if (pendingMessage != null) {
            notify(context.getString(pendingMessage))
            vm.consumeMessage()
        }
    }

    val activity = LocalActivity.current
    val deniedMessage = stringResource(R.string.permission_denied)
    // 恒久拒否のあとは要求ダイアログが出ずに即 false が返るので、設定アプリへ案内する。
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.refreshDevices()
        val blocked = !granted &&
            activity?.shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT) == false
        if (blocked) {
            notify(deniedMessage)
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    ),
                )
            }
        }
    }

    val mac = selectedMac
    if (mac == null) {
        DeviceListScreen(
            vm = vm,
            snackbarHostState = snackbarHostState,
            onOpenDevice = { target ->
                vm.ensureProfile(target)
                selectedMac = target
            },
            onRequestPermission = { permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) },
            onNotify = notify,
        )
    } else {
        BackHandler { selectedMac = null }
        DeviceDetailScreen(
            vm = vm,
            mac = mac,
            snackbarHostState = snackbarHostState,
            onBack = { selectedMac = null },
            onNotify = notify,
        )
    }
}
