@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.RooMCutNext.ui.editor

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
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
// Adjustments -> full color grading (v1.1): LUT filters + basic parameters
// ===========================================================================

@Composable
fun AdjustSheet(vm: EditorViewModel, state: EditorUiState, onImportLut: () -> Unit = {}) {
    val clip = state.selectedClip ?: return
    SheetTitle(stringResource(R.string.panel_adjust_title))

    // v1.1.1: the panel body scrolls in the ToolPanelHost; the sheet itself
    // must NOT own a vertical scroller (nested same-direction scroll would
    // swallow the host's scrolling).
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    ) {
        // ---- LUT filter section ----
        SectionLabel(stringResource(R.string.panel_adjust_filter_section))
        LutChipRow(
            luts = state.availableLuts,
            selectedLutId = clip.adjust.lutId,
            onSelect = { lutId ->
                vm.setClipAdjust(clip.id, clip.adjust.copy(lutId = lutId, lutStrength = 1f))
            },
            onImport = onImportLut,
            onDelete = { vm.deleteUserLut(it) },
        )
        if (clip.adjust.lutId != null) {
            LabeledSlider(
                label = stringResource(R.string.panel_filter_strength),
                value = clip.adjust.lutStrength,
                valueRange = 0f..1f,
                valueLabel = "${(clip.adjust.lutStrength * 100).toInt()}%",
                onValueChange = {
                    vm.setClipAdjust(clip.id, clip.adjust.copy(lutStrength = it), commit = false)
                },
                onValueChangeFinished = { vm.setClipAdjust(clip.id, clip.adjust) },
            )
        }

        // ---- basic grading section ----
        SectionLabel(stringResource(R.string.panel_adjust_param_section))
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
        LabeledSlider(
            label = stringResource(R.string.panel_adjust_temperature),
            value = clip.adjust.temperature,
            valueRange = -1f..1f,
            valueLabel = formatSigned(clip.adjust.temperature),
            onValueChange = { vm.setClipAdjust(clip.id, clip.adjust.copy(temperature = it), commit = false) },
            onValueChangeFinished = { vm.setClipAdjust(clip.id, clip.adjust) },
        )
        LabeledSlider(
            label = stringResource(R.string.panel_adjust_tint),
            value = clip.adjust.tint,
            valueRange = -1f..1f,
            valueLabel = formatSigned(clip.adjust.tint),
            onValueChange = { vm.setClipAdjust(clip.id, clip.adjust.copy(tint = it), commit = false) },
            onValueChangeFinished = { vm.setClipAdjust(clip.id, clip.adjust) },
        )
        LabeledSlider(
            label = stringResource(R.string.panel_adjust_r),
            value = clip.adjust.rGain,
            valueRange = -1f..1f,
            valueLabel = formatSigned(clip.adjust.rGain),
            onValueChange = { vm.setClipAdjust(clip.id, clip.adjust.copy(rGain = it), commit = false) },
            onValueChangeFinished = { vm.setClipAdjust(clip.id, clip.adjust) },
        )
        LabeledSlider(
            label = stringResource(R.string.panel_adjust_g),
            value = clip.adjust.gGain,
            valueRange = -1f..1f,
            valueLabel = formatSigned(clip.adjust.gGain),
            onValueChange = { vm.setClipAdjust(clip.id, clip.adjust.copy(gGain = it), commit = false) },
            onValueChangeFinished = { vm.setClipAdjust(clip.id, clip.adjust) },
        )
        LabeledSlider(
            label = stringResource(R.string.panel_adjust_b),
            value = clip.adjust.bGain,
            valueRange = -1f..1f,
            valueLabel = formatSigned(clip.adjust.bGain),
            onValueChange = { vm.setClipAdjust(clip.id, clip.adjust.copy(bGain = it), commit = false) },
            onValueChangeFinished = { vm.setClipAdjust(clip.id, clip.adjust) },
        )
        // v1.1.2: copy this clip's grading onto every clip (videos + images).
        // Only meaningful with more than one clip on the timeline.
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            if (state.project.clips.size > 1) {
                TextButton(
                    onClick = { vm.applyAdjustToAll(clip.adjust) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.panel_adjust_apply_all), color = AccentColor)
                }
            }
            TextButton(
                onClick = { vm.setClipAdjust(clip.id, com.RooMCutNext.core.model.ColorAdjust()) },
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.panel_adjust_reset), color = WarnColor)
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

/** Horizontal scrolling row of LUT chips: "无" + builtin + user + import. */
@Composable
private fun LutChipRow(
    luts: List<com.RooMCutNext.media.LutRegistry.LutSpec>,
    selectedLutId: String?,
    onSelect: (String?) -> Unit,
    onImport: () -> Unit,
    onDelete: (String) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LutChip(
            label = stringResource(R.string.panel_filter_none),
            swatches = null,
            selected = selectedLutId == null,
            onClick = { onSelect(null) },
        )
        luts.forEach { spec ->
            LutChip(
                label = spec.displayName(),
                swatches = remember(spec.id) { com.RooMCutNext.media.LutRegistry.swatchesFor(spec.id) },
                selected = selectedLutId == spec.id,
                onClick = { onSelect(spec.id) },
                onLongClick = if (spec.isUser) ({ onDelete(spec.id) }) else null,
            )
        }
        ImportChip(
            label = stringResource(R.string.panel_filter_import),
            onClick = onImport,
        )
    }
}

@Composable
private fun LutChip(
    label: String,
    swatches: IntArray?,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Column(
        Modifier
            .width(64.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(
                width = if (selected) 2.dp else 0.dp,
                color = if (selected) AccentColor else androidx.compose.ui.graphics.Color.Transparent,
                shape = MaterialTheme.shapes.small,
            )
            .let { m ->
                if (onLongClick != null) {
                    m.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                } else {
                    m.clickable(onClick = onClick)
                }
            }
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(22.dp)
                .clip(MaterialTheme.shapes.extraSmall),
        ) {
            if (swatches != null) {
                swatches.forEach { c ->
                    Box(Modifier.weight(1f).fillMaxHeight().background(Color(c)))
                }
            } else {
                Box(
                    Modifier.weight(1f).fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "—",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

/** The trailing "+ import" chip used by both the LUT and font rows. */
@Composable
private fun ImportChip(label: String, onClick: () -> Unit) {
    Column(
        Modifier
            .width(72.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "+",
            style = MaterialTheme.typography.titleMedium,
            color = AccentColor,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

private fun formatSigned(v: Float): String =
    if (kotlin.math.abs(v) < 0.005f) "0" else "%+.2f".format(v)

/** PiP / sticker accent (matches the timeline lane color). */
private val PipColor = androidx.compose.ui.graphics.Color(0xFF64B5F6)

/** mm:ss for the live voiceover clock (rolls over to h:mm:ss past an hour). */
private fun formatElapsedMs(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

// ===========================================================================
// Text
// ===========================================================================

private val TEXT_COLORS = listOf(
    0xFFFFFFFFL, 0xFF000000L, 0xFFFFD54FL, 0xFFFF5C5CL, 0xFF42A5F5L,
    0xFF66BB6AL, 0xFFEC407AL, 0xFFFFFFFFL,
)

@Composable
fun TextSheet(vm: EditorViewModel, state: EditorUiState, onImportFont: () -> Unit = {}) {
    val item = state.selectedText ?: return
    SheetTitle(stringResource(R.string.panel_text_title))

    // v1.1.1: scrolling owned by the ToolPanelHost (see AdjustSheet note).
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    ) {
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

        // ---- font selector (v1.1): chips render their name in the font ----
        Text(
            stringResource(R.string.panel_text_font),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        FontChipRow(
            fonts = state.availableFonts,
            selectedFontId = item.style.fontId,
            onSelect = { fontId ->
                vm.updateText(item.copy(style = item.style.copy(fontId = fontId)))
            },
            onImport = onImportFont,
            onDelete = { vm.deleteUserFont(it) },
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
}

/** Horizontal font chips: each renders its own name in the actual font. */
@Composable
private fun FontChipRow(
    fonts: List<com.RooMCutNext.media.FontRegistry.FontSpec>,
    selectedFontId: String,
    onSelect: (String) -> Unit,
    onImport: () -> Unit,
    onDelete: (String) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        fonts.forEach { spec ->
            val family = remember(spec.id) {
                com.RooMCutNext.media.FontRegistry.resolve(spec.id, false)
                    ?.let { androidx.compose.ui.text.font.FontFamily(it) }
            } ?: androidx.compose.ui.text.font.FontFamily.Default
            val selected = selectedFontId == spec.id
            Box(
                Modifier
                    .clip(MaterialTheme.shapes.small)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .border(
                        width = if (selected) 2.dp else 0.dp,
                        color = if (selected) AccentColor else androidx.compose.ui.graphics.Color.Transparent,
                        shape = MaterialTheme.shapes.small,
                    )
                    .let { m ->
                        if (spec.isUser) {
                            m.combinedClickable(
                                onClick = { onSelect(spec.id) },
                                onLongClick = { onDelete(spec.id) },
                            )
                        } else {
                            m.clickable(onClick = { onSelect(spec.id) })
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    spec.displayName(),
                    fontFamily = family,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                )
            }
        }
        ImportChip(
            label = stringResource(R.string.panel_text_font_import),
            onClick = onImport,
        )
    }
}

// ===========================================================================
// Picture-in-picture / sticker (v1.2)
// ===========================================================================

@Composable
fun PipSheet(vm: EditorViewModel, state: EditorUiState) {
    SheetTitle(stringResource(R.string.panel_pip_title))
    val pip = state.selectedPip
    if (pip == null) {
        // No layer selected: pick a project asset to insert at the playhead.
        // Transparent PNGs inserted here ARE stickers.
        Text(
            stringResource(R.string.panel_pip_pick_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )
        val candidates = state.project.assets.filter { !it.isAudio }
        if (candidates.isEmpty()) {
            Text(
                stringResource(R.string.panel_pip_no_assets),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                candidates.forEach { asset ->
                    Column(
                        Modifier
                            .width(84.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { vm.addPipAtPlayhead(asset.id) }
                            .padding(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val thumbs = remember(asset.id) {
                            vm.thumbnails.peek(asset.id)
                        }
                        val thumb = thumbs?.firstOrNull()
                        if (thumb != null) {
                            androidx.compose.foundation.Image(
                                bitmap = thumb,
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(40.dp)
                                    .clip(MaterialTheme.shapes.extraSmall),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            )
                        } else {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(40.dp)
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    if (asset.isImage) "图" else "视频",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = PipColor,
                                )
                            }
                        }
                        Text(
                            asset.displayName,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        return
    }

    // Selected layer: geometry + opacity + delete.
    LabeledSlider(
        label = stringResource(R.string.panel_pip_position_x),
        value = pip.xRatio,
        valueRange = 0.05f..0.95f,
        valueLabel = "${(pip.xRatio * 100).toInt()}%",
        onValueChange = { vm.updatePip(pip.copy(xRatio = it), commit = false) },
        onValueChangeFinished = { vm.updatePip(pip) },
    )
    LabeledSlider(
        label = stringResource(R.string.panel_pip_position_y),
        value = pip.yRatio,
        valueRange = 0.05f..0.95f,
        valueLabel = "${(pip.yRatio * 100).toInt()}%",
        onValueChange = { vm.updatePip(pip.copy(yRatio = it), commit = false) },
        onValueChangeFinished = { vm.updatePip(pip) },
    )
    LabeledSlider(
        label = stringResource(R.string.panel_pip_size),
        value = pip.scale,
        valueRange = 0.1f..1f,
        valueLabel = "${(pip.scale * 100).toInt()}%",
        onValueChange = { vm.updatePip(pip.copy(scale = it), commit = false) },
        onValueChangeFinished = { vm.updatePip(pip) },
    )
    LabeledSlider(
        label = stringResource(R.string.panel_pip_opacity),
        value = pip.opacity,
        valueRange = 0.1f..1f,
        valueLabel = "${(pip.opacity * 100).toInt()}%",
        onValueChange = { vm.updatePip(pip.copy(opacity = it), commit = false) },
        onValueChangeFinished = { vm.updatePip(pip) },
    )
    Spacer(Modifier.height(4.dp))
    TextButton(
        onClick = { vm.deletePip(pip.id) },
        modifier = Modifier.padding(horizontal = 16.dp),
    ) {
        Text(stringResource(R.string.panel_pip_delete), color = DangerColor)
    }
    Spacer(Modifier.height(8.dp))
}

// ===========================================================================
// Crossfade transitions (v1.2.1)
// ===========================================================================

@Composable
fun TransitionSheet(vm: EditorViewModel, state: EditorUiState) {
    val clip = state.selectedClip ?: return
    SheetTitle(stringResource(R.string.panel_transition_title))
    val clips = state.project.clips
    val index = clips.indexOfFirst { it.id == clip.id }

    when {
        index <= 0 -> {
            TransitionHint(stringResource(R.string.panel_transition_first_clip))
            return
        }
        clip.gapBeforeUs >= com.RooMCutNext.core.model.TRANSITION_MIN_GAP_US -> {
            TransitionHint(stringResource(R.string.panel_transition_gap))
            return
        }
    }

    // Quick presets ( microseconds).
    val presets = listOf(
        0L to stringResource(R.string.panel_transition_none),
        300_000L to "0.3s",
        500_000L to "0.5s",
        1_000_000L to "1s",
        2_000_000L to "2s",
    )
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        presets.forEach { (us, label) ->
            FilterChip(
                selected = clip.transitionInUs == us,
                onClick = { vm.setClipTransition(clip.id, us) },
                label = { Text(label) },
            )
        }
    }
    LabeledSlider(
        label = stringResource(R.string.panel_transition_duration),
        value = (clip.transitionInUs / 1_000_000f).coerceIn(0f, 2f),
        valueRange = 0f..2f,
        valueLabel = stringResource(R.string.panel_transition_sec_fmt, clip.transitionInUs / 1_000_000f),
        onValueChange = { vm.setClipTransition(clip.id, (it * 1_000_000f).toLong(), commit = false) },
        onValueChangeFinished = { vm.setClipTransition(clip.id, clip.transitionInUs) },
    )

    // The chain constraints (previous clip's fading head, own minimum body)
    // can shorten the EFFECTIVE fade below the stored slider value — surface
    // that instead of letting the user wonder why the overlap looks shorter.
    val effectiveUs = com.RooMCutNext.core.model.effectiveTransitions(clips).getOrNull(index) ?: 0L
    if (effectiveUs < clip.transitionInUs) {
        Text(
            stringResource(
                R.string.panel_transition_effective_fmt,
                TimeCode.formatShort(effectiveUs),
            ),
            style = MaterialTheme.typography.labelSmall,
            color = WarnColor,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun TransitionHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    )
    Spacer(Modifier.height(8.dp))
}

// ===========================================================================
// Mosaic regions (v1.2.2)
// ===========================================================================

@Composable
fun MosaicSheet(vm: EditorViewModel, state: EditorUiState) {
    SheetTitle(stringResource(R.string.panel_mosaic_title))
    val clip = state.selectedClip ?: run {
        Text(
            stringResource(R.string.panel_need_clip),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
        return
    }

    // Region chips + add button (cap enforced by the VM with a toast).
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        clip.mosaics.forEachIndexed { i, region ->
            FilterChip(
                selected = state.selectedMosaicId == region.id,
                onClick = { vm.selectMosaic(region.id) },
                label = { Text(stringResource(R.string.panel_mosaic_region_fmt, i + 1)) },
            )
        }
        if (clip.mosaics.size < com.RooMCutNext.core.ops.TimelineOps.MOSAIC_MAX_REGIONS) {
            ActionChip(stringResource(R.string.panel_mosaic_add)) {
                vm.addMosaicRegion(clip.id)
            }
        }
    }

    // Switching clips pre-selects the first region so the sliders are usable
    // immediately; after an explicit delete the selection stays empty.
    androidx.compose.runtime.LaunchedEffect(clip.id) {
        if (state.selectedMosaicId == null || clip.mosaics.none { it.id == state.selectedMosaicId }) {
            vm.selectMosaic(clip.mosaics.firstOrNull()?.id)
        }
    }

    val region = state.selectedMosaic
    if (region == null) {
        Text(
            stringResource(if (clip.mosaics.isEmpty()) R.string.panel_mosaic_empty else R.string.panel_mosaic_pick),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        Spacer(Modifier.height(8.dp))
        return
    }

    // Sliders preview LIVE through the same composition pipeline (WYSIWYG):
    // drag until the mosaic covers the face/logo, watching the preview.
    LabeledSlider(
        label = stringResource(R.string.panel_mosaic_position_x),
        value = region.x,
        valueRange = 0.02f..0.98f,
        valueLabel = "${(region.x * 100).toInt()}%",
        onValueChange = { vm.updateMosaicRegion(clip.id, region.copy(x = it), commit = false) },
        onValueChangeFinished = { vm.updateMosaicRegion(clip.id, region) },
    )
    LabeledSlider(
        label = stringResource(R.string.panel_mosaic_position_y),
        value = region.y,
        valueRange = 0.02f..0.98f,
        valueLabel = "${(region.y * 100).toInt()}%",
        onValueChange = { vm.updateMosaicRegion(clip.id, region.copy(y = it), commit = false) },
        onValueChangeFinished = { vm.updateMosaicRegion(clip.id, region) },
    )
    LabeledSlider(
        label = stringResource(R.string.panel_mosaic_width),
        value = region.w,
        valueRange = 0.05f..1f,
        valueLabel = "${(region.w * 100).toInt()}%",
        onValueChange = { vm.updateMosaicRegion(clip.id, region.copy(w = it), commit = false) },
        onValueChangeFinished = { vm.updateMosaicRegion(clip.id, region) },
    )
    LabeledSlider(
        label = stringResource(R.string.panel_mosaic_height),
        value = region.h,
        valueRange = 0.05f..1f,
        valueLabel = "${(region.h * 100).toInt()}%",
        onValueChange = { vm.updateMosaicRegion(clip.id, region.copy(h = it), commit = false) },
        onValueChangeFinished = { vm.updateMosaicRegion(clip.id, region) },
    )
    LabeledSlider(
        label = stringResource(R.string.panel_mosaic_block),
        value = region.blockRatio,
        valueRange = 0.01f..0.25f,
        valueLabel = "${(region.blockRatio * 1000).toInt() / 10f}%",
        onValueChange = { vm.updateMosaicRegion(clip.id, region.copy(blockRatio = it), commit = false) },
        onValueChangeFinished = { vm.updateMosaicRegion(clip.id, region) },
    )
    TextButton(
        onClick = { vm.removeMosaicRegion(clip.id, region.id) },
        modifier = Modifier.padding(horizontal = 16.dp),
    ) {
        Text(stringResource(R.string.panel_mosaic_delete), color = DangerColor)
    }
    Spacer(Modifier.height(8.dp))
}

// ===========================================================================
// Audio
// ===========================================================================

@Composable
fun AudioSheet(vm: EditorViewModel, state: EditorUiState, onAddMusic: () -> Unit = {}, onRecord: () -> Unit = {}) {
    // v1.1.4: voiceover section is global (not tied to any selection) and
    // always at the top of the audio panel.
    val vo = state.voiceover
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (vo != null) {
            // Live take: red pulse dot + elapsed clock + stop button.
            Box(
                Modifier
                    .size(10.dp)
                    .background(DangerColor, CircleShape)
            )
            Text(
                formatElapsedMs(vo.elapsedMs),
                style = MaterialTheme.typography.titleSmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = DangerColor,
                modifier = Modifier.padding(start = 8.dp),
            )
            Spacer(Modifier.weight(1f))
            Button(onClick = { vm.stopVoiceover() }) {
                Text(stringResource(R.string.panel_audio_record_stop))
            }
        } else {
            Text(
                stringResource(R.string.panel_audio_record),
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onRecord) {
                Text(stringResource(R.string.panel_audio_record))
            }
        }
    }
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
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
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
