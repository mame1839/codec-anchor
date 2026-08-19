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

    val pendingMessage = vm.pendingMessage
    val pendingText = pendingMessage?.let { stringResource(it) }
    LaunchedEffect(pendingText) {
        if (pendingText != null) {
            notify(pendingText)
            vm.consumeMessage()
        }
    }

    val activity = LocalActivity.current
    val deniedMessage = stringResource(R.string.permission_denied)
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

    AppNavigation(
        vm = vm,
        snackbarHostState = snackbarHostState,
        onRequestPermission = { permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) },
        onNotify = notify,
    )
}

@Composable
internal fun AppNavigation(
    vm: MainViewModel,
    snackbarHostState: SnackbarHostState,
    onRequestPermission: () -> Unit,
    onNotify: (String) -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(HomeTab.DEVICES) }
    var selectedMac by rememberSaveable { mutableStateOf<String?>(null) }
    var eqOpen by rememberSaveable { mutableStateOf(false) }
    var eqFinderOpen by rememberSaveable { mutableStateOf(false) }

    val mac = selectedMac
    when {
        mac == null -> {
            BackHandler(enabled = tab != HomeTab.DEVICES) { tab = HomeTab.DEVICES }
            HomeScreen(
                vm = vm,
                tab = tab,
                onSelectTab = { tab = it },
                snackbarHostState = snackbarHostState,
                onOpenDevice = { target ->
                    vm.ensureProfile(target)
                    selectedMac = target
                },
                onRequestPermission = onRequestPermission,
                onNotify = onNotify,
            )
        }

        eqFinderOpen -> {
            BackHandler { eqFinderOpen = false }
            EqFinderScreen(
                vm = vm,
                mac = mac,
                snackbarHostState = snackbarHostState,
                onBack = { eqFinderOpen = false },
            )
        }

        eqOpen -> {
            BackHandler { eqOpen = false }
            EqScreen(
                vm = vm,
                mac = mac,
                snackbarHostState = snackbarHostState,
                onBack = { eqOpen = false },
                onOpenFinder = { eqFinderOpen = true },
            )
        }

        else -> {
            BackHandler { selectedMac = null }
            DeviceDetailScreen(
                vm = vm,
                mac = mac,
                snackbarHostState = snackbarHostState,
                onBack = { selectedMac = null },
                onOpenEq = { eqOpen = true },
                onNotify = onNotify,
            )
        }
    }
}
