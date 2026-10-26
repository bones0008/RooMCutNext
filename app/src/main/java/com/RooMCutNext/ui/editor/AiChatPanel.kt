package com.RooMCutNext.ui.editor

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.RooMCutNext.R
import com.RooMCutNext.ai.AiChatEntry
import com.RooMCutNext.ai.AiChatUiState
import com.RooMCutNext.ai.CustomSkillCodec
import com.RooMCutNext.ai.EditSkill
import com.RooMCutNext.ai.EditSkills
import com.RooMCutNext.ui.settings.AiSettingsDialog
import kotlinx.coroutines.launch

// ===========================================================================
// AI 剪辑（v1.6.0）— v1.6.3 微信式布局。
//
// 真机反馈：v1.6.2 的“预览不缩、键盘只压对话区”在竖屏数学上不成立 —
// 对话区住在时间线区域（weight(1f) ≈ 屏高 1/3 ≈ 250dp），而键盘约
// 800dp，容器内的 windowInsetsPadding(ime) 想垫 800dp，可用高度被垫成
// 负值，消息列表和输入行整条被挤没（“输入栏全被挡住”）。
//
// v1.6.3 按用户描述的微信模式重做：
//  - AiChatOverlay — 对话内容做成半透明浮层，直接叠在视频显示区上
//    （底部对齐：最新气泡/提案卡贴播放栏上沿，视频上部完整露出），
//    配一个“显示/隐藏对话”开关，关闭即完整视频画面；
//  - AiBottomBar — 输入行独立成自适应高度底栏（忙碌行 + 输入行），
//    宿主主列统一 imePadding：键盘弹出整个界面上移（视频压缩），
//    输入行恒在键盘正上方 —— 收起即恢复（v1.6.2 的 stage-resize
//    重画暂停帧机制照旧处理恢复时的重画）；
//  - 播放栏（播放/快进/快退/到头/到尾）保持在视频下方不动；
//  - “配置模型”引导从大卡缩成单行小条（浮层最顶、最旧位置）。
//
// v1.6.5 技能库：输入框左端内嵌闪电按钮（leadingIcon，不占行宽 —
//  v1.6.3 刚验证过输入行宽度敏感，五个 48dp 按钮会把输入框挤到
//  140dp），弹出 [EditSkills] 技能列表（分类 + 一句话），点选即填入
//  输入框。技能本体随 system prompt 注入模型 —— 用户口语点名即可。
// ===========================================================================

/**
 * 对话浮层：叠在预览区（视频）上的半透明消息流。
 *
 * reverseLayout 底部对齐 — 消息少时贴着播放栏上沿、视频上部完整露出；
 * 消息多时整层可上下滚动。浮层显示期间消费手势（滚动/点按气泡），
 * 想直接操作视频（拖动 PiP、看完整画面）时用输入行左端的开关关掉。
 *
 * v1.6.11: 新增 onOpenSkills —— 空对话引导卡整卡可点直接打开技能库
 * （skillsOpen 状态提升到 EditorScreen，与 aiOverlayVisible 同模式）。
 */
@Composable
fun AiChatOverlay(
    vm: EditorViewModel,
    onOpenSkills: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val aiState by vm.aiChat.state.collectAsState()
    Box(modifier) {
        // 轻度渐变 scrim：顶部几乎透明（视频清晰），底部渐深（气泡可读）。
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0x2E000000), Color(0x52000000), Color(0x96000000))
                    )
                )
        )
        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            // reverseLayout：DSL 首项 = 视觉最底部（最新内容贴播放栏）。
            reverseLayout = true,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 10.dp, vertical = 6.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // 最新内容在 DSL 首（视觉底部）：消息倒序。
            // v1.6.4: 提案卡已删 —— 修改直接应用，撤销兜底（真机反馈
            // “为什么要弹出对话框确认？直接做就好了”）。
            val reversed = aiState.entries.asReversed()
            items(reversed.size) { i ->
                when (val e = reversed[i]) {
                    is AiChatEntry.User -> UserBubble(e.text)
                    is AiChatEntry.Assistant -> AssistantBubble(e.text)
                    is AiChatEntry.ToolEvent -> ToolEventBlock(e)
                    is AiChatEntry.Note -> NoteLine(e)
                }
            }
            // 最旧位置（视觉顶部）：未配置时的单行小条；已配置且还没
            // 聊过 → 使用引导卡（v1.6.11：技能库/上传入口两轮真机反馈
            // 找不到，可发现性救急），发过首条消息即自动消失。
            if (!aiState.settings.ready) {
                item { AiOnboardingBar(onConfigure = { vm.aiChat.openSettings(true) }) }
            } else if (aiState.entries.isEmpty()) {
                item { AiEmptyHintCard(onOpenSkills = onOpenSkills) }
            }
        }
        // 新消息 → 滚回视觉底部（reverseLayout 的 index 0）。
        LaunchedEffect(aiState.entries.size) {
            if (aiState.entries.isNotEmpty()) {
                runCatching { listState.animateScrollToItem(0) }
            }
        }
    }
}

/**
 * 输入底栏：忙碌行 + 输入行（对话浮层开关 / 输入框 / 发送 / 关闭）。
 * 自适应高度 — AI 模式下不再占用时间线的整块空间，让出的高度全部
 * 还给预览视频。键盘避让由宿主主列统一 imePadding（微信模式）。
 */
@Composable
fun AiBottomBar(
    vm: EditorViewModel,
    overlayVisible: Boolean,
    onToggleOverlay: () -> Unit,
    onClose: () -> Unit,
    skillsOpen: Boolean,
    onSkillsOpenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val aiState by vm.aiChat.state.collectAsState()
    // 关面板先收键盘，避免宿主恢复 imePadding 的瞬间布局抖动。
    val keyboard = LocalSoftwareKeyboardController.current
    fun closePanel() {
        keyboard?.hide()
        onClose()
    }
    // v1.6.5: 输入文本提升到底栏层 —— 技能面板点选后能直接填进来。
    var inputText by rememberSaveable { mutableStateOf("") }
    // v1.6.11: skillsOpen 提升到 EditorScreen（浮层引导卡也能打开）。
    // 此前是本组件私有 remember —— 空状态引导卡需要跨组件触发，
    // 与 aiOverlayVisible 同一提升模式，行为不变。
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding()
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // ---- 忙碌指示（仅 20dp 高）----
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

        // ---- 输入行 ----
        AiInputRow(
            enabled = !aiState.busy,
            overlayVisible = overlayVisible,
            onToggleOverlay = onToggleOverlay,
            text = inputText,
            onTextChange = { inputText = it },
            onOpenSkills = { onSkillsOpenChange(true) },
            onSend = {
                vm.aiChat.send(it)
                inputText = ""
            },
            onClose = ::closePanel,
            // v1.6.14: 语音输入的错误提示走全局 toast 通道（与导入/剪辑
            // 反馈同一条链路，不另建 UI）。
            onFlashToast = vm::flashToast,
        )

        // ---- 设置对话框（窗口级）----
        if (aiState.settingsOpen) {
            AiSettingsDialog(
                initial = aiState.settings,
                onDismiss = { vm.aiChat.openSettings(false) },
                onSave = { vm.aiChat.updateSettings(it) },
            )
        }

        // ---- v1.6.5 技能库（窗口级）----
        // v1.6.8：自定义技能同库展示（“自定义”分组，可删），列表来自
        // VM 的 customSkills flow —— 导入/删除后即时刷新。
        if (skillsOpen) {
            val custom by vm.customSkills.collectAsState()
            AiSkillsSheet(
                custom = custom,
                onImport = vm::importCustomSkills,
                onReadFile = vm::readSkillFileText,
                onDelete = vm::deleteCustomSkill,
                onDismiss = { onSkillsOpenChange(false) },
                onPick = { name ->
                    // 追加而非覆盖 —— 用户可能已打了半句细节，别替他删。
                    inputText = if (inputText.isBlank()) name else "$inputText，$name"
                    onSkillsOpenChange(false)
                },
            )
        }
    }
}

// ---------------------------------------------------------------------
// 浮层片段（半透明样式 — 直接叠在视频画面上，深底浅字保证可读）
// ---------------------------------------------------------------------

@Composable
private fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f),
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp),
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
            modifier = Modifier.padding(top = 4.dp).width(16.dp).height(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Surface(
            color = Color(0xD91E1E22),
            shape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Text(
                text,
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFF4EFF4),
            )
        }
    }
}

/**
 * v1.6.4: 工具事件改为通俗动作列表 —— 不再是等宽字体的
 * `split_clip(clip_id=c1 …)` 技术行（真机反馈“返回信息看着太高级”）。
 * 成功项柔和小字，失败项附一句短原因。
 */
@Composable
private fun ToolEventBlock(e: AiChatEntry.ToolEvent) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        e.lines.forEach { line ->
            Text(
                (if (line.ok) "· " else "⚠ ") + line.label,
                style = MaterialTheme.typography.labelSmall,
                color = if (line.ok) Color(0xCCFFFFFF) else Color(0xFFFFB4AB),
            )
        }
    }
}

@Composable
private fun NoteLine(e: AiChatEntry.Note) {
    Text(
        e.text,
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = if (e.isError) Color(0xFFFFB4AB) else Color(0xCCFFFFFF),
        textAlign = TextAlign.Center,
    )
}

/**
 * 未配置引导 — v1.6.3 从大卡缩成单行小条（真机反馈“配置模型的提示
 * 能不能做的小一点”）。整条可点，尾部齿轮同效。
 */
@Composable
private fun AiOnboardingBar(onConfigure: () -> Unit) {
    Surface(
        color = Color(0xD91E1E22),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .clickable(onClick = onConfigure)
                .padding(start = 14.dp, end = 4.dp)
                .height(40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.AutoAwesome, null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(16.dp).height(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.ai_onboarding_short),
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFFE6E1E6),
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Filled.Settings,
                stringResource(R.string.ai_hint_config),
                tint = Color(0xFFE6E1E6),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * v1.6.11: 空对话使用引导卡 —— 技能库/上传入口两轮真机反馈找不到
 * （入口在输入框左端，v1.6.5 上线以来一直存在但用户两次均未发现）。
 * 已配置且未发首条消息时显示于浮层顶部，**整卡可点直接打开技能库**
 * （顶部即"上传 / 导入自定义技能"），发过任意一条消息即自动消失。
 * 深底浅字与气泡同风格，叠在视频渐变 scrim 上保证可读。
 */
@Composable
private fun AiEmptyHintCard(onOpenSkills: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xD91E1E22))
            .clickable(onClick = onOpenSkills)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.AutoAwesome, null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.ai_empty_hint_title),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = Color(0xFFF4EFF4),
            )
        }
        Text(
            stringResource(R.string.ai_empty_hint_skills),
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xCCFFFFFF),
        )
        Text(
            stringResource(R.string.ai_empty_hint_examples),
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xCCFFFFFF),
        )
        Text(
            stringResource(R.string.ai_empty_hint_open),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

// ---------------------------------------------------------------------
// 输入行
// ---------------------------------------------------------------------

@Composable
private fun AiInputRow(
    enabled: Boolean,
    overlayVisible: Boolean,
    onToggleOverlay: () -> Unit,
    text: String,
    onTextChange: (String) -> Unit,
    onOpenSkills: () -> Unit,
    onSend: (String) -> Unit,
    onClose: () -> Unit,
    onFlashToast: (String) -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current

    // ---- v1.6.14: 语音输入 ----
    // 系统 SpeechRecognizer（在线优先/离线兑底，见 VoiceInput.kt）。
    // 识别结果（partial 与 final）填入输入框由用户确认后手动发送 ——
    // 不自动发送，防“听岔了直接执行错误剪辑指令”。
    val voice = rememberVoiceInput(
        onPartial = onTextChange,
        onFinal = onTextChange,
        onError = { code ->
            voiceErrorHint(code)?.let { onFlashToast(context.getString(it)) }
        },
    )
    // 权限门与旁白录音同一条路：未授权则请求，授予后立即开始听。
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) voice.toggle(text)
        else onFlashToast(context.getString(R.string.ai_voice_permission))
    }
    fun toggleVoice() {
        // 先收键盘 —— 语音会话和 IME 并存体验混乱，且让麦克风成为唯一焦点。
        keyboard?.hide()
        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            voice.toggle(text)
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun trySend() {
        if (text.isNotBlank() && enabled) {
            onSend(text)
            keyboard?.hide()
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // v1.6.3: 对话浮层开关 — 关掉即完整视频画面（想看剪辑效果时用）。
        IconButton(onClick = onToggleOverlay) {
            Icon(
                if (overlayVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                stringResource(
                    if (overlayVisible) R.string.ai_overlay_hide else R.string.ai_overlay_show
                ),
                tint = if (overlayVisible) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.ai_input_hint)) },
            singleLine = true,
            enabled = enabled,
            // v1.6.5: 技能库入口内嵌输入框左端（leadingIcon 不占行宽 —
            // 行内已有眼睛/发送/关闭三个 48dp 按钮，再加独立按钮会把
            // 输入框挤到 ~140dp，v1.6.3 刚验证过行宽敏感）。
            // v1.6.11: 图标-only 方案两轮真机证伪（v1.6.5 上线以来用户
            // 两次找不到入口）—— 闪电加「技能」文字做成胶囊按钮，
            // primaryContainer 底色，自解释可点；宽度代价仅 ~30dp。
            leadingIcon = {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .clickable(enabled = enabled, onClick = onOpenSkills)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Bolt,
                        stringResource(R.string.ai_skills_open),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        stringResource(R.string.ai_skills_chip),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { trySend() }),
            // v1.6.14: 麦克风内嵌输入框右端（trailingIcon 不占行宽，与
            // 左端技能胶囊对称 —— v1.6.3/1.6.5 已验证行宽敏感，独立
            // 48dp 按钮会把输入框挤到 ~140dp）。无识别服务的 ROM
            // 不渲染（available=false），干净降级。
            trailingIcon = if (voice.available) {
                {
                    VoiceMicIcon(
                        listening = voice.listening,
                        enabled = enabled,
                        onToggle = ::toggleVoice,
                    )
                }
            } else null,
        )
        Spacer(Modifier.width(6.dp))
        // v1.6.2: 发送键恒可点（空文本点击为无操作）—— 禁用态的灰色
        // 图标在深色面板上几乎不可见，真机反馈“右下有个看不见的按键”。
        // 是否可发由 tint 表达：有文本亮主题色，无文本用中性色。
        IconButton(onClick = { trySend() }) {
            Icon(
                Icons.Filled.Send,
                stringResource(R.string.ai_send),
                tint = if (enabled && text.isNotBlank()) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // v1.6.2: 关闭键收进输入行（原来的“AI 剪辑 + 叉”头部行已删）。
        IconButton(onClick = onClose) {
            Icon(
                Icons.Filled.Close,
                stringResource(R.string.panel_close),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------
// v1.6.14 语音输入麦克风
// ---------------------------------------------------------------------

/**
 * 输入框尾部的麦克风图标：静止中性色；识别中主题色 + 呼吸脉动，
 * 再点一次即停止（stopListening，系统随后给出最终结果）。
 */
@Composable
private fun VoiceMicIcon(
    listening: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val pulse = if (listening) {
        val infinite = rememberInfiniteTransition(label = "voice")
        infinite.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
            label = "voice",
        ).value
    } else 1f
    Icon(
        Icons.Filled.Mic,
        stringResource(
            if (listening) R.string.ai_voice_listening else R.string.ai_voice_input
        ),
        tint = when {
            listening -> MaterialTheme.colorScheme.primary
            enabled -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        },
        modifier = Modifier
            .padding(end = 4.dp)
            .graphicsLayer { alpha = if (enabled) pulse else 0.6f }
            .clickable(enabled = enabled, onClick = onToggle),
    )
}

// ---------------------------------------------------------------------
// v1.6.5 技能库面板
// ---------------------------------------------------------------------

/**
 * 技能库弹窗：分类列出全部技能（内置 + 自定义，名称 + 一句话），
 * 点选即把技能名填入输入框（用户可继续补细节再发送）。
 * 中文名为主，英语环境自动切英文字段。
 *
 * v1.6.8：顶部新增“导入自定义技能”入口（文件/粘贴 → 校验 → 落盘），
 * 自定义技能出现“自定义”分组、带删除按钮，导入后随 system prompt
 * 注入 —— 下一句话 AI 即可按名调用。
 */
@Composable
private fun AiSkillsSheet(
    custom: List<EditSkill>,
    onImport: (String) -> CustomSkillCodec.Result,
    onReadFile: suspend (Uri) -> String?,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val zh = LocalConfiguration.current.locale.language.startsWith("zh")
    var importOpen by remember { mutableStateOf(false) }
    val customIds = custom.map { it.id }.toSet()
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ai_skills_close)) }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Bolt, null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(18.dp).height(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.ai_skills_title))
            }
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // ---- v1.6.8 导入入口（置顶 —— 用户要能“看到上传的地方”）----
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { importOpen = true }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Add, null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.ai_skills_import),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                EditSkills.byCategory(custom).forEach { (cat, skills) ->
                    Text(
                        if (zh) cat.zh else cat.en,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                    )
                    skills.forEach { s ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                Modifier
                                    .weight(1f)
                                    .clickable { onPick(if (zh) s.nameZh else s.nameEn) }
                                    .padding(vertical = 6.dp),
                            ) {
                                Text(
                                    if (zh) s.nameZh else s.nameEn,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    if (zh) s.oneLinerZh else s.oneLinerEn,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (s.id in customIds) {
                                IconButton(
                                    onClick = { onDelete(s.id) },
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        stringResource(R.string.ai_skills_delete),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    stringResource(R.string.ai_skills_footer),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
    )

    if (importOpen) {
        AiSkillImportDialog(
            onImport = onImport,
            onReadFile = onReadFile,
            onDismiss = { importOpen = false },
        )
    }
}

// ---------------------------------------------------------------------
// v1.6.8 技能导入对话框
// ---------------------------------------------------------------------

/**
 * 导入自定义技能：粘贴 JSON 或从文件选（填充进文本框检查后再导入）。
 * “填入示例”一键塞入可直接导入的样例 —— 用户照着改就能写新技能。
 * 导入成功留在本对话框（可连续导入多个文件），技能库列表实时刷新。
 */
@Composable
private fun AiSkillImportDialog(
    onImport: (String) -> CustomSkillCodec.Result,
    onReadFile: suspend (Uri) -> String?,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val content = onReadFile(uri)
                if (content == null) {
                    message = ctx.getString(R.string.ai_skills_file_error)
                    isError = true
                } else {
                    // 填入文本框让用户过目 —— 导入仍由“导入”键触发，
                    // 选错文件（非 JSON）在解析层拦下。
                    text = content
                    message = null
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ai_skills_import_title)) },
        text = {
            // v1.6.10: 与设置弹窗同理加固 —— 文本框拉满 + 键盘弹出时小屏同样会裁剪。
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.ai_skills_import_hint)) },
                    minLines = 5,
                    maxLines = 10,
                )
                message?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        text = CustomSkillCodec.SAMPLE_JSON
                        message = null
                    }) { Text(stringResource(R.string.ai_skills_import_sample)) }
                    TextButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                        Text(stringResource(R.string.ai_skills_import_file))
                    }
                }
                Text(
                    stringResource(R.string.ai_skills_import_format),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = {
                    when (val r = onImport(text)) {
                        is CustomSkillCodec.Result.Success -> {
                            val warn = r.warnings.takeIf { it.isNotEmpty() }
                                ?.joinToString("；", prefix = "（", postfix = "）") ?: ""
                            message = ctx.getString(
                                R.string.ai_skills_import_ok_fmt, r.skills.size,
                            ) + warn
                            isError = false
                            text = ""
                        }
                        is CustomSkillCodec.Result.Error -> {
                            message = r.message
                            isError = true
                        }
                    }
                },
            ) { Text(stringResource(R.string.ai_skills_import_do)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ai_skills_close)) }
        },
    )
}
