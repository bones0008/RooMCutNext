@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.RooMCutNext.ui.editor

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.RooMCutNext.R
import com.RooMCutNext.core.model.CropSpec
import com.RooMCutNext.core.model.snapProjectFps
import com.RooMCutNext.core.util.FileSize
import com.RooMCutNext.core.util.TimeCode
import com.RooMCutNext.export.ExportBus
import com.RooMCutNext.export.ExportOptions
import com.RooMCutNext.ui.theme.AccentColor
import com.RooMCutNext.ui.theme.DangerColor
import com.RooMCutNext.ui.theme.WarnColor

// ===========================================================================
// Speed
// ===========================================================================

@Composable
fun SpeedSheet(vm: EditorViewModel, state: EditorUiState) {
    val clip = state.selectedClip ?: return
    SheetTitle(stringResource(R.string.panel_speed_title))
    val presets = listOf(0.25f, 0.5f, 0.75f, 1f, 1.5f, 2f, 3f)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        presets.take(4).forEach { speed ->
            FilterChip(
                selected = clip.speed == speed,
                onClick = { vm.setClipSpeed(clip.id, speed) },
                label = { Text(speedLabel(speed)) },
            )
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        presets.drop(4).forEach { speed ->
            FilterChip(
                selected = clip.speed == speed,
                onClick = { vm.setClipSpeed(clip.id, speed) },
                label = { Text(speedLabel(speed)) },
            )
        }
    }
    LabeledSlider(
        label = stringResource(R.string.panel_speed_custom),
        value = clip.speed,
        valueRange = 0.25f..4f,
        valueLabel = speedLabel(clip.speed),
        onValueChange = { vm.setClipSpeed(clip.id, it, commit = false) },
        onValueChangeFinished = { vm.setClipSpeed(clip.id, clip.speed) },
    )
}

private fun speedLabel(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}x" else "${"%.2f".format(speed)}x"

// ===========================================================================
// Rotate & mirror
// ===========================================================================

@Composable
fun RotateSheet(vm: EditorViewModel, state: EditorUiState) {
    val clip = state.selectedClip ?: return
    SheetTitle(stringResource(R.string.panel_rotate_title))
    Row(
        Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ActionChip(stringResource(R.string.panel_rotate_ccw), Modifier.weight(1f)) {
            vm.rotateClipBy(clip.id, -90)
        }
        ActionChip(stringResource(R.string.panel_rotate_180), Modifier.weight(1f)) {
            vm.rotateClipBy(clip.id, 180)
        }
        ActionChip(stringResource(R.string.panel_rotate_cw), Modifier.weight(1f)) {
            vm.rotateClipBy(clip.id, 90)
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.panel_flip_h), Modifier.weight(1f))
        Switch(checked = clip.flipH, onCheckedChange = { vm.toggleFlip(clip.id, horizontal = true) })
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.panel_flip_v), Modifier.weight(1f))
        Switch(checked = clip.flipV, onCheckedChange = { vm.toggleFlip(clip.id, horizontal = false) })
    }
    Spacer(Modifier.height(8.dp))
}

// ===========================================================================
// Crop
// ===========================================================================

@Composable
fun CropSheet(vm: EditorViewModel, state: EditorUiState) {
    val clip = state.selectedClip ?: return
    val asset = state.project.assetById(clip.assetId)
    val sourceAspect = if (asset != null && asset.height > 0) asset.width.toFloat() / asset.height else 16f / 9f
    // Crop is defined against the ROTATED frame.
    val rotatedAspect = when (clip.rotationDeg) {
        90, 270 -> 1f / sourceAspect
        else -> sourceAspect
    }
    SheetTitle(stringResource(R.string.panel_crop_title))

    val aspects = listOf(
        null to stringResource(R.string.panel_crop_none),
        16f / 9f to "16:9",
        9f / 16f to "9:16",
        1f to "1:1",
        4f / 3f to "4:3",
        3f / 4f to "3:4",
    )
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        aspects.forEach { (aspect, label) ->
            FilterChip(
                selected = if (aspect == null) clip.crop == null else clip.crop == CropSpec.forAspect(aspect, rotatedAspect),
                onClick = {
                    vm.setClipCrop(clip.id, aspect?.let { CropSpec.forAspect(it, rotatedAspect) })
                },
                label = { Text(label) },
            )
        }
    }

    clip.crop?.let { crop ->
        LabeledSlider(
            label = stringResource(R.string.panel_crop_horizontal),
            value = (crop.left + (1f - crop.right)) / 2f,
            valueRange = 0f..0.45f,
            valueLabel = "${(((crop.left + (1f - crop.right)) / 2f) * 100).toInt()}%",
            onValueChange = { inset ->
                val m = inset.coerceIn(0f, 0.45f)
                vm.setClipCrop(clip.id, CropSpec(m, 1f - m, crop.top, crop.bottom), commit = false)
            },
            onValueChangeFinished = {
                vm.setClipCrop(clip.id, clip.crop)
            },
        )
        LabeledSlider(
            label = stringResource(R.string.panel_crop_vertical),
            value = (crop.bottom + (1f - crop.top)) / 2f,
            valueRange = 0f..0.45f,
            valueLabel = "${(((crop.bottom + (1f - crop.top)) / 2f) * 100).toInt()}%",
            onValueChange = { inset ->
                val m = inset.coerceIn(0f, 0.45f)
                vm.setClipCrop(clip.id, CropSpec(crop.left, crop.right, 1f - m, m), commit = false)
            },
            onValueChangeFinished = {
                vm.setClipCrop(clip.id, clip.crop)
            },
        )
    }
    Spacer(Modifier.height(8.dp))
}

// ===========================================================================
// Adjustments (brightness / contrast / saturation)
// ===========================================================================

@Composable
fun AdjustSheet(vm: EditorViewModel, state: EditorUiState) {
    val clip = state.selectedClip ?: return
    SheetTitle(stringResource(R.string.panel_adjust_title))
    LabeledSlider(
        label = stringResource(R.string.panel_adjust_brightness),
        value = clip.adjust.brightness,
        valueRange = -1f..1f,
        valueLabel = formatSigned(clip.adjust.brightness),
        onValueChange = { vm.setClipAdjust(clip.id, clip.adjust.copy(brightness = it), commit = false) },
        onValueChangeFinished = { vm.setClipAdjust(clip.id, clip.adjust) },
    )
    LabeledSlider(
        label = stringResource(R.string.panel_adjust_contrast),
        value = clip.adjust.contrast,
        valueRange = -1f..1f,
        valueLabel = formatSigned(clip.adjust.contrast),
        onValueChange = { vm.setClipAdjust(clip.id, clip.adjust.copy(contrast = it), commit = false) },
        onValueChangeFinished = { vm.setClipAdjust(clip.id, clip.adjust) },
    )
    LabeledSlider(
        label = stringResource(R.string.panel_adjust_saturation),
        value = clip.adjust.saturation,
        valueRange = -1f..1f,
        valueLabel = formatSigned(clip.adjust.saturation),
        onValueChange = { vm.setClipAdjust(clip.id, clip.adjust.copy(saturation = it), commit = false) },
        onValueChangeFinished = { vm.setClipAdjust(clip.id, clip.adjust) },
    )
    TextButton(
        onClick = { vm.setClipAdjust(clip.id, com.RooMCutNext.core.model.ColorAdjust()) },
        modifier = Modifier.padding(horizontal = 16.dp),
    ) {
        Text(stringResource(R.string.panel_adjust_reset), color = WarnColor)
    }
}

private fun formatSigned(v: Float): String =
    if (kotlin.math.abs(v) < 0.005f) "0" else "%+.2f".format(v)

// ===========================================================================
// Text
// ===========================================================================

private val TEXT_COLORS = listOf(
    0xFFFFFFFFL, 0xFF000000L, 0xFFFFD54FL, 0xFFFF5C5CL, 0xFF42A5F5L,
    0xFF66BB6AL, 0xFFEC407AL, 0xFFFFFFFFL,
)

@Composable
fun TextSheet(vm: EditorViewModel, state: EditorUiState) {
    val item = state.selectedText ?: return
    SheetTitle(stringResource(R.string.panel_text_title))

    var text by remember(item.id) { mutableStateOf(item.text) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            vm.updateText(item.copy(text = it), commit = false)
        },
        label = { Text(stringResource(R.string.panel_text_content)) },
        placeholder = { Text(stringResource(R.string.panel_text_empty_hint)) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        minLines = 1,
        maxLines = 3,
    )

    LabeledSlider(
        label = stringResource(R.string.panel_text_size),
        value = item.style.fontSizeRatio,
        valueRange = 0.03f..0.25f,
        valueLabel = "${(item.style.fontSizeRatio * 100).toInt()}",
        onValueChange = { vm.updateText(item.copy(style = item.style.copy(fontSizeRatio = it)), commit = false) },
        onValueChangeFinished = { vm.updateText(item) },
    )

    Text(
        stringResource(R.string.panel_text_color),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TEXT_COLORS.take(7).forEach { c ->
            val selected = item.style.color == c
            Box(
                Modifier
                    .size(30.dp)
                    .background(Color(c.toInt()), CircleShape)
                    .border(
                        width = if (selected) 3.dp else 1.dp,
                        color = if (selected) AccentColor else MaterialTheme.colorScheme.outline,
                        shape = CircleShape,
                    )
                    .clickable { vm.updateText(item.copy(style = item.style.withColor(c))) },
            )
        }
    }

    LabeledSlider(
        label = stringResource(R.string.panel_text_stroke),
        value = item.style.strokeWidthRatio,
        valueRange = 0f..0.25f,
        valueLabel = "${(item.style.strokeWidthRatio * 100).toInt()}",
        onValueChange = { vm.updateText(item.copy(style = item.style.copy(strokeWidthRatio = it)), commit = false) },
        onValueChangeFinished = { vm.updateText(item) },
    )
    LabeledSlider(
        label = stringResource(R.string.panel_text_shadow),
        value = item.style.shadowRadiusRatio,
        valueRange = 0f..0.3f,
        valueLabel = "${(item.style.shadowRadiusRatio * 100).toInt()}",
        onValueChange = { vm.updateText(item.copy(style = item.style.copy(shadowRadiusRatio = it)), commit = false) },
        onValueChangeFinished = { vm.updateText(item) },
    )
    LabeledSlider(
        label = stringResource(R.string.panel_text_position_x),
        value = item.xRatio,
        valueRange = 0.05f..0.95f,
        valueLabel = "${(item.xRatio * 100).toInt()}%",
        onValueChange = { vm.updateText(item.copy(xRatio = it), commit = false) },
        onValueChangeFinished = { vm.updateText(item) },
    )
    LabeledSlider(
        label = stringResource(R.string.panel_text_position_y),
        value = item.yRatio,
        valueRange = 0.05f..0.95f,
        valueLabel = "${(item.yRatio * 100).toInt()}%",
        onValueChange = { vm.updateText(item.copy(yRatio = it), commit = false) },
        onValueChangeFinished = { vm.updateText(item) },
    )
    LabeledSlider(
        label = stringResource(R.string.panel_text_rotation),
        value = item.rotationDeg,
        valueRange = -180f..180f,
        valueLabel = "${item.rotationDeg.toInt()}°",
        onValueChange = { vm.updateText(item.copy(rotationDeg = it), commit = false) },
        onValueChangeFinished = { vm.updateText(item) },
    )
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.panel_text_bold), Modifier.weight(1f))
        Switch(checked = item.style.bold, onCheckedChange = {
            vm.updateText(item.copy(style = item.style.copy(bold = it)))
        })
    }
    TextButton(
        onClick = { vm.deleteText(item.id) },
        modifier = Modifier.padding(horizontal = 16.dp),
    ) {
        Text(stringResource(R.string.panel_text_delete), color = DangerColor)
    }
}

// ===========================================================================
// Audio
// ===========================================================================

@Composable
fun AudioSheet(vm: EditorViewModel, state: EditorUiState, onAddMusic: () -> Unit = {}) {
    // Independent audio clip selected -> its own controls.
    val audio = state.selectedAudio
    if (audio != null) {
        AudioClipSheet(vm, audio)
        return
    }
    // Nothing selected: guide the user instead of an empty sheet. Importing
    // audio now lives in 添加素材; this panel is the audio SETTINGS tool.
    val clip = state.selectedClip ?: run {
        SheetTitle(stringResource(R.string.panel_audio_title))
        Text(
            stringResource(R.string.panel_audio_need_selection),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
        TextButton(
            onClick = onAddMusic,
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            Text(stringResource(R.string.panel_audio_add_music), color = AccentColor)
        }
        Spacer(Modifier.height(8.dp))
        return
    }
    // Otherwise: audio settings of the selected video clip.
    val asset = state.project.assetById(clip.assetId)
    SheetTitle(stringResource(R.string.panel_audio_title))
    if (asset != null && (asset.isImage || !asset.hasAudio)) {
        Text(
            stringResource(R.string.panel_audio_no_audio),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
        return
    }
    LabeledSlider(
        label = stringResource(R.string.panel_audio_volume),
        value = clip.volume,
        valueRange = 0f..2f,
        valueLabel = "${(clip.volume * 100).toInt()}%",
        onValueChange = { vm.setClipVolume(clip.id, it, commit = false) },
        onValueChangeFinished = { vm.setClipVolume(clip.id, clip.volume) },
    )
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.panel_audio_mute), Modifier.weight(1f))
        Switch(checked = clip.muted, onCheckedChange = { vm.toggleClipMuted(clip.id) })
    }
    FadeSlider(
        label = stringResource(R.string.panel_audio_fade_in),
        ms = clip.fadeInMs,
        onPreview = { vm.setClipFades(clip.id, it, clip.fadeOutMs, commit = false) },
        onCommit = { vm.setClipFades(clip.id, it, clip.fadeOutMs) },
    )
    FadeSlider(
        label = stringResource(R.string.panel_audio_fade_out),
        ms = clip.fadeOutMs,
        onPreview = { vm.setClipFades(clip.id, clip.fadeInMs, it, commit = false) },
        onCommit = { vm.setClipFades(clip.id, clip.fadeInMs, it) },
    )
    TextButton(
        onClick = onAddMusic,
        modifier = Modifier.padding(horizontal = 16.dp),
    ) {
        Text(stringResource(R.string.panel_audio_add_music), color = AccentColor)
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun AudioClipSheet(vm: EditorViewModel, audio: com.RooMCutNext.core.model.AudioClip) {
    SheetTitle(stringResource(R.string.panel_audio_clip_title))
    Text(
        stringResource(R.string.panel_audio_clip_hint),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    LabeledSlider(
        label = stringResource(R.string.panel_audio_volume),
        value = audio.volume,
        valueRange = 0f..2f,
        valueLabel = "${(audio.volume * 100).toInt()}%",
        onValueChange = { vm.setAudioVolume(audio.id, it, commit = false) },
        onValueChangeFinished = { vm.setAudioVolume(audio.id, audio.volume) },
    )
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.panel_audio_mute), Modifier.weight(1f))
        Switch(checked = audio.muted, onCheckedChange = { vm.toggleAudioMuted(audio.id) })
    }
    FadeSlider(
        label = stringResource(R.string.panel_audio_fade_in),
        ms = audio.fadeInMs,
        onPreview = { vm.setAudioFades(audio.id, it, audio.fadeOutMs, commit = false) },
        onCommit = { vm.setAudioFades(audio.id, it, audio.fadeOutMs) },
    )
    FadeSlider(
        label = stringResource(R.string.panel_audio_fade_out),
        ms = audio.fadeOutMs,
        onPreview = { vm.setAudioFades(audio.id, audio.fadeInMs, it, commit = false) },
        onCommit = { vm.setAudioFades(audio.id, audio.fadeInMs, it) },
    )
    TextButton(
        onClick = { vm.deleteAudio(audio.id) },
        modifier = Modifier.padding(horizontal = 16.dp),
    ) {
        Text(stringResource(R.string.panel_audio_delete), color = DangerColor)
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun FadeSlider(label: String, ms: Int, onPreview: (Int) -> Unit, onCommit: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            Text(
                stringResource(R.string.panel_audio_ms_fmt, ms),
                style = MaterialTheme.typography.labelMedium,
                color = AccentColor,
            )
        }
        Slider(
            value = ms.toFloat(),
            onValueChange = { onPreview(it.toInt()) },
            onValueChangeFinished = { onCommit(ms) },
            valueRange = 0f..2000f,
            steps = 7,
        )
    }
}

// ===========================================================================
// Info
// ===========================================================================

@Composable
fun InfoSheet(vm: EditorViewModel, state: EditorUiState, onReplace: () -> Unit = {}) {
    val clip = state.selectedClip ?: return
    val asset = state.project.assetById(clip.assetId) ?: return
    SheetTitle(stringResource(R.string.panel_info_title))
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        InfoRow(stringResource(R.string.panel_info_resolution), "${asset.width}×${asset.height}")
        if (!asset.isImage) {
            InfoRow(
                stringResource(R.string.panel_info_fps),
                if (asset.fps > 0f) "${"%.2f".format(asset.fps)} FPS (${stringResource(R.string.panel_info_speed_fmt, "1x")} → ${state.project.fps}FPS)"
                else "${state.project.fps} FPS"
            )
            // Frame timecode (no milliseconds); source-side values use the
            // asset's own snapped grid, timeline values the project grid.
            val srcFps = if (asset.fps > 0f) snapProjectFps(asset.fps) else state.project.fps
            InfoRow(stringResource(R.string.panel_info_duration), TimeCode.formatTimecodeFrames(asset.durationUs, srcFps))
            InfoRow(
                stringResource(R.string.panel_info_used),
                "${TimeCode.formatTimecodeFrames(clip.sourceInUs, srcFps)} → ${TimeCode.formatTimecodeFrames(clip.sourceOutUs, srcFps)}",
            )
            InfoRow(stringResource(R.string.panel_info_speed), stringResource(R.string.panel_info_speed_fmt, speedLabel(clip.speed)))
            InfoRow(stringResource(R.string.panel_info_codec), asset.videoCodec ?: "-")
            InfoRow(
                stringResource(R.string.panel_info_audio),
                if (asset.hasAudio) (asset.audioCodec ?: "AAC") else stringResource(R.string.clip_badge_muted),
            )
        } else {
            InfoRow(stringResource(R.string.panel_info_duration), TimeCode.formatTimecodeFrames(clip.durationUs, state.project.fps))
        }
        InfoRow(stringResource(R.string.panel_info_size), FileSize.format(asset.fileBytes))
        if (clip.assetId in state.missingAssetIds) {
            Text(
                stringResource(R.string.panel_info_missing),
                color = DangerColor,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        OutlinedButton(onClick = onReplace, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_replace))
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ===========================================================================
// Go to time
// ===========================================================================

@Composable
fun GotoSheet(vm: EditorViewModel, state: EditorUiState) {
    SheetTitle(stringResource(R.string.panel_goto_title))
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(
            stringResource(
                R.string.panel_goto_frame_fmt,
                TimeCode.frameIndex(state.playheadUs, state.project.fps),
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; error = false },
            label = { Text(stringResource(R.string.panel_goto_title)) },
            placeholder = { Text(stringResource(R.string.panel_goto_hint)) },
            isError = error,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (error) {
            Text(stringResource(R.string.panel_goto_invalid), color = DangerColor)
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                val us = TimeCode.parseMs(text)
                if (us == null) error = true
                else {
                    vm.seekTo(us)
                    vm.openPanel(null)
                }
            },
            enabled = text.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.panel_goto_go))
        }
    }
}

// ===========================================================================
// Export
// ===========================================================================

@Composable
fun ExportSheet(vm: EditorViewModel, state: EditorUiState, onNeedPermission: () -> Unit) {
    val context = LocalContext.current
    var selectedHeight by remember { mutableStateOf<Int?>(null) }
    SheetTitle(stringResource(R.string.panel_export_title))
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(
            stringResource(R.string.panel_export_resolution),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ExportOptions.HEIGHT_CHOICES.forEach { h ->
                FilterChip(
                    selected = selectedHeight == h,
                    onClick = { selectedHeight = h },
                    label = { Text(h?.let { "${it}P" } ?: stringResource(R.string.export_original)) },
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row {
            Text(
                stringResource(R.string.panel_export_format),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.panel_export_format_value),
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Spacer(Modifier.height(14.dp))

        val es = state.exportState
        when {
            es == null || (es.phase != ExportBus.Phase.RUNNING && es.projectId != state.project.id) -> {
                Button(
                    onClick = {
                        if (android.os.Build.VERSION.SDK_INT >= 33 &&
                            androidx.core.content.ContextCompat.checkSelfPermission(
                                context, android.Manifest.permission.POST_NOTIFICATIONS
                            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) {
                            vm.pendingExportHeight = selectedHeight
                            onNeedPermission()
                        } else {
                            vm.startExport(selectedHeight)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.panel_export_start))
                }
            }
            es.phase == ExportBus.Phase.RUNNING -> {
                Text(stringResource(R.string.panel_export_exporting), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                if (es.progress >= 0) {
                    LinearProgressIndicator(
                        progress = { es.progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = AccentColor,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(
                            R.string.panel_export_progress_fmt,
                            (es.progress * 100).toInt(),
                            es.etaSeconds ?: 0L,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = AccentColor)
                    Text(stringResource(R.string.panel_export_indeterminate), style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { vm.cancelExport() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.panel_export_cancel), color = DangerColor)
                }
            }
            es.phase == ExportBus.Phase.COMPLETED -> {
                Text(stringResource(R.string.panel_export_done), style = MaterialTheme.typography.titleSmall, color = AccentColor)
                Text(stringResource(R.string.panel_export_saved_hint), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            es.outputUri?.let { uri ->
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW).apply {
                                            setDataAndType(uri, "video/mp4")
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                    )
                                }
                            }
                        },
                        enabled = es.outputUri != null,
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.panel_export_open)) }
                    OutlinedButton(
                        onClick = {
                            es.outputUri?.let { uri ->
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_SEND).apply {
                                            type = "video/mp4"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                    )
                                }
                            }
                        },
                        enabled = es.outputUri != null,
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.panel_export_share)) }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { vm.dismissExportResult(); vm.openPanel(null) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.panel_export_close))
                }
            }
            else -> { // FAILED / CANCELLED
                Text(
                    stringResource(R.string.panel_export_failed),
                    style = MaterialTheme.typography.titleSmall,
                    color = DangerColor,
                )
                es.error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { vm.dismissExportResult() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.panel_export_close))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

// ===========================================================================
// Shared bits
// ===========================================================================

@Composable
fun SheetTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueLabel: String,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            Text(valueLabel, style = MaterialTheme.typography.labelMedium, color = AccentColor)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
        )
    }
}

@Composable
fun ActionChip(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier,
    ) {
        Box(Modifier.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}
