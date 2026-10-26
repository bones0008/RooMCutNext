package com.RooMCutNext.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.RooMCutNext.R
import com.RooMCutNext.ai.AiProvider
import com.RooMCutNext.ai.AiSettings

/**
 * v1.6.7: AI 模型设置对话框 —— 从 AiChatPanel 抽出为共享组件。
 *
 * 同一份表单两处入口：
 *  - 首页顶栏齿轮（一次性配置好，进编辑器 AI 面板零打扰）；
 *  - AI 面板输入行齿轮（剪辑中途随时改）。
 * 读写都走宿主传入的 onDismiss/onSave（最终落到 SettingsRepository，
 * 本机 SharedPreferences 持久化，一次保存全程生效）。
 *
 * v1.6.10: 表单加 verticalScroll —— v1.6.6~v1.6.9 连续三版向对话框加了
 * 画面理解三档、画面方式两档、视频格式两档与提示文案后，内容总高已超
 * 小屏可用高度，AlertDialog 的 text 槽不滚动导致底部被硬裁剪；与
 * AiSkillsSheet 同一范式（fillMaxWidth + verticalScroll），超出即可上下拖动。
 */
@Composable
internal fun AiSettingsDialog(
    initial: AiSettings,
    onDismiss: () -> Unit,
    onSave: (AiSettings) -> Unit,
) {
    var providerId by remember { mutableStateOf(initial.providerId) }
    var baseUrl by remember { mutableStateOf(initial.baseUrlOverride) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var model by remember { mutableStateOf(initial.model) }
    // v1.6.6 画面感知：auto=识别到多模态模型才发帧 / on=始终 / off=从不。
    var visionMode by remember { mutableStateOf(initial.visionMode) }
    // v1.6.9 画面方式：关键帧 / 整段视频直传（原生视频模型）。
    var visionStyle by remember { mutableStateOf(initial.visionStyle) }
    var videoPartStyle by remember { mutableStateOf(initial.videoPartStyle) }
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
                        visionMode = visionMode,
                        visionStyle = visionStyle,
                        videoPartStyle = videoPartStyle,
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
            // v1.6.10: 内容超出屏高时可上下拖动（此前被硬裁剪，见真机截图反馈）。
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
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

                // ---- v1.6.6 画面感知（多模态开关）----
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.ai_settings_vision),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(
                        AiSettings.VISION_AUTO to R.string.ai_vision_auto,
                        AiSettings.VISION_ON to R.string.ai_vision_on,
                        AiSettings.VISION_OFF to R.string.ai_vision_off,
                    ).forEach { (mode, res) ->
                        FilterChip(
                            selected = visionMode == mode,
                            onClick = { visionMode = mode },
                            label = { Text(stringResource(res), style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
                Text(
                    stringResource(R.string.ai_settings_vision_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // ---- v1.6.9 画面方式（关键帧 / 整段视频）----
                if (visionMode != AiSettings.VISION_OFF) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.ai_settings_vision_style),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf(
                            AiSettings.VISION_STYLE_FRAMES to R.string.ai_vision_style_frames,
                            AiSettings.VISION_STYLE_VIDEO to R.string.ai_vision_style_video,
                        ).forEach { (style, res) ->
                            FilterChip(
                                selected = visionStyle == style,
                                onClick = { visionStyle = style },
                                label = { Text(stringResource(res), style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }
                    if (visionStyle == AiSettings.VISION_STYLE_VIDEO) {
                        Text(
                            stringResource(R.string.ai_settings_video_format),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            listOf(
                                AiSettings.VIDEO_PART_INPUT_VIDEO,
                                AiSettings.VIDEO_PART_VIDEO_URL,
                            ).forEach { part ->
                                FilterChip(
                                    selected = videoPartStyle == part,
                                    onClick = { videoPartStyle = part },
                                    label = {
                                        Text(
                                            part,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                        )
                                    },
                                )
                            }
                        }
                    }
                    Text(
                        stringResource(R.string.ai_settings_video_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
