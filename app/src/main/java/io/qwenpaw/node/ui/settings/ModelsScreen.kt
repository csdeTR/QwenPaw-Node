package io.qwenpaw.node.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.R
import io.qwenpaw.node.ui.chat.OptionDialog
import io.qwenpaw.node.ui.chat.OptionItem
import io.qwenpaw.node.ui.common.ConsoleHint
import io.qwenpaw.node.ui.common.WebEntryDialog
import io.qwenpaw.node.ui.theme.QwenIcons

/**
 * 模型与提供商：两个作用域的默认模型与供应商连通性测试。
 *
 * 只做「看状态、切默认、测连通」。模型增删、供应商配置与 OAuth 都在 Web 控制台，
 * 手机上做这些只会得到一张填不完的表单。
 */
@Composable
fun ModelsScreen(
    state: ModelsUiState,
    onBack: () -> Unit,
    onPickDefault: (ModelScope, String, String) -> Unit,
    onRetry: () -> Unit,
    onDismissNotice: () -> Unit,
    onOpenConsole: (() -> Unit)? = null,
    onOpenProvider: (String) -> Unit = {},
) {
    // 正在选模型的对话框属于哪一行；null 表示没有打开。
    var picking by remember { mutableStateOf<ModelScope?>(null) }

    WebEntryDialog(pageKey = "models", onOpenConsole = onOpenConsole)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .statusBarsPadding(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onBack),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = QwenIcons.arrowLeft,
                            contentDescription = stringResource(R.string.action_back),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.models_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                state.loading && !state.loaded -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator() }

                state.providers.isEmpty() && state.error != null -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = state.error,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.models_action_retry_click),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(onClick = onRetry),
                    )
                }

                else -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    ConsoleHint(onOpenConsole = onOpenConsole)
                    ModelsCard {
                        Text(
                            text = stringResource(R.string.models_section_default),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(6.dp))
                        ModelScope.entries.forEach { scope ->
                            DefaultModelRow(
                                scope = scope,
                                value = state.labelOf(scope)
                                    ?: stringResource(R.string.models_follow_global),
                                enabled = !state.saving && state.choices.isNotEmpty(),
                                onClick = { picking = scope },
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.models_default_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // 切换失败的提示必须贴着这两行：放在供应商列表之后，
                        // 在几十个供应商的长列表里根本看不见，用户会以为「点了没反应」。
                        val errorText = state.error
                        if (errorText != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = errorText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        val noticeText = state.notice
                        if (noticeText != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = noticeText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable(onClick = onDismissNotice),
                            )
                        }
                    }

                    ModelsCard {
                        Text(
                            text = stringResource(R.string.models_section_providers),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(10.dp))
                        if (state.providers.isEmpty()) {
                            Text(
                                text = stringResource(R.string.models_no_providers),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            state.providers.forEach { provider ->
                                val count = provider.allModels.size
                                val modelsText = if (count == 0) {
                                    stringResource(R.string.models_no_models)
                                } else {
                                    pluralStringResource(
                                        R.plurals.models_model_count,
                                        count,
                                        count,
                                    )
                                }
                                ProviderRow(
                                    name = provider.name.ifBlank { provider.id },
                                    detail = if (provider.enabled) {
                                        modelsText
                                    } else {
                                        stringResource(
                                            R.string.models_detail_disabled,
                                            modelsText,
                                        )
                                    },
                                    dimmed = !provider.enabled,
                                    onOpen = { onOpenProvider(provider.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    val pickingScope = picking
    if (pickingScope != null) {
        OptionDialog(
            title = stringResource(pickingScope.titleRes),
            items = state.choices.map { choice ->
                OptionItem(
                    key = choice.key,
                    label = choice.modelName,
                    sublabel = choice.providerName,
                    selected = state.labelOf(pickingScope) == choice.modelName,
                )
            },
            emptyHint = stringResource(R.string.models_no_available_models),
            onPick = { key ->
                picking = null
                state.choices.firstOrNull { it.key == key }?.let { choice ->
                    onPickDefault(pickingScope, choice.providerId, choice.modelId)
                }
            },
            onDismiss = { picking = null },
        )
    }
}

@Composable
private fun ModelsCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(14.dp),
    ) { content() }
}

/**
 * 一行默认模型。
 *
 * 当前值放在第二行而不是右对齐：模型名常见 30 个字符以上（如
 * `CodeArts/deepseek-v4.1-flash-1017`），右对齐会把名字截成
 * `CodeArts/deepseek-v4.1-flash-10…`，用户看不出选的是哪个。
 */
@Composable
private fun DefaultModelRow(
    scope: ModelScope,
    value: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(scope.titleRes),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.width(6.dp))
        Icon(
            imageVector = QwenIcons.chevronRight,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProviderRow(
    name: String,
    detail: String,
    dimmed: Boolean,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onOpen)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (dimmed) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = QwenIcons.chevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}
