package com.smartcess.app.ui.components

import androidx.compose.foundation.BorderStroke
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
 * Spec card: white fill on the white field, clay-soft 20dp radius, 1dp
 * outline edge, 3dp lift, 12dp inner padding, 12dp inner rhythm,
 * single-column full width. Separation comes from the outline edge —
 * never a tonal gray fill alone.
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
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    )
    val elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    if (onClick != null) {
        Card(
            onClick = onClick,
            shape = RoundedCornerShape(20.dp),
            colors = colors,
            elevation = elevation,
            border = border,
            modifier = modifier.fillMaxWidth().then(semantics),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) { content() }
        }
    } else {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = colors,
            elevation = elevation,
            border = border,
            modifier = Modifier.fillMaxWidth().then(semantics),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) { content() }
        }
    }
}
