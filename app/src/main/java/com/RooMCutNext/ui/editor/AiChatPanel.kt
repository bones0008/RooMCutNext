package com.RooMCutNext.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.RooMCutNext.R
import com.RooMCutNext.ai.AiChatEntry
import com.RooMCutNext.ai.AiProvider
import com.RooMCutNext.ai.AiSettings
import com.RooMCutNext.ai.AiChatUiState

/**
 * AI 剪辑（v1.6.0）— 对话面板。
 *
 * 宿主为 ToolPanelHost 的自管滚动分支（消息列表 + 固定输入行）。
 * 与其它工具面板不同，本面板不要求选中任何目标：agent 通过工具
 * 直接读项目状态，用户只需要描述想要的剪辑结果。
 */
@Composable
fun AiChatPanel(vm: EditorViewModel, modifier: Modifier = Modifier) {
    val aiState by vm.aiChat.state.collectAsState()
    Column(modifier = modifier.fillMaxSize()) {

        // ---- 未配置引导 ----
        if (!aiState.settings.ready) {
            AiOnboardingCard(onConfigure = { vm.aiChat.openSettings(true) })
        }

        // ---- 消息流 ----
        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 12.dp, vertical = 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(aiState.entries.size) { i ->
                when (val e = aiState.entries[i]) {
                    is AiChatEntry.User -> UserBubble(e.text)
                    is AiChatEntry.Assistant -> AssistantBubble(e.text)
                    is AiChatEntry.ToolEvent -> ToolEventBlock(e)
                    is AiChatEntry.Note -> NoteLine(e)
                }
            }
            item { Spacer(Modifier.height(4.dp)) }
        }
        // 新消息自动滚到底部。
        LaunchedEffect(aiState.entries.size) {
            if (aiState.entries.isNotEmpty()) {
                runCatching { listState.animateScrollToItem(aiState.entries.size) }
            }
        }

        // ---- 忙碌指示 ----
        if (aiState.busy) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    Modifier.width(14.dp).height(14.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    aiState.busyTool?.let {
                        stringResource(R.string.ai_busy_tool_fmt, it)
                    } ?: stringResource(R.string.ai_busy_thinking),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LinearProgressIndicator(
                Modifier.fillMaxWidth().height(2.dp),
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // ---- 提案卡 ----
        aiState.proposal?.let { proposal ->
            ProposalCard(
                changeCount = proposal.changeCount,
                journal = proposal.journal,
                summary = proposal.summary,
                seekSec = proposal.seekSec,
                stale = proposal.stale,
                onApply = { vm.aiChat.applyProposal() },
                onDiscard = { vm.aiChat.discardProposal() },
            )
        }

        // ---- 输入行 ----
        AiInputRow(enabled = !aiState.busy, onSend = { vm.aiChat.send(it) })

        // ---- 设置 ----
        if (aiState.settingsOpen) {
            AiSettingsDialog(
                initial = aiState.settings,
                onDismiss = { vm.aiChat.openSettings(false) },
                onSave = { vm.aiChat.updateSettings(it) },
            )
        }
    }
}

// ---------------------------------------------------------------------
// 片段
// ---------------------------------------------------------------------

@Composable
private fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(topStart = 14.dp, topEnd = 4.dp, bottomStart = 14.dp, bottomEnd = 14.dp),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Text(
                text,
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun AssistantBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Icon(
            Icons.Filled.AutoAwesome, null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 2.dp).width(16.dp).height(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            Modifier.widthIn(max = 300.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ToolEventBlock(e: AiChatEntry.ToolEvent) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            e.lines.forEach { line ->
                Text(
                    (if (line.ok) "✓ " else "✗ ") + line.label,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (line.ok) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun NoteLine(e: AiChatEntry.Note) {
    Text(
        e.text,
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = if (e.isError) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun ProposalCard(
    changeCount: Int,
    journal: List<String>,
    summary: String,
    seekSec: Double?,
    stale: Boolean,
    onApply: () -> Unit,
    onDiscard: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                stringResource(R.string.ai_proposal_title, changeCount),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            if (journal.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                journal.take(10).forEachIndexed { i, line ->
                    Text(
                        "${i + 1}. $line",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    )
                }
                if (journal.size > 10) {
                    Text(
                        "… +${journal.size - 10}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                    )
                }
            }
            if (summary.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            if (stale) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.ai_project_changed_warn),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            seekSec?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(R.string.ai_proposal_seek_fmt, "%.1f".format(it)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDiscard) {
                    Text(stringResource(R.string.ai_proposal_discard))
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = onApply) {
                    Text(stringResource(R.string.ai_proposal_apply))
                }
            }
        }
    }
}

@Composable
private fun AiInputRow(enabled: Boolean, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    Row(
        Modifier
            .fillMaxWidth()
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.ai_input_hint)) },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = {
                if (text.isNotBlank()) {
                    onSend(text); text = ""; keyboard?.hide()
                }
            }),
        )
        Spacer(Modifier.width(6.dp))
        IconButton(
            onClick = {
                if (text.isNotBlank()) {
                    onSend(text); text = ""; keyboard?.hide()
                }
            },
            enabled = enabled && text.isNotBlank(),
        ) {
            Icon(
                Icons.Filled.Send,
                stringResource(R.string.ai_send),
                tint = if (enabled && text.isNotBlank()) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun AiOnboardingCard(onConfigure: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(12.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.AutoAwesome, null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(18.dp).height(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.ai_hint_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.ai_hint_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row {
                Spacer(Modifier.weight(1f))
                Button(onClick = onConfigure) {
                    Icon(Icons.Filled.Key, null, Modifier.width(16.dp).height(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.ai_hint_config))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------
// 设置对话框
// ---------------------------------------------------------------------

@Composable
private fun AiSettingsDialog(
    initial: AiSettings,
    onDismiss: () -> Unit,
    onSave: (AiSettings) -> Unit,
) {
    var providerId by remember { mutableStateOf(initial.providerId) }
    var baseUrl by remember { mutableStateOf(initial.baseUrlOverride) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var model by remember { mutableStateOf(initial.model) }
    val preset = AiProvider.byId(providerId) ?: AiProvider.PRESETS.first()

    // 局部 @Composable：stringResource 必须在可组合上下文中调用。
    @Composable
    fun providerLabel(id: String): String = when (id) {
        AiProvider.ZAI -> stringResource(R.string.ai_provider_zai)
        AiProvider.DEEPSEEK -> stringResource(R.string.ai_provider_deepseek)
        AiProvider.MOONSHOT -> stringResource(R.string.ai_provider_moonshot)
        AiProvider.OPENAI -> stringResource(R.string.ai_provider_openai)
        else -> stringResource(R.string.ai_provider_custom)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = {
                onSave(
                    AiSettings(
                        providerId = providerId,
                        baseUrlOverride = if (providerId == AiProvider.CUSTOM) baseUrl.trim() else "",
                        apiKey = apiKey.trim(),
                        model = model.trim(),
                    ),
                )
            }) { Text(stringResource(R.string.ai_settings_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ai_settings_cancel)) }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Settings, null, Modifier.width(18.dp).height(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.ai_settings_title))
            }
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.ai_settings_provider),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AiProvider.PRESETS.take(3).forEach { p ->
                        FilterChip(
                            selected = providerId == p.id,
                            onClick = {
                                providerId = p.id
                                if (p.defaultModel.isNotEmpty()) model = p.defaultModel
                            },
                            label = { Text(providerLabel(p.id), style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(AiProvider.OPENAI, AiProvider.CUSTOM).forEach { id ->
                        FilterChip(
                            selected = providerId == id,
                            onClick = {
                                providerId = id
                                val p = AiProvider.byId(id)
                                if (p != null && p.defaultModel.isNotEmpty()) model = p.defaultModel
                            },
                            label = { Text(providerLabel(id), style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }

                if (providerId == AiProvider.CUSTOM) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text(stringResource(R.string.ai_settings_base_url)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else if (preset.baseUrl.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        preset.baseUrl,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(stringResource(R.string.ai_settings_api_key)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (preset.keyUrl.isNotBlank()) {
                    Text(
                        stringResource(R.string.ai_settings_key_hint, preset.keyUrl),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text(stringResource(R.string.ai_settings_model)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (preset.candidateModels.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        preset.candidateModels.take(3).forEach { m ->
                            FilterChip(
                                selected = model == m,
                                onClick = { model = m },
                                label = { Text(m, style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.ai_settings_privacy),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}
