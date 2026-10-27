package com.smartcess.app.features.briefing

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.smartcess.app.R
import com.smartcess.app.ui.components.BannerKind
import com.smartcess.app.ui.components.SmartCessBanner
import com.smartcess.app.ui.components.SmartCessCard
import com.smartcess.app.ui.components.SmartCessListItem
import com.smartcess.app.ui.components.SmartCessPrimaryButton
import com.smartcess.app.ui.components.SmartCessSecondaryButton
import com.smartcess.app.ui.components.SmartCessStatusChip
import com.smartcess.app.ui.components.SmartCessTopBar
import com.smartcess.app.ui.components.SmartCessUrgencyChip
import com.smartcess.app.ui.format.SmartCessFormat

/** Daily Briefing: prioritized spoken briefing across all six modules. */
@Composable
fun BriefingScreen(
    onBack: () -> Unit,
    onBill: (Long) -> Unit,
    onAppointment: (Long) -> Unit,
    onEmail: () -> Unit,
    onDocument: (Long) -> Unit,
    onRoutine: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BriefingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val spokenIntro = stringResource(R.string.briefing_spoken_intro)
    val spokenEmpty = stringResource(R.string.briefing_spoken_empty)
    val spokenOverdue = stringResource(R.string.briefing_spoken_overdue)
    val spokenDue = stringResource(R.string.briefing_spoken_due)
    val spokenEmail = stringResource(R.string.briefing_spoken_email)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SmartCessTopBar(title = stringResource(R.string.briefing_title), onBack = onBack)
        if (state.loading) {
            Text(
                text = stringResource(R.string.status_loading),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            return@Column
        }
        state.errorRes?.let {
            SmartCessBanner(
                title = stringResource(R.string.banner_error_title),
                message = stringResource(it),
                kind = BannerKind.ERROR,
            )
        }
        if (state.offline) {
            SmartCessBanner(message = stringResource(R.string.banner_offline), kind = BannerKind.OFFLINE)
        }
        if (state.isEmpty) {
            Text(text = stringResource(R.string.briefing_empty), style = MaterialTheme.typography.bodyLarge)
            SmartCessSecondaryButton(
                text = stringResource(R.string.action_listen),
                contentDescription = stringResource(R.string.briefing_listen_desc),
                onClick = { viewModel.speak(spokenEmpty) },
                modifier = Modifier.fillMaxWidth(),
            )
            return@Column
        }
        state.aiBriefing?.let {
            SmartCessCard(heading = stringResource(R.string.briefing_title)) {
                Text(text = it, style = MaterialTheme.typography.bodyLarge)
            }
        }
        SmartCessPrimaryButton(
            text = stringResource(R.string.action_listen),
            contentDescription = stringResource(R.string.briefing_listen_desc),
            onClick = {
                viewModel.speak(
                    buildSpokenBriefing(
                        spokenIntro, spokenEmpty, spokenOverdue, spokenDue, spokenEmail, state,
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        BriefingSection(
            title = stringResource(R.string.briefing_section_overdue),
            count = state.overdueBills.size,
        ) {
            state.overdueBills.forEach { bill ->
                SmartCessListItem(
                    title = stringResource(
                        R.string.bill_item_desc,
                        bill.provider,
                        SmartCessFormat.money(bill.amount, bill.currency),
                        SmartCessFormat.isoDate(bill.dueDate),
                    ),
                    onClick = { onBill(bill.id) },
                    trailing = { SmartCessStatusChip(stringResource(R.string.chip_overdue)) },
                )
            }
        }
        BriefingSection(
            title = stringResource(R.string.briefing_section_due_soon),
            count = state.billsDueSoon.size,
        ) {
            state.billsDueSoon.forEach { bill ->
                SmartCessListItem(
                    title = stringResource(
                        R.string.bill_item_desc,
                        bill.provider,
                        SmartCessFormat.money(bill.amount, bill.currency),
                        SmartCessFormat.isoDate(bill.dueDate),
                    ),
                    onClick = { onBill(bill.id) },
                    trailing = { SmartCessStatusChip(stringResource(R.string.chip_due_soon)) },
                )
            }
        }
        BriefingSection(
            title = stringResource(R.string.briefing_section_appointments),
            count = state.appointments.size,
        ) {
            state.appointments.forEach { appt ->
                SmartCessListItem(
                    title = stringResource(
                        R.string.appt_item_desc,
                        appt.title,
                        SmartCessFormat.instantDate(appt.startMillis),
                        SmartCessFormat.instantTime(appt.startMillis),
                    ),
                    onClick = { onAppointment(appt.id) },
                )
            }
        }
        BriefingSection(
            title = stringResource(R.string.briefing_section_email),
            count = state.urgentEmails.size,
        ) {
            state.urgentEmails.forEach { email ->
                SmartCessListItem(
                    title = email.subject,
                    subtitle = stringResource(R.string.email_from, email.sender),
                    onClick = onEmail,
                    trailing = { SmartCessUrgencyChip(email.urgency) },
                )
            }
        }
        BriefingSection(
            title = stringResource(R.string.briefing_section_documents),
            count = state.documents.size,
        ) {
            state.documents.forEach { doc ->
                SmartCessListItem(
                    title = stringResource(R.string.documents_item_desc, doc.title, doc.type),
                    subtitle = doc.deadline?.let { stringResource(R.string.documents_deadline, SmartCessFormat.isoDate(it)) },
                    onClick = { onDocument(doc.id) },
                )
            }
        }
        BriefingSection(
            title = stringResource(R.string.briefing_section_routines),
            count = state.routines.size,
        ) {
            state.routines.forEach { routine ->
                SmartCessListItem(
                    title = stringResource(R.string.routine_item_desc, routine.title, routine.scheduleRule),
                    onClick = { onRoutine(routine.id) },
                )
            }
        }
        SmartCessSecondaryButton(
            text = stringResource(R.string.action_stop),
            onClick = { viewModel.stop() },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun BriefingSection(
    title: String,
    count: Int,
    content: @Composable () -> Unit,
) {
    if (count == 0) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        SmartCessCard { content() }
    }
}

private fun buildSpokenBriefing(
    intro: String,
    empty: String,
    overdueTemplate: String,
    dueTemplate: String,
    emailTemplate: String,
    state: BriefingUiState,
): String {
    if (state.isEmpty) return empty
    state.aiBriefing?.let { return "$intro $it" }
    val parts = mutableListOf(intro)
    state.overdueBills.forEach {
        parts.add(overdueTemplate.format(it.provider, SmartCessFormat.money(it.amount, it.currency)))
    }
    state.billsDueSoon.forEach {
        parts.add(dueTemplate.format(it.provider, SmartCessFormat.isoDate(it.dueDate)))
    }
    state.appointments.forEach { parts.add(it.title) }
    state.urgentEmails.forEach { parts.add(emailTemplate.format(it.subject, it.sender)) }
    state.routines.forEach { parts.add(it.title) }
    state.documents.forEach { parts.add(it.title) }
    return parts.joinToString(" ")
}
