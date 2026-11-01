package com.smartcess.app.ui.components

import androidx.compose.foundation.BorderStroke
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

enum class BannerKind { INFO, WARNING, ERROR, OFFLINE }

/**
 * Spec banner for offline mode, low confidence, and errors.
 * White ground with a 1dp outline edge and a kind-tinted icon — never a
 * color wash alone, and icon + words, never color-only. A live region by
 * default so TalkBack announces it; pass [announce] = false when the app's
 * own TTS already speaks the text (Talk screen), so the two voices never
 * overlap.
 */
@Composable
fun SmartCessBanner(
    message: String,
    modifier: Modifier = Modifier,
    kind: BannerKind = BannerKind.INFO,
    title: String? = null,
    announce: Boolean = true,
) {
    val iconTint = when (kind) {
        BannerKind.INFO -> MaterialTheme.colorScheme.primary
        BannerKind.WARNING, BannerKind.OFFLINE -> MaterialTheme.colorScheme.tertiary
        BannerKind.ERROR -> MaterialTheme.colorScheme.error
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth().semantics {
            if (announce) liveRegion = LiveRegionMode.Polite
        },
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = if (kind == BannerKind.INFO) Icons.Filled.Info else Icons.Filled.Warning,
                contentDescription = null,
                tint = iconTint,
            )
            androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                title?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                Text(message, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
