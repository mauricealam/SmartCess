package com.smartcess.app.ui.components

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartcess.app.R

/** Localized label for a spec urgency (low|normal|high|urgent). */
@Composable
fun urgencyLabel(urgency: String): String = when (urgency.lowercase()) {
    "low" -> stringResource(R.string.urgency_low)
    "high" -> stringResource(R.string.urgency_high)
    "urgent" -> stringResource(R.string.urgency_urgent)
    else -> stringResource(R.string.urgency_normal)
}

/** Urgency chip with words (never color-only). Maps spec urgencies to labels. */
@Composable
fun SmartCessUrgencyChip(
    urgency: String,
    modifier: Modifier = Modifier,
) {
    val label = urgencyLabel(urgency)
    AssistChip(
        onClick = {},
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        modifier = modifier.heightIn(min = 48.dp),
    )
}

/** Generic status chip (overdue, due soon, today, offline, needs review). */
@Composable
fun SmartCessStatusChip(
    text: String,
    modifier: Modifier = Modifier,
) {
    AssistChip(
        onClick = {},
        label = { Text(text, style = MaterialTheme.typography.labelSmall) },
        modifier = modifier.heightIn(min = 48.dp),
    )
}
