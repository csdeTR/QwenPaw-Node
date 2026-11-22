package io.qwenpaw.node.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.R
import io.qwenpaw.node.data.model.PoolModelDto

/**
 * 单个模型的详细设置。
 *
 * 文案与 Web 控制台的模型配置页对齐；思考能力按「自动/未知、不支持、离散档位、
 * 数值预算」四类显示，只有数值预算才显示预算输入。
 */
@Composable
fun ModelDetailScreen(
    state: ModelsAdminUiState,
    model: PoolModelDto,
    draft: ModelDetailDraft,
    onChange: ((ModelDetailDraft) -> ModelDetailDraft) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

        Text(stringResource(R.string.models_admin_model_type), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DetailChip(
                stringResource(R.string.models_model_type_chat),
                draft.modelType == "chat",
            ) { onChange { it.copy(modelType = "chat") } }
            DetailChip(
                stringResource(R.string.models_model_type_decision),
                draft.modelType == "decision",
            ) { onChange { it.copy(modelType = "decision") } }
        }

        Text(stringResource(R.string.models_model_capability), style = MaterialTheme.typography.bodyMedium)
        CapabilityRow(
            label = stringResource(R.string.models_model_cap_image),
            value = draft.supportsImage,
        ) { next -> onChange { it.copy(supportsImage = next) } }
        CapabilityRow(
            label = stringResource(R.string.models_model_cap_audio),
            value = draft.supportsAudio,
        ) { next -> onChange { it.copy(supportsAudio = next) } }
        CapabilityRow(
            label = stringResource(R.string.models_model_cap_video),
            value = draft.supportsVideo,
        ) { next -> onChange { it.copy(supportsVideo = next) } }
        CapabilityRow(
            label = stringResource(R.string.models_model_cap_tool),
            value = draft.supportsToolCalling,
        ) { next -> onChange { it.copy(supportsToolCalling = next) } }

        OutlinedTextField(
            value = draft.maxInput,
            onValueChange = { value -> onChange { it.copy(maxInput = value) } },
            label = { Text(stringResource(R.string.models_model_max_input)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            supportingText = {
                Column {
                    Text(stringResource(R.string.models_model_max_input_hint))
                    val effective = model.effectiveMaxInputLength ?: model.maxInputLength
                    if (effective != null) {
                        Text(
                            stringResource(
                                R.string.models_model_max_input_effective,
                                effective.toString(),
                            ),
                        )
                    }
                    (model.automaticMaxInputLength ?: model.maxInputLengthAutoDetected)?.let { automatic ->
                        Text(
                            stringResource(
                                R.string.models_model_max_input_auto,
                                automatic.toString(),
                            ),
                        )
                    }
                    contextSourceLabelRes(model.contextLengthSource)?.let { source ->
                        Text(
                            stringResource(
                                R.string.models_model_max_input_source,
                                stringResource(source),
                            ),
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (draft.maxInput.isNotBlank()) {
            TextButton(onClick = { onChange { it.copy(maxInput = "") } }) {
                Text(stringResource(R.string.models_model_max_input_reset))
            }
        }

        OutlinedTextField(
            value = draft.maxOutput,
            onValueChange = { value -> onChange { it.copy(maxOutput = value) } },
            label = { Text(stringResource(R.string.models_model_max_output)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            supportingText = {
                Column {
                    Text(stringResource(R.string.models_model_max_output_hint))
                    model.maxOutputLength?.let { capability ->
                        Text(
                            stringResource(
                                R.string.models_model_output_capability,
                                capability.toString(),
                            ),
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (draft.maxOutput.isNotBlank()) {
            TextButton(onClick = { onChange { it.copy(maxOutput = "") } }) {
                Text(stringResource(R.string.models_model_max_output_reset))
            }
        }

        Text(stringResource(R.string.models_model_thinking), style = MaterialTheme.typography.bodyMedium)
        Text(
            text = stringResource(R.string.models_model_thinking_declaration),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.models_model_thinking_declaration_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf("unknown", "unsupported", "effort", "budget").forEach { kind ->
                DetailChip(
                    label = stringResource(thinkingKindLabelRes(kind)),
                    selected = draft.thinkingKind == kind,
                ) {
                    onChange { it.withThinkingKind(kind) }
                }
            }
        }

        val editable = draft.thinkingKind == "effort" || draft.thinkingKind == "budget"
        if (!editable) {
            Text(
                text = stringResource(R.string.models_model_thinking_cannot_edit),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = stringResource(R.string.models_model_thinking_wire),
                style = MaterialTheme.typography.labelMedium,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val wires = if (draft.thinkingKind == "budget") {
                    listOf("native", "compat_budget", "anthropic_budget", "gemini_budget")
                } else {
                    listOf("native", "compat_effort", "anthropic_adaptive", "gemini_level")
                }
                wires.forEach { wire ->
                    val labelRes = thinkingWireLabelRes(wire)
                    DetailChip(
                        label = if (labelRes != null) stringResource(labelRes) else wire,
                        selected = draft.thinkingWire == wire,
                    ) {
                        onChange { it.copy(thinkingWire = wire) }
                    }
                }
            }

            if (draft.thinkingKind == "effort") {
                Text(
                    text = stringResource(R.string.models_model_thinking_efforts),
                    style = MaterialTheme.typography.labelMedium,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val efforts = model.reasoningEffortOptions.ifEmpty {
                        listOf("minimal", "low", "medium", "high", "xhigh", "max")
                    }
                    efforts.forEach { effort ->
                        val labelRes = effortLabelRes(effort)
                        DetailChip(
                            label = if (labelRes != null) stringResource(labelRes) else effort,
                            selected = effort in draft.thinkingEfforts,
                        ) {
                            onChange {
                                it.copy(
                                    thinkingEfforts = if (effort in it.thinkingEfforts) {
                                        it.thinkingEfforts - effort
                                    } else {
                                        it.thinkingEfforts + effort
                                    },
                                )
                            }
                        }
                    }
                }
            }

            if (draft.thinkingKind == "budget") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = draft.thinkingBudgetMin,
                        onValueChange = { value -> onChange { it.copy(thinkingBudgetMin = value) } },
                        label = { Text(stringResource(R.string.models_model_thinking_budget_min)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = draft.thinkingBudgetMax,
                        onValueChange = { value -> onChange { it.copy(thinkingBudgetMax = value) } },
                        label = { Text(stringResource(R.string.models_model_thinking_budget_max)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.models_model_thinking_can_off),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = draft.thinkingSupportsOff,
                        onCheckedChange = { value -> onChange { it.copy(thinkingSupportsOff = value) } },
                    )
                }
            }

            Text(
                text = stringResource(R.string.models_model_thinking_default),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (draft.thinkingKind == "effort") {
                    DetailChip(
                        stringResource(R.string.models_model_thinking_default_value),
                        draft.thinkingEnabled == null && draft.reasoningEffort.isBlank(),
                    ) { onChange { it.copy(thinkingEnabled = null, reasoningEffort = "") } }
                    DetailChip(
                        stringResource(R.string.models_model_thinking_off),
                        draft.thinkingEnabled == false,
                    ) { onChange { it.copy(thinkingEnabled = false, reasoningEffort = "") } }
                    val efforts = model.reasoningEffortOptions.ifEmpty {
                        listOf("low", "medium", "high")
                    }
                    efforts.forEach { effort ->
                        val labelRes = effortLabelRes(effort)
                        DetailChip(
                            label = if (labelRes != null) stringResource(labelRes) else effort,
                            selected = draft.thinkingEnabled != false &&
                                draft.reasoningEffort == effort,
                        ) {
                            onChange {
                                it.copy(
                                    thinkingEnabled = true,
                                    reasoningEffort = effort,
                                    thinkingBudget = "",
                                )
                            }
                        }
                    }
                } else {
                    DetailChip(
                        stringResource(R.string.models_model_thinking_default_value),
                        draft.thinkingEnabled == null && draft.thinkingBudget.isBlank(),
                    ) { onChange { it.copy(thinkingEnabled = null, thinkingBudget = "") } }
                    DetailChip(
                        stringResource(R.string.models_model_thinking_off),
                        draft.thinkingEnabled == false,
                    ) { onChange { it.copy(thinkingEnabled = false, thinkingBudget = "") } }
                }
            }
            if (draft.thinkingKind == "budget") {
                OutlinedTextField(
                    value = draft.thinkingBudget,
                    onValueChange = { value ->
                        onChange {
                            it.copy(
                                thinkingBudget = value,
                                thinkingEnabled = if (value.isBlank()) null else true,
                            )
                        }
                    },
                    label = { Text(stringResource(R.string.models_model_thinking_budget_value)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.models_model_relay_reasoning),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.models_model_relay_reasoning_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = draft.relayReasoning,
                onCheckedChange = { value -> onChange { it.copy(relayReasoning = value) } },
            )
        }

        Text(
            text = stringResource(R.string.models_model_advanced),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(R.string.models_model_generate_kwargs),
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            text = stringResource(R.string.models_model_generate_kwargs_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = draft.generateKwargs,
            onValueChange = { value -> onChange { it.copy(generateKwargs = value) } },
            minLines = 3,
            maxLines = 8,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun CapabilityRow(
    label: String,
    value: Boolean?,
    onChange: (Boolean?) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            DetailChip(stringResource(R.string.models_model_cap_auto), value == null) { onChange(null) }
            DetailChip(stringResource(R.string.models_model_cap_yes), value == true) { onChange(true) }
            DetailChip(stringResource(R.string.models_model_cap_no), value == false) { onChange(false) }
        }
    }
}

@Composable
private fun DetailChip(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
