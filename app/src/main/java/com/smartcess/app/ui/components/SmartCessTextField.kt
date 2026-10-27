package com.smartcess.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/** Spec outlined text field with a visible label (never placeholder-only). */
@Composable
fun SmartCessTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    error: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    /** Mask input (API keys): hidden on screen and not spoken aloud by TalkBack. */
    isPassword: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = hint?.let { { Text(it) } },
        isError = error != null,
        supportingText = error?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
        singleLine = singleLine,
        minLines = minLines,
        keyboardOptions = if (isPassword) {
            keyboardOptions.copy(keyboardType = KeyboardType.Password)
        } else {
            keyboardOptions
        },
        visualTransformation = if (isPassword) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
        textStyle = MaterialTheme.typography.bodyLarge,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
    )
}
