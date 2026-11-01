package com.smartcess.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Spec list item: white 56dp-minimum row, title + optional subtitle +
 * trailing slot, closed by a 1dp divider so stacked rows never merge.
 * Text and icon are never color-only: callers pair chips with words.
 */
@Composable
fun SmartCessListItem(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    contentDescription: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val clickable = if (onClick != null) {
        Modifier.clickable(onClickLabel = title, onClick = onClick)
    } else {
        Modifier
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .then(clickable)
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .semantics { contentDescription?.let { desc -> this.contentDescription = desc } },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
            trailing?.invoke()
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}
