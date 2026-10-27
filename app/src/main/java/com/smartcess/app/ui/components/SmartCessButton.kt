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

private val SmartCessButtonShape = RoundedCornerShape(16.dp)

/**
 * Spec primary button: filled, 48dp minimum height, 16dp radius,
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
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
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
 * Tonal secondary button: soft container fill, same 48dp target and focus
 * ring. No wireframe outline; separation comes from tonal elevation.
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
    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        shape = SmartCessButtonShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
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
