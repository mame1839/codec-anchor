package io.github.mame1839.codecanchor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mame1839.codecanchor.BuildConfig
import io.github.mame1839.codecanchor.R
import io.github.mame1839.codecanchor.core.ModuleVersionState

/**
 * 状態タブ。**選ぶものではなく確かめるもの**だけを置く。
 *
 * ここに集めたのは全部**端末側の事情**で、正常なときはモジュールのカードが 2 行出るだけになる。
 * うまく動いていない理由の説明は**ここにしか置かない** — 下部ナビの印
 * ([needsAttention]) は「ここを見ろ」と言うだけで、理由は繰り返さない。
 */
@Composable
fun StatusTab(vm: MainViewModel, contentPadding: PaddingValues, onNotify: (String) -> Unit) {
    val pushedMessage = stringResource(R.string.msg_config_pushed)
    val unknownVersion = stringResource(R.string.value_unknown)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(screenPadding(contentPadding)),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ModuleCard(
            vm = vm,
            onPush = {
                vm.pushConfig()
                onNotify(pushedMessage)
            },
        )

        if (vm.configBroken) {
            SettingsCard(container = MaterialTheme.colorScheme.errorContainer) {
                NoticeRow(
                    icon = R.drawable.ic_warning,
                    text = stringResource(R.string.config_broken),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }

        // オフロードは端末全体の設定なので、機器ごとの詳細ではなくここに出す。報告が届いて
        // いなければ offloadEnabled は false になるので、モジュールが動いていない間は出ない。
        if (vm.a2dpOffloadEnabled) {
            SettingsCard(container = MaterialTheme.colorScheme.surfaceContainerHighest) {
                NoticeRow(
                    icon = R.drawable.ic_bolt,
                    text = stringResource(R.string.offload_hint),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp),
                )
                // 切り方は音響処理の画面にも同じものが出る。文言と出し分けは OffloadTurnOffLines が持つ。
                OffloadTurnOffLines(vm = vm, modifier = Modifier.padding(bottom = 10.dp))
            }
        }

        // 音響処理モジュールとアプリは別々に更新されるので、片方だけ古い状態が普通に起きる。
        // UNKNOWN (プロパティが空 = モジュールが入っていないか、版を出さない古いモジュール) では
        // 何も出さない — 音響処理が使えない理由と二重になるため。
        if (vm.moduleVersionState == ModuleVersionState.MISMATCHED) {
            SettingsCard(container = MaterialTheme.colorScheme.surfaceContainerHighest) {
                NoticeRow(
                    icon = R.drawable.ic_info,
                    text = stringResource(
                        R.string.eq_module_version_mismatch,
                        bidiIsolate(vm.moduleSemver.ifBlank { unknownVersion }),
                        bidiIsolate(BuildConfig.VERSION_NAME),
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun ModuleCard(vm: MainViewModel, onPush: () -> Unit) {
    val report = vm.report
    val container = when (vm.moduleState) {
        ModuleState.INACTIVE -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }
    val unknown = stringResource(R.string.value_unknown)
    SettingsCard(container = container) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            when (vm.moduleState) {
                ModuleState.CHECKING -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(12.dp))
                        Text(
                            text = stringResource(R.string.module_checking),
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                }

                ModuleState.ACTIVE -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(R.drawable.ic_check_circle),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.size(10.dp))
                        Text(
                            text = stringResource(R.string.module_active),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(
                            R.string.module_meta,
                            bidiIsolate(report?.moduleVersion.orEmpty().ifBlank { unknown }),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // 届いているときは何も出さない。異常だけを知らせる。
                    when {
                        report?.configLoaded != true -> {
                            Spacer(Modifier.height(8.dp))
                            SyncLine(stringResource(R.string.module_config_pending), onPush)
                        }

                        !vm.configSynced -> {
                            Spacer(Modifier.height(8.dp))
                            SyncLine(stringResource(R.string.module_config_stale), onPush)
                        }
                    }
                }

                ModuleState.INACTIVE -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(R.drawable.ic_warning),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.size(10.dp))
                        Text(
                            text = stringResource(R.string.module_inactive),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.module_inactive_body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    listOf(R.string.module_step_1, R.string.module_step_2, R.string.module_step_3).forEach { step ->
                        Text(stringResource(step), style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.module_inactive_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = { vm.requestStatus() }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text(stringResource(R.string.action_recheck))
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncLine(message: String, onPush: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onPush, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text(stringResource(R.string.action_resend))
        }
    }
}
