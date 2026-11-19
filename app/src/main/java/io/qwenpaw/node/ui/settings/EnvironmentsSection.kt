package io.qwenpaw.node.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.R
import io.qwenpaw.node.data.model.EnvCatalogDto
import io.qwenpaw.node.data.model.EnvVarDto
import io.qwenpaw.node.ui.common.DestructiveActionDialog
import io.qwenpaw.node.ui.i18n.Texts

/** 服务端 `mutability` 取值，可热改的那一类。 */
private const val HOT_RUNTIME = "hot_runtime"

/**
 * 环境变量。
 *
 * 页面按官方控制台的结构常驻三段：已配置、实时配置、只读配置。
 * 前两段的值默认遮罩——服务端 `GET /api/envs` 返回明文（落盘加密、读时解密），
 * 局域网里任何能连上实例的人都能看到；客户端若不遮罩，等于把密钥摊在屏幕上。
 * 只读项不含用户密钥，且用户点进来往往正是为了核对值，因此在弹窗里直接显示明文。
 *
 * 删除与恢复默认都走二次确认：这两个动作一旦误触，密钥会直接消失或被打回默认值，
 * 而服务端没有回收站。
 */
@Composable
internal fun EnvironmentsSection(
    vars: List<EnvVarDto>,
    catalog: List<EnvCatalogDto>,
    loading: Boolean,
    error: String?,
    onSave: (Map<String, String>) -> Unit,
    /** `QWENPAW_AUTH_ENABLED` 单独走 PUT 合并写入。 */
    onSaveAuthEnabled: (String) -> Unit = {},
    onDelete: (String) -> Unit,
    onReset: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 展开显示明文的那一项。放在页面本地而非 ViewModel：退出页面就该忘掉。
    var revealed by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<EnvVarDto?>(null) }
    var adding by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<EnvVarDto?>(null) }
    var pendingReset by remember { mutableStateOf<EnvVarDto?>(null) }
    var viewingReadonly by remember { mutableStateOf<EnvCatalogDto?>(null) }
    var pendingAuthValue by remember { mutableStateOf<String?>(null) }
    val authKey = "QWENPAW_AUTH_ENABLED"
    val authVar = vars.firstOrNull { it.key == authKey }

    val specByKey = remember(catalog) { catalog.associateBy { it.key } }
    val liveSpecs = remember(catalog) { catalog.filter { it.mutability == HOT_RUNTIME } }
    val readonlySpecs = remember(catalog) { catalog.filter { it.mutability != HOT_RUNTIME } }

    Column(
        modifier = modifier
            .fillMaxWidth(),
    ) {
        SectionHeader(
            stringResource(R.string.env_header_auth),
            stringResource(R.string.env_desc_auth_enabled),
        )
        Card {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !loading) {
                        editing = EnvVarDto(key = authKey, value = authVar?.value.orEmpty())
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(authKey, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = if (authVar == null) {
                            stringResource(R.string.env_auth_unset)
                        } else {
                            stringResource(R.string.env_auth_set)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = stringResource(R.string.action_edit),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }

        // 鉴权开关在上方常驻显示；已配置列表里不再重复这一行。
        val visibleVars = vars.filter { it.key != authKey }
        SectionHeader(
            stringResource(R.string.env_header_configured),
            pluralStringResource(R.plurals.env_count, visibleVars.size, visibleVars.size),
        )
        Card {
            if (visibleVars.isEmpty()) {
                Text(
                    text = if (loading) {
                        stringResource(R.string.action_loading)
                    } else {
                        stringResource(R.string.env_empty_custom)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
                )
                Divider()
            } else {
                visibleVars.forEach { v ->
                    val spec = specByKey[v.key]
                    EnvRow(
                        envVar = v,
                        editable = spec?.editable ?: true,
                        live = spec != null && spec.mutability == HOT_RUNTIME,
                        revealed = revealed == v.key,
                        onToggleReveal = { revealed = if (revealed == v.key) null else v.key },
                        onEdit = { editing = v },
                        onReset = { pendingReset = v },
                        // 内置变量只能重置：DELETE 会返回 400「Known variable must be reset」。
                        onDelete = { pendingDelete = v }.takeIf { spec == null },
                        resetLabel = if (spec != null) stringResource(R.string.env_action_reset) else null,
                    )
                    Divider()
                }
            }
            ActionRow(text = stringResource(R.string.env_action_add), onClick = { adding = true })
        }

        SectionHeader(
            stringResource(R.string.env_header_live),
            stringResource(R.string.env_live_hint),
        )
        Card {
            if (liveSpecs.isEmpty()) {
                Text(
                    text = if (loading) {
                        stringResource(R.string.action_loading)
                    } else {
                        stringResource(R.string.env_empty_live)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
                )
            } else {
                liveSpecs.forEachIndexed { index, spec ->
                    LiveEnvRow(
                        spec = spec,
                        configured = vars.any { it.key == spec.key },
                        revealed = revealed == spec.key,
                        onToggleReveal = { revealed = if (revealed == spec.key) null else spec.key },
                        onEdit = {
                            editing = EnvVarDto(key = spec.key, value = spec.effectiveValue)
                        },
                    )
                    if (index != liveSpecs.lastIndex) Divider()
                }
            }
        }

        SectionHeader(
            stringResource(R.string.env_header_readonly),
            stringResource(R.string.env_readonly_tag),
        )
        Card {
            if (readonlySpecs.isEmpty()) {
                Text(
                    text = if (loading) {
                        stringResource(R.string.action_loading)
                    } else {
                        stringResource(R.string.env_empty_readonly)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
                )
            } else {
                readonlySpecs.forEachIndexed { index, spec ->
                    ReadonlyEnvRow(spec = spec, onClick = { viewingReadonly = spec })
                    if (index != readonlySpecs.lastIndex) Divider()
                }
            }
        }

        SectionHeader(stringResource(R.string.env_header_notes))
        Text(
            text = stringResource(R.string.env_notes_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
    }

    editing?.let { target ->
        EnvEditDialog(
            envVar = target,
            onDismiss = { editing = null },
            onSave = { value ->
                if (target.key == authKey) {
                    if (value.trim().equals("true", ignoreCase = true)) {
                        // 先确认再写：写成功后本页会立刻失去访问。
                        pendingAuthValue = value.trim()
                    } else {
                        onSaveAuthEnabled(value)
                    }
                } else {
                    onSave(mapOf(target.key to value))
                }
                editing = null
            },
        )
    }

    pendingAuthValue?.let { value ->
        AlertDialog(
            onDismissRequest = { pendingAuthValue = null },
            title = { Text(stringResource(R.string.env_auth_confirm_title)) },
            text = { Text(stringResource(R.string.env_auth_confirm_message)) },
            confirmButton = {
                Text(
                    text = stringResource(R.string.env_auth_confirm),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .clickable {
                            pendingAuthValue = null
                            onSaveAuthEnabled(value)
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            },
            dismissButton = {
                Text(
                    text = stringResource(R.string.action_cancel),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clickable { pendingAuthValue = null }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            },
        )
    }

    if (adding) {
        EnvAddDialog(
            existing = vars,
            catalog = catalog,
            onDismiss = { adding = false },
            onSave = { key, value ->
                onSave(mapOf(key to value))
                adding = false
            },
        )
    }

    pendingDelete?.let { target ->
        DestructiveActionDialog(
            title = stringResource(R.string.env_delete_title),
            message = stringResource(R.string.env_delete_message),
            impacts = listOf(
                target.key,
                stringResource(R.string.env_current_value, maskValue(target.value)),
            ),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                onDelete(target.key)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }

    pendingReset?.let { target ->
        DestructiveActionDialog(
            title = stringResource(R.string.env_action_reset),
            message = stringResource(R.string.env_reset_message),
            impacts = listOf(
                target.key,
                stringResource(R.string.env_current_value, maskValue(target.value)),
            ),
            confirmLabel = stringResource(R.string.env_action_reset),
            confirmDanger = false,
            onConfirm = {
                onReset(target.key)
                pendingReset = null
            },
            onDismiss = { pendingReset = null },
        )
    }

    viewingReadonly?.let { spec ->
        ReadonlyEnvDialog(spec = spec, onDismiss = { viewingReadonly = null })
    }
}

/**
 * 实时配置的一行。整行可点，点击编辑；值默认遮罩，点值才显示。
 *
 * `configured` 为真说明这一项已被用户改过，它同时也会出现在「已配置」里。
 * 两处都显示是刻意的：已配置段反映落盘的完整清单，这一段列出服务端声明的可改项，
 * 内容不随改动增删，便于对照。
 */
@Composable
private fun LiveEnvRow(
    spec: EnvCatalogDto,
    configured: Boolean,
    revealed: Boolean,
    onToggleReveal: () -> Unit,
    onEdit: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = spec.key,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (configured) {
                    Spacer(Modifier.width(6.dp))
                    MiniTag(stringResource(R.string.env_tag_modified))
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = if (revealed) spec.effectiveValue else maskValue(spec.effectiveValue),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(onClick = onToggleReveal),
            )
        }
        Spacer(Modifier.width(8.dp))
        SmallAction(text = stringResource(R.string.action_edit), onClick = onEdit)
    }
}

/**
 * 只读配置的一行。整行可点，打开详情弹窗。
 *
 * 值不做遮罩：这些项由服务端在启动时确定，不含用户密钥。
 */
@Composable
private fun ReadonlyEnvRow(spec: EnvCatalogDto, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = spec.key,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = if (spec.effectiveValue.isEmpty()) {
                    stringResource(R.string.env_value_empty)
                } else {
                    spec.effectiveValue
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.env_view),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}

/** 只读项的详情弹窗：提示语、当前值、说明与修改方式。 */
@Composable
private fun ReadonlyEnvDialog(spec: EnvCatalogDto, onDismiss: () -> Unit) {
    val copy = envSpecCopy(spec.key)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(spec.key, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                Text(
                    text = stringResource(READONLY_HINT),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                DetailLine(
                    stringResource(R.string.env_detail_current),
                    if (spec.effectiveValue.isEmpty()) {
                        stringResource(R.string.env_value_empty)
                    } else {
                        spec.effectiveValue
                    },
                )
                DetailLine(
                    stringResource(R.string.env_detail_default),
                    if (spec.default.isEmpty()) {
                        stringResource(R.string.env_value_empty)
                    } else {
                        spec.default
                    },
                )
                DetailLine(stringResource(R.string.env_detail_source), envSourceText(spec.source))
                readonlyReasonText(spec.readonlyReasonCode)?.let {
                    DetailLine(stringResource(R.string.env_detail_readonly_reason), it)
                }
                DetailLine(
                    stringResource(R.string.env_detail_description),
                    copy?.let { stringResource(it.description) }
                        ?: stringResource(R.string.env_description_missing),
                )
                DetailLine(
                    stringResource(R.string.env_detail_how_to_modify),
                    copy?.let { stringResource(it.howToModify) }
                        ?: stringResource(R.string.env_how_to_modify_missing),
                )
            }
        },
        confirmButton = {
            Text(
                text = stringResource(R.string.action_close),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        },
    )
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 3.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 行内的小标签，用于标明「实时配置」「已修改」这类状态。 */
@Composable
private fun MiniTag(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/**
 * 新增自定义变量。
 *
 * 服务端没有 `POST /api/envs`，新增走的是 `PATCH /api/envs` 的合并语义：
 * 提交一个此前不存在的 key 即等于创建，未提交的已有项不受影响。
 *
 * 校验放在本地做，理由是逐条都能给出比服务端英文 `detail` 更具体的中文提示；
 * 本地拦不住的（例如新增的 key 恰好是内置的数值型变量、值格式不符）
 * 仍然提交，由服务端 400 的 `detail` 经 [io.qwenpaw.node.data.remote.ErrorText] 显示回页面。
 */
@Composable
private fun EnvAddDialog(
    existing: List<EnvVarDto>,
    catalog: List<EnvCatalogDto>,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var keyText by remember { mutableStateOf("") }
    var valueText by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    // 只在按下保存后才提示：边输入边报「变量名只能包含…」会在用户还没打完时就飘红。
    var submitted by remember { mutableStateOf(false) }

    val key = keyText.trim()
    val keyError = if (submitted) validateNewEnvKey(key, existing, catalog) else null

    fun commit() {
        submitted = true
        if (validateNewEnvKey(key, existing, catalog) == null) onSave(key, valueText)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.env_action_add), style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                OutlinedTextField(
                    value = keyText,
                    onValueChange = { keyText = it; submitted = false },
                    singleLine = true,
                    label = { Text(stringResource(R.string.env_field_name)) },
                    placeholder = { Text(stringResource(R.string.env_field_name_placeholder)) },
                    isError = keyError != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                keyError?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 4.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                // 值可以为空：服务端接受空字符串，列表里会显示「（空）」，与已有项一致。
                OutlinedTextField(
                    value = valueText,
                    onValueChange = { valueText = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.env_field_value)) },
                    visualTransformation = if (visible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions.Default,
                    trailingIcon = {
                        Text(
                            text = if (visible) {
                                stringResource(R.string.env_hide)
                            } else {
                                stringResource(R.string.env_show)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier
                                .clickable { visible = !visible }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Text(
                text = stringResource(R.string.action_save),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier
                    .clickable { commit() }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        },
        dismissButton = {
            Text(
                text = stringResource(R.string.action_cancel),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        },
    )
}

/**
 * 新增变量的名称校验，与服务端 `_validate_updates` 一一对应。
 *
 * 返回 null 表示可提交。规则来源：
 * - `registry.py:16` 的 `^[A-Za-z_][A-Za-z0-9_]*$`；
 * - `registry.py:199-212`：`QWENPAW_` 前缀必须全大写，`editable=false` 的内置项不可写；
 * - `envs.py:52-71`：同一请求内 strip 后大小写冲突会 400。
 *
 * 只读判定排在重名判定之前：`catalog` 列出了全部 17 个内置项（含未配置的），
 * 若先查重，只读项会得到「已存在」这句无用提示，用户看不出它其实是被服务端锁住的。
 *
 * 与 `existing` 比对按 `uppercase()` 而不是完全相同：服务端就是按大小写不敏感查重的，
 * `path` 与 `PATH` 会撞在一起。
 *
 * **未配置的内置可写项允许新增**：这类项在 `catalog` 里有、在 `vars` 里没有，
 * 「新增」是覆盖其默认值的唯一入口，不能当成重名挡掉。
 */
internal fun validateNewEnvKey(
    key: String,
    existing: List<EnvVarDto>,
    catalog: List<EnvCatalogDto>,
): String? {
    if (key.isBlank()) return Texts.get(R.string.env_validate_name_required)
    if (!Regex("^[A-Za-z_][A-Za-z0-9_]*$").matches(key)) {
        return Texts.get(R.string.env_validate_name_charset)
    }
    if (key.uppercase().startsWith("QWENPAW_") && key != key.uppercase()) {
        return Texts.get(R.string.env_validate_uppercase)
    }
    val spec = catalog.firstOrNull { it.key == key }
    if (spec != null && !spec.editable) {
        return Texts.get(R.string.env_validate_readonly)
    }
    if (existing.any { it.key.uppercase() == key.uppercase() }) {
        return Texts.get(R.string.env_validate_exists, key)
    }
    return null
}

@Composable
private fun EnvRow(
    envVar: EnvVarDto,
    editable: Boolean,
    live: Boolean,
    revealed: Boolean,
    onToggleReveal: () -> Unit,
    onEdit: () -> Unit,
    onReset: () -> Unit,
    onDelete: (() -> Unit)?,
    resetLabel: String?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = editable, onClick = onEdit)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = envVar.key,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                // 标记这一项同时出现在下方「实时配置」段里，避免用户以为两处是不同变量。
                if (live) {
                    Spacer(Modifier.width(6.dp))
                    MiniTag(stringResource(R.string.env_header_live))
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = if (revealed) envVar.value else maskValue(envVar.value),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(onClick = onToggleReveal),
            )
        }
        if (resetLabel != null) {
            Spacer(Modifier.width(8.dp))
            SmallAction(text = resetLabel, onClick = onReset)
        }
        if (onDelete != null) {
            Spacer(Modifier.width(8.dp))
            SmallAction(text = stringResource(R.string.action_delete), onClick = onDelete, danger = true)
        }
    }
}

@Composable
private fun SmallAction(
    text: String,
    onClick: () -> Unit,
    danger: Boolean = false,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
        )
    }
}

@Composable
private fun EnvEditDialog(
    envVar: EnvVarDto,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    // 编辑时也先遮罩：用户点了「编辑」才填值，此时明文是他自己的选择。
    var text by remember(envVar.key) { mutableStateOf(envVar.value) }
    var visible by remember(envVar.key) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(envVar.key, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    visualTransformation = if (visible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions.Default,
                    trailingIcon = {
                        Text(
                            text = if (visible) {
                                stringResource(R.string.env_hide)
                            } else {
                                stringResource(R.string.env_show)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier
                                .clickable { visible = !visible }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Text(
                text = stringResource(R.string.action_save),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier
                    .clickable { onSave(text) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        },
        dismissButton = {
            Text(
                text = stringResource(R.string.action_cancel),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        },
    )
}

/**
 * 遮罩值。
 *
 * 长度固定 8 个圆点而不是跟随原值：圆点数量会暴露长度，
 * 而长度本身对猜密钥是有用信息。
 */
internal fun maskValue(value: String): String =
    if (value.isEmpty()) Texts.get(R.string.env_value_empty) else "••••••••"

/** 只读判定：`editable=false` 的项不允许提交。 */
internal fun isEnvEditable(spec: EnvCatalogDto?): Boolean = spec?.editable ?: true