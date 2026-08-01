package com.clarifi.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.clarifi.core.model.AccountColors
import com.clarifi.ui.icons.ClariFiIcons
import com.clarifi.ui.theme.Motion
import com.clarifi.ui.theme.parseAccountColor

/**
 * The eight preset account swatches, the same list the desktop's picker offers.
 *
 * The selected swatch grows and shows a check rather than only gaining a ring -
 * on a bright swatch a ring alone is easy to miss, and colour is never the sole
 * carrier of state.
 */
@Composable
fun AccountColorPicker(
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AccountColors.PRESETS.forEach { hex ->
            SwatchButton(
                hex = hex,
                isSelected = hex.equals(selected, ignoreCase = true),
                onClick = { onSelect(hex) },
            )
        }
    }
}

@Composable
private fun SwatchButton(hex: String, isSelected: Boolean, onClick: () -> Unit) {
    val color = parseAccountColor(hex, MaterialTheme.colorScheme.primary)
    val size by animateDpAsState(if (isSelected) 34.dp else 28.dp, Motion.spring(), label = "swatchSize")
    val ring by animateColorAsState(
        if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
        Motion.spring(),
        label = "swatchRing",
    )

    Box(
        modifier = Modifier
            .size(size)
            .background(color, CircleShape)
            .border(BorderStroke(2.dp, ring), CircleShape)
            .selectable(selected = isSelected, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (isSelected) {
            Icon(
                imageVector = ClariFiIcons.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
