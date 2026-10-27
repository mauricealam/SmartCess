package com.smartcess.app.features.email

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.smartcess.app.R
import com.smartcess.app.data.local.entity.EmailItemEntity
import com.smartcess.app.ui.components.BannerKind
import com.smartcess.app.ui.components.SmartCessBanner
import com.smartcess.app.ui.components.SmartCessCard
import com.smartcess.app.ui.components.SmartCessConfirmDialog
import com.smartcess.app.ui.components.SmartCessPrimaryButton
import com.smartcess.app.ui.components.SmartCessSecondaryButton
import com.smartcess.app.ui.components.SmartCessTextAction
import com.smartcess.app.ui.components.SmartCessTopBar
import com.smartcess.app.ui.components.SmartCessUrgencyChip
import com.smartcess.app.ui.components.urgencyLabel
import com.smartcess.app.ui.format.SmartCessFormat

/**
 * Email Briefing: summarize, urgency, deadlines, reminders.
 * Read-only by design: no send, reply, delete, or forward actions exist.
 */
@Composable
fun EmailScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EmailViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsState()
    val state by viewModel.state.collectAsState()
    var remindTarget by remember { mutableStateOf<EmailItemEntity?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SmartCessTopBar(title = stringResource(R.string.email_title), onBack = onBack)
        SmartCessBanner(message = stringResource(R.string.email_demo_note), kind = BannerKind.INFO)
        if (state.offline) {
            SmartCessBanner(message = stringResource(R.string.banner_offline), kind = BannerKind.OFFLINE)
        }
        state.messageRes?.let {
            SmartCessBanner(message = stringResource(it), kind = BannerKind.INFO)
        }
        state.briefing?.let { briefing ->
            Text(
                text = stringResource(R.string.email_briefing_label),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            SmartCessCard {
                Text(text = briefing, style = MaterialTheme.typography.bodyLarge)
            }
            SmartCessPrimaryButton(
                text = stringResource(R.string.action_listen),
                onClick = { viewModel.speak(briefing) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (items.isEmpty()) {
            Text(text = stringResource(R.string.email_empty), style = MaterialTheme.typography.bodyLarge)
        } else {
            items.forEach { email ->
                val spoken = stringResource(R.string.email_spoken, email.subject, email.body)
                EmailCard(
                    email = email,
                    onListen = { viewModel.speak(spoken) },
                    onMarkRead = { viewModel.markRead(email.id) },
                    onRemind = { remindTarget = email },
                )
            }
        }
        SmartCessBanner(message = stringResource(R.string.email_safety_note), kind = BannerKind.INFO)
    }

    remindTarget?.let { email ->
        SmartCessConfirmDialog(
            title = stringResource(R.string.action_remind),
            message = stringResource(
                R.string.reminder_confirm_message,
                email.subject,
                email.deadline?.let { SmartCessFormat.isoDate(it) } ?: "",
            ),
            confirmText = stringResource(R.string.action_remind),
            onConfirm = { viewModel.confirmDeadlineReminder(email); remindTarget = null },
            onDismiss = { remindTarget = null },
        )
    }
}

@Composable
private fun EmailCard(
    email: EmailItemEntity,
    onListen: () -> Unit,
    onMarkRead: () -> Unit,
    onRemind: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SmartCessCard(
        modifier = modifier,
        heading = email.subject,
        contentDescription = stringResource(
            R.string.email_item_desc,
            email.subject,
            email.sender,
            urgencyLabel(email.urgency),
        ),
    ) {
        Text(text = email.subject, style = MaterialTheme.typography.titleMedium)
        Text(
            text = stringResource(R.string.email_from, email.sender),
            style = MaterialTheme.typography.bodyMedium,
        )
        email.briefingNote?.let {
            Text(text = it, style = MaterialTheme.typography.bodyLarge)
        }
        email.deadline?.let {
            Text(
                text = stringResource(R.string.email_deadline, SmartCessFormat.isoDate(it)),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (email.hasAttachment) {
            Text(
                text = stringResource(R.string.email_has_attachment),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        SmartCessUrgencyChip(urgency = email.urgency)
        if (email.isRead) {
            Text(
                text = stringResource(R.string.email_is_read),
                style = MaterialTheme.typography.labelSmall,
            )
        } else {
            SmartCessTextAction(
                text = stringResource(R.string.email_mark_read),
                onClick = onMarkRead,
            )
        }
        SmartCessSecondaryButton(
            text = stringResource(R.string.action_listen),
            onClick = onListen,
            modifier = Modifier.fillMaxWidth(),
        )
        if (email.hasDeadline && email.deadline != null) {
            SmartCessSecondaryButton(
                text = stringResource(R.string.action_remind),
                onClick = onRemind,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
