package io.github.mame1839.codecanchor.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
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

    // 送信元が Bluetooth プロセス (別 uid) なので EXPORTED で登録する。
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(from: Context?, intent: Intent?) {
                if (intent?.action != Bridge.ACTION_REPORT) return
                StatusReport.decode(intent.getStringExtra(Bridge.EXTRA_JSON))?.let(vm::onReport)
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Bridge.ACTION_REPORT),
            ContextCompat.RECEIVER_EXPORTED,
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.refreshDevices()
    }

    val notify: (String) -> Unit = { message ->
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
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
