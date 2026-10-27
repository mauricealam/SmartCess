package com.smartcess.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.smartcess.app.ui.theme.SmartCessTheme

enum class BannerKind { INFO, WARNING, ERROR, OFFLINE }

/**
 * Spec banner for offline mode, low confidence, and errors.
 * Always a live region so TalkBack announces it; icon + words, never color-only.
 */
@Composable
fun SmartCessBanner(
    message: String,
    modifier: Modifier = Modifier,
    kind: BannerKind = BannerKind.INFO,
    title: String? = null,
) {
    val container = when (kind) {
        BannerKind.INFO -> MaterialTheme.colorScheme.primaryContainer
        BannerKind.WARNING, BannerKind.OFFLINE -> SmartCessTheme.extra.warning.copy(alpha = 0.18f)
        BannerKind.ERROR -> MaterialTheme.colorScheme.errorContainer
    }
    val content = when (kind) {
        BannerKind.INFO -> MaterialTheme.colorScheme.onPrimaryContainer
        BannerKind.WARNING, BannerKind.OFFLINE -> MaterialTheme.colorScheme.onSurface
        BannerKind.ERROR -> MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = container,
        contentColor = content,
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = if (kind == BannerKind.INFO) Icons.Filled.Info else Icons.Filled.Warning,
                contentDescription = null,
            )
            androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                title?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                Text(message, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
