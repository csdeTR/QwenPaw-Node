package io.qwenpaw.node.ui.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.R
import io.qwenpaw.node.data.model.PawAppDto
import io.qwenpaw.node.ui.common.AdminTopBar
import io.qwenpaw.node.ui.settings.Card
import io.qwenpaw.node.ui.settings.SectionHeader

/**
 * App 中心：列出服务端已安装的 PawApp，点开用系统浏览器打开。
 *
 * PawApp 是依赖整个 Console 宿主的 ESM 模块，不提供独立页面；内嵌 WebView
 * 需要先加载数 MB 的 Console 并注入 token，因此本版只做列表与跳转。
 */
@Composable
fun AppCenterScreen(
    state: AppCenterUiState,
    baseUrl: String?,
    onBack: () -> Unit,
    onEnsureLoaded: () -> Unit,
    onOpenConsole: () -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { onEnsureLoaded() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AdminTopBar(title = stringResource(R.string.appcenter_title), onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            if (state.loading && !state.loaded) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator() }
            }

            state.error?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }

            if (state.loaded && state.apps.isEmpty()) {
                SectionHeader(stringResource(R.string.appcenter_section_empty))
                Card {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = stringResource(R.string.appcenter_empty_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            enabled = baseUrl != null,
                            onClick = onOpenConsole,
                        ) { Text(stringResource(R.string.appcenter_action_open_console)) }
                    }
                }
                return@Column
            }

            SectionHeader(
                title = stringResource(R.string.appcenter_section_installed),
                trailing = pluralStringResource(
                    R.plurals.appcenter_count,
                    state.apps.size,
                    state.apps.size,
                ),
            )
            state.apps.forEach { app ->
                AppRow(
                    app = app,
                    enabled = baseUrl != null,
                    onOpen = {
                        val base = baseUrl ?: return@AppRow
                        context.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("$base/apps/${app.id}"),
                            ),
                        )
                    },
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun AppRow(app: PawAppDto, enabled: Boolean, onOpen: () -> Unit) {
    Card {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.name.ifBlank { app.id },
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = listOf(app.version, app.author).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                app.description.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(enabled = enabled, onClick = onOpen) {
                Text(stringResource(R.string.appcenter_action_open))
            }
        }
    }
}
