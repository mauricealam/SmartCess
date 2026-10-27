package com.smartcess.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.smartcess.app.R

/** Standard sub-screen header: back action + heading. TalkBack-first focus order. */
@Composable
fun SmartCessTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp)
            .statusBarsPadding(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .size(48.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceContainerHighest,
                    CircleShape,
                ),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.action_back),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.semantics { heading() },
        )
    }
}

/**
 * Home header: compact app-name wordmark only. The script-S mark was removed
 * from headers to return vertical space to active content; brand presence
 * stays on the launcher icon and splash.
 */
@Composable
fun SmartCessHomeHeader(
    modifier: Modifier = Modifier,
) {
    Text(
        text = stringResource(R.string.app_name),
        style = MaterialTheme.typography.headlineLarge,
        modifier = modifier.fillMaxWidth().statusBarsPadding().semantics { heading() },
    )
}
