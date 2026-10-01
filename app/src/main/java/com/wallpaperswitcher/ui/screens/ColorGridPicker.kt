package com.wallpaperswitcher.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.wallpaperswitcher.engine.ColorPickerGrid

/**
 * Material-style colour picker: a hue x tone grid plus an optional translucency
 * slider and a preview bar (the layout the user asked for, see 4.9.50).
 *
 * The grid itself is described by [ColorPickerGrid] (pure maths, unit tested); this
 * file only draws it. Tapping a cell moves the local selection - nothing is applied
 * until the surrounding dialog's 保存 button is pressed, so a user who changes their
 * mind can back out with 取消.
 */
@Composable
fun ColorGridPicker(
    selectedHex: String,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    alphaPercent: Int? = null,
    onAlphaChange: ((Int) -> Unit)? = null,
    /**
     * Lowest value the translucency slider accepts. The floating button clamps its
     * stored opacity to FLOATING_BUTTON_ALPHA_MIN, so the slider has to use the
     * same floor - it used to run 0..100 and could show 0-4% while 5% was stored.
     */
    alphaMinPercent: Int = 0
) {
    val alphaFloor = alphaMinPercent.coerceIn(0, 100)
    val selectedColor = remember(selectedHex) { ColorPickerGrid.parseHex(selectedHex) }
    val selectedCell = remember(selectedHex) { ColorPickerGrid.nearestCellOf(selectedHex) }
    val previewColor = selectedColor?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
    val previewAlpha = (alphaPercent ?: 100) / 100f

    Column(modifier = modifier.fillMaxWidth()) {
        // Preview bar: grey/white checkerboard underneath so translucency is visible.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .checkerboard()
                .background(previewColor.copy(alpha = previewAlpha))
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(8.dp)
                )
        )
        Spacer(modifier = Modifier.height(14.dp))

        // Hue x tone grid. Cells keep their aspect ratio, so the grid fills the
        // dialog width the way Material's own pickers do.
        for (row in 0 until ColorPickerGrid.ROWS) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                for (column in 0 until ColorPickerGrid.COLUMNS) {
                    val argb = remember(column, row) { ColorPickerGrid.colorAt(column, row) }
                    val color = Color(argb)
                    val hex = remember(argb) { ColorPickerGrid.toHex(argb) }
                    val isSelected = selectedCell?.let { it.first == column && it.second == row } == true
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(2.dp))
                            .background(color)
                            // Accessibility: 72 unlabelled squares were read out as
                            // "unlabelled button" by TalkBack. Name each cell by the
                            // colour it offers and expose the selected state, so a
                            // screen-reader user can navigate the palette at all.
                            .semantics {
                                role = Role.RadioButton
                                selected = isSelected
                                contentDescription = cellDescription(column, row)
                            }
                            .clickable { onPick(hex) },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            // Same affordance as the reference picker: a white ring
                            // with a soft outer border so it reads on light colours.
                            Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .border(2.dp, Color.White, CircleShape)
                                    .border(3.dp, Color.Black.copy(alpha = 0.25f), CircleShape)
                            )
                        }
                    }
                }
            }
            if (row != ColorPickerGrid.ROWS - 1) Spacer(modifier = Modifier.height(2.dp))
        }

        if (alphaPercent != null && onAlphaChange != null) {
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                "透明度 $alphaPercent%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .checkerboard()
                    .background(
                        Brush.horizontalGradient(
                            listOf(previewColor.copy(alpha = 0f), previewColor.copy(alpha = 1f))
                        )
                    )
            ) {
                Slider(
                    value = alphaPercent.toFloat(),
                    onValueChange = { onAlphaChange(it.toInt()) },
                    valueRange = alphaFloor.toFloat()..100f,
                    colors = SliderDefaults.colors(
                        // The gradient underneath is the track.
                        activeTrackColor = Color.Transparent,
                        inactiveTrackColor = Color.Transparent,
                        thumbColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * Dialog wrapper: picks a colour and only reports it when 保存 is pressed.
 *
 * [systemOption] adds the "跟随系统" choice the theme colour needs (it stores an
 * empty string meaning "let Android/Monet decide"); [withAlpha] exposes the
 * translucency slider, which the floating button uses for its rest opacity.
 */
@Composable
fun ColorGridPickerDialog(
    title: String,
    currentHex: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    systemOption: Boolean = false,
    withAlpha: Boolean = false,
    alphaPercent: Int = 100,
    alphaMinPercent: Int = 0,
    onConfirmAlpha: ((Int) -> Unit)? = null
) {
    var picked by remember(currentHex) { mutableStateOf(currentHex) }
    var pickedAlpha by remember(alphaPercent) { mutableStateOf(alphaPercent) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (systemOption) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { picked = "" }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            if (picked.isEmpty()) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.size(12.dp))
                        Text(
                            "跟随系统 Monet（用壁纸配色，Android 12+）",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }
                ColorGridPicker(
                    selectedHex = picked,
                    onPick = { picked = it },
                    alphaPercent = if (withAlpha) pickedAlpha else null,
                    alphaMinPercent = alphaMinPercent,
                    onAlphaChange = if (withAlpha) {
                        { pickedAlpha = it }
                    } else {
                        null
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(picked)
                    if (withAlpha) onConfirmAlpha?.invoke(pickedAlpha)
                    // Close here rather than relying on the call site: the settings
                    // screen only clears its own flag in onDismiss, so 保存 applied
                    // the colour and left the dialog on screen (the theme dialog
                    // closed only because its onConfirm happened to clear the flag).
                    onDismiss()
                }
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * Human-readable name for one grid cell, used as its accessibility label.
 *
 * Built from the same numbers the cell is painted from ([ColorPickerGrid]), so the
 * spoken colour and the visible colour cannot drift apart.
 */
private fun cellDescription(column: Int, row: Int): String {
    val hue = ColorPickerGrid.hueAt(column).toInt()
    val lightness = (ColorPickerGrid.toneAt(row) * 100).toInt()
    return "色相 ${hue}°，明度 ${lightness}%"
}

/** Grey/white checkerboard, the usual affordance for "this is translucent". */private fun Modifier.checkerboard(cell: androidx.compose.ui.unit.Dp = 8.dp): Modifier =
    drawBehind {
        val step = cell.toPx()
        var y = 0f
        var row = 0
        while (y < size.height) {
            var x = 0f
            var column = 0
            while (x < size.width) {
                val light = (row + column) % 2 == 0
                drawRect(
                    color = if (light) Color(0xFFEDEDED) else Color(0xFFCFCFCF),
                    topLeft = Offset(x, y),
                    size = Size(minOf(step, size.width - x), minOf(step, size.height - y))
                )
                x += step
                column++
            }
            y += step
            row++
        }
    }
