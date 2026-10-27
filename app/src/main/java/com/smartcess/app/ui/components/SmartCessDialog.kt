package com.smartcess.app.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.smartcess.app.R

/**
 * Spec confirmation dialog. Every destructive or committing action
 * (save, schedule, calendar, archive, delete) asks through this first.
 */
@Composable
fun SmartCessConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    confirmText: String = stringResource(R.string.action_confirm),
    dismissText: String = stringResource(R.string.action_cancel),
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.headlineLarge) },
        text = { Text(message, style = MaterialTheme.typography.bodyLarge) },
        confirmButton = { SmartCessPrimaryButton(confirmText, onConfirm) },
        dismissButton = { SmartCessSecondaryButton(dismissText, onDismiss) },
        modifier = modifier,
    )
}
