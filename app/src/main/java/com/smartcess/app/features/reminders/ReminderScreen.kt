package com.smartcess.app.features.reminders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.smartcess.app.R
import com.smartcess.app.ui.components.BannerKind
import com.smartcess.app.ui.components.SmartCessBanner
import com.smartcess.app.ui.components.SmartCessCard
import com.smartcess.app.ui.components.SmartCessConfirmDialog
import com.smartcess.app.ui.components.SmartCessListItem
import com.smartcess.app.ui.components.SmartCessPrimaryButton
import com.smartcess.app.ui.components.SmartCessSecondaryButton
import com.smartcess.app.ui.components.SmartCessTopBar
import com.smartcess.app.ui.format.SmartCessFormat

/** Reminder confirm/detail: OPTIONS → CONFIRM → SCHEDULED → FIRED → COMPLETED. */
@Composable
fun ReminderScreen(
    reminderId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ReminderViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var showConfirm by remember { mutableStateOf(false) }
    var showCancel by remember { mutableStateOf(false) }
    var showLeadConfirm by remember { mutableStateOf(false) }
    var selectedLead by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(reminderId) { viewModel.load(reminderId) }
    LaunchedEffect(state.currentLeadDays) {
        state.currentLeadDays?.let { selectedLead = it }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SmartCessTopBar(title = stringResource(R.string.reminder_title), onBack = onBack)
        if (state.loading) {
            Text(
                text = stringResource(R.string.status_loading),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            return@Column
        }
        val reminder = state.reminder
        if (state.notFound || reminder == null) {
            SmartCessBanner(
                message = stringResource(R.string.error_not_found),
                kind = BannerKind.ERROR,
            )
            return@Column
        }
        state.messageRes?.let {
            SmartCessBanner(message = stringResource(it), kind = BannerKind.INFO)
        }
        SmartCessCard(heading = reminder.title) {
            Text(text = reminder.title, style = MaterialTheme.typography.titleMedium)
            SmartCessListItem(
                title = stringResource(R.string.reminder_when),
                subtitle = SmartCessFormat.instantDateTime(reminder.triggerAtMillis),
            )
            reminder.detail?.let {
                SmartCessListItem(
                    title = stringResource(R.string.reminder_detail),
                    subtitle = it,
                )
            }
            Text(
                text = stringResource(R.string.reminder_status, reminder.status),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (state.leadOptions.isNotEmpty()) {
            Text(
                text = stringResource(R.string.reminder_lead_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            SmartCessCard {
                state.leadOptions.forEach { days ->
                    SmartCessListItem(
                        title = pluralStringResource(R.plurals.reminder_lead_days, days, days),
                        onClick = { selectedLead = days },
                        trailing = {
                            if (selectedLead == days) {
                                Text(
                                    text = stringResource(R.string.option_selected),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        },
                    )
                }
            }
            SmartCessSecondaryButton(
                text = stringResource(R.string.action_confirm),
                onClick = { if (selectedLead != null) showLeadConfirm = true },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        SmartCessPrimaryButton(
            text = stringResource(R.string.reminder_confirm),
            onClick = { showConfirm = true },
            modifier = Modifier.fillMaxWidth(),
        )
        SmartCessSecondaryButton(
            text = stringResource(R.string.reminder_mark_complete),
            onClick = { viewModel.markComplete(onBack) },
            modifier = Modifier.fillMaxWidth(),
        )
        SmartCessSecondaryButton(
            text = stringResource(R.string.action_cancel),
            onClick = { showCancel = true },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    val reminder = state.reminder
    if (showConfirm && reminder != null) {
        SmartCessConfirmDialog(
            title = stringResource(R.string.reminder_confirm),
            message = stringResource(
                R.string.reminder_confirm_message,
                reminder.title,
                SmartCessFormat.instantDateTime(reminder.triggerAtMillis),
            ),
            onConfirm = { showConfirm = false; viewModel.confirmSchedule() },
            onDismiss = { showConfirm = false },
        )
    }
    if (showCancel && reminder != null) {
        SmartCessConfirmDialog(
            title = stringResource(R.string.reminder_cancel_title),
            message = stringResource(R.string.reminder_cancel_message, reminder.title),
            confirmText = stringResource(R.string.action_delete),
            onConfirm = { showCancel = false; viewModel.confirmCancel(onBack) },
            onDismiss = { showCancel = false },
        )
    }
    val leadDays = selectedLead
    if (showLeadConfirm && reminder != null && leadDays != null) {
        SmartCessConfirmDialog(
            title = stringResource(R.string.reminder_lead_confirm_title),
            message = stringResource(
                R.string.reminder_lead_confirm_message,
                pluralStringResource(R.plurals.reminder_lead_days, leadDays, leadDays),
            ),
            onConfirm = { showLeadConfirm = false; viewModel.changeLeadTime(leadDays) },
            onDismiss = { showLeadConfirm = false },
        )
    }
}
