package com.smartcess.app.ui.components

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

private val SmartCessButtonShape = RoundedCornerShape(8.dp)

/**
 * Spec primary button: filled, 48dp minimum height, 8dp radius,
 * visible #FFD54F focus ring for keyboard/switch users.
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
    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        shape = SmartCessButtonShape,
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
        modifier = modifier
            .heightIn(min = 48.dp)
            .focusRing(focused)
            .semantics { contentDescription?.let { desc -> this.contentDescription = desc } },
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

/** Spec secondary button: outlined, same 48dp target and focus ring. */
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
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
        border = ButtonDefaults.outlinedButtonBorder,
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
