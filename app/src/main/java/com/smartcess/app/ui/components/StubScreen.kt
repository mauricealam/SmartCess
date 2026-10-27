package com.smartcess.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.smartcess.app.R

/**
 * Placeholder scaffold for module screens (later agents replace with real UI).
 * TalkBack-friendly: real heading semantics and a 48dp-minimum Back button.
 */
@Composable
fun StubScreen(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    body: String = "Coming soon in this build.",
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
        )
        Button(onClick = onBack) {
            Text(stringResource(R.string.action_back))
        }
    }
}
