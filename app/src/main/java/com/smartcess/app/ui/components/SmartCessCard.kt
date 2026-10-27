package com.smartcess.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Spec card: 12dp radius, 8dp inner padding, single-column full width.
 * Tonal separation (container on background) instead of wireframe borders.
 */
@Composable
fun SmartCessCard(
    modifier: Modifier = Modifier,
    heading: String? = null,
    contentDescription: String? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val semantics = Modifier.semantics(mergeDescendants = false) {
        if (heading != null) heading()
        contentDescription?.let { desc -> this.contentDescription = desc }
    }
    val colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
    )
    if (onClick != null) {
        Card(
            onClick = onClick,
            shape = RoundedCornerShape(12.dp),
            colors = colors,
            modifier = modifier.fillMaxWidth().then(semantics),
        ) {
            Column(modifier = Modifier.padding(8.dp)) { content() }
        }
    } else {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = colors,
            modifier = modifier.fillMaxWidth().then(semantics),
        ) {
            Column(modifier = Modifier.padding(8.dp)) { content() }
        }
    }
}
