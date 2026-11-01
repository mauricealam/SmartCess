package com.smartcess.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.smartcess.app.ui.theme.SmartCessTheme

private val SmartCessButtonShape = RoundedCornerShape(20.dp)

/**
 * Spec hero button: ops-accent fill, 48dp minimum height, clay-soft 20dp
 * radius, visible #FFD54F focus ring for keyboard/switch users. One hero
 * action per surface; everything else is secondary.
 */
@Composable
fun SmartCessPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val extra = SmartCessTheme.extra
    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        shape = SmartCessButtonShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = extra.ops,
            contentColor = extra.onOps,
        ),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
        modifier = modifier
            .heightIn(min = 48.dp)
            .focusRing(focused)
            .semantics { contentDescription?.let { desc -> this.contentDescription = desc } },
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * Secondary button: brand-blue outline + brand-blue text on the white
 * field, same 48dp target, 20dp radius and focus ring. Never a tonal gray
 * fill as the only signal; disabled state keeps the M3 dimmed treatment.
 */
@Composable
fun SmartCessSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        shape = SmartCessButtonShape,
        border = BorderStroke(
            1.dp,
            if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.primary,
        ),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
        modifier = modifier
            .heightIn(min = 48.dp)
            .focusRing(focused)
            .semantics { contentDescription?.let { desc -> this.contentDescription = desc } },
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

/** Quiet tertiary action. Still a 48dp target for TalkBack-first touch. */
@Composable
fun SmartCessTextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    TextButton(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        shape = SmartCessButtonShape,
        colors = ButtonDefaults.textButtonColors(
            contentColor = MaterialTheme.colorScheme.primary,
        ),
        modifier = modifier
            .heightIn(min = 48.dp)
            .focusRing(focused)
            .semantics { contentDescription?.let { desc -> this.contentDescription = desc } },
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun Modifier.focusRing(focused: Boolean): Modifier {
    if (!focused) return this
    return border(3.dp, SmartCessTheme.extra.focus, SmartCessButtonShape)
}
