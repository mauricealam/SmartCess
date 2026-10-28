package com.smartcess.app.ui.components

import androidx.compose.foundation.layout.Arrangement
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
 * Spec card: 12dp radius, 8dp inner padding, 12dp inner rhythm, 1dp tonal
 * elevation, single-column full width. Separation comes from tone plus a
 * whisper of elevation — grouped items never read as one clump.
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
    val elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    if (onClick != null) {
        Card(
            onClick = onClick,
            shape = RoundedCornerShape(12.dp),
            colors = colors,
            elevation = elevation,
            modifier = modifier.fillMaxWidth().then(semantics),
        ) {
            Column(
                modifier = Modifier.padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) { content() }
        }
    } else {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = colors,
            elevation = elevation,
            modifier = Modifier.fillMaxWidth().then(semantics),
        ) {
            Column(
                modifier = Modifier.padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) { content() }
        }
    }
}
