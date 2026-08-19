package io.github.mame1839.codecanchor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mame1839.codecanchor.R

private val EQ_TITLE_BAR_PADDING = 36.dp

internal val eqTitleBarHeight: Dp
    @Composable get() = with(LocalDensity.current) {
        MaterialTheme.typography.titleMedium.lineHeight.toDp() +
            MaterialTheme.typography.bodySmall.lineHeight.toDp() +
            EQ_TITLE_BAR_PADDING
    }

@Composable
internal fun EqScreenTitle(name: String) {
    Column {
        Text(
            text = stringResource(R.string.section_eq),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
        )
        Text(
            text = name,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqScreen(
    vm: MainViewModel,
    mac: String,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
) {
    val profile = vm.config.profileFor(mac)
    if (profile == null) {
        LaunchedEffect(mac) { onBack() }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { EqScreenTitle(vm.nameOf(mac)) },
                expandedHeight = eqTitleBarHeight,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(screenPadding(inner)),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            EqSection(vm = vm, mac = mac, profile = profile)
        }
    }
}
