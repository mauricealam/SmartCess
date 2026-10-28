package com.smartcess.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.smartcess.app.ui.theme.SmartCessTheme

private val OpsShape = RoundedCornerShape(20.dp)

/**
 * Night Ops command bar: the secondary decision (Talk). Tonal container
 * fill with ops-accent icon and arrow. 72dp minimum target.
 */
@Composable
fun OpsCommandBar(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    contentDescription: String? = null,
) {
    val extra = SmartCessTheme.extra
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        shape = OpsShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .opsFocusRing(focused)
            .semantics { contentDescription?.let { desc -> this.contentDescription = desc } },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    tint = extra.ops,
                    modifier = Modifier.size(28.dp),
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = extra.ops,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/**
 * Night Ops hero action: the single monumental decision per surface (Scan).
 * Filled ops accent, 88dp minimum target.
 */
@Composable
fun OpsHeroAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    contentDescription: String? = null,
) {
    val extra = SmartCessTheme.extra
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        shape = OpsShape,
        color = extra.ops,
        contentColor = extra.onOps,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 88.dp)
            .opsFocusRing(focused)
            .semantics { contentDescription?.let { desc -> this.contentDescription = desc } },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.displayLarge,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

/**
 * Night Ops standard button: filled ops accent at the 48dp spec size.
 * Key forward actions (onboarding Next / Get started) use this so the one
 * primary accent holds across onboarding and the dashboard heroes.
 */
@Composable
fun OpsButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
    leadingContent: @Composable (() -> Unit)? = null,
) {
    val extra = SmartCessTheme.extra
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        shape = OpsShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = extra.ops,
            contentColor = extra.onOps,
        ),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
        modifier = modifier
            .heightIn(min = 48.dp)
            .opsFocusRing(focused)
            .semantics { contentDescription?.let { desc -> this.contentDescription = desc } },
    ) {
        leadingContent?.let {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                it()
                Text(text, style = MaterialTheme.typography.titleMedium)
            }
        } ?: Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * Ops queue row: fixed anatomy (mono index + title + meta + mono status word).
 * The status is always words, never color alone. TalkBack hears one merged
 * announcement — the full sentence, plus the status word only when it adds
 * information the sentence lacks — so title/meta never double-speak.
 */
@Composable
fun OpsQueueRow(
    index: String,
    title: String,
    meta: String,
    status: String,
    statusColor: Color,
    modifier: Modifier = Modifier,
) {
    val announcement = remember(meta, status) {
        if (status.isNotBlank() && !meta.contains(status, ignoreCase = true)) {
            "$meta. $status"
        } else {
            meta
        }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .semantics(mergeDescendants = true) { contentDescription = announcement }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = index,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = meta,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = status,
            maxLines = 2,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            ),
            color = statusColor,
        )
    }
}

/**
 * Ops notice strip for journey overlays (framing instruction over the camera
 * preview): dark console strip with an ops-accent leading rule. Identical in
 * both modes since it floats over live video. Callers add live-region
 * semantics.
 */
@Composable
fun OpsNotice(
    text: String,
    modifier: Modifier = Modifier,
) {
    val extra = SmartCessTheme.extra
    Surface(
        shape = OpsShape,
        color = Color(0xFF0B0F0E),
        contentColor = Color.White,
        border = BorderStroke(1.dp, extra.ops),
        modifier = modifier.fillMaxWidth().heightIn(min = 64.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Canvas(
                modifier = Modifier.size(width = 4.dp, height = 40.dp),
            ) {
                drawRect(color = extra.ops)
            }
            Text(
                text = text,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            )
        }
    }
}

@Composable
private fun Modifier.opsFocusRing(focused: Boolean): Modifier {
    if (!focused) return this
    return border(3.dp, MaterialTheme.colorScheme.primary, OpsShape)
}
