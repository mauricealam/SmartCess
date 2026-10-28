package com.smartcess.app.features.documents

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.smartcess.app.R
import com.smartcess.app.data.repository.LOW_CONFIDENCE_THRESHOLD
import com.smartcess.app.ui.components.BannerKind
import com.smartcess.app.ui.components.SmartCessBanner
import com.smartcess.app.ui.components.SmartCessCard
import com.smartcess.app.ui.components.SmartCessConfirmDialog
import com.smartcess.app.ui.components.SmartCessListItem
import com.smartcess.app.ui.components.SmartCessPrimaryButton
import com.smartcess.app.ui.components.SmartCessSecondaryButton
import com.smartcess.app.ui.components.SmartCessStatusChip
import com.smartcess.app.ui.components.SmartCessTextField
import com.smartcess.app.ui.components.SmartCessTopBar
import com.smartcess.app.ui.format.SmartCessFormat

/** Document summary + Q&A: plain summary, key points, deadlines, questions. */
@Composable
fun DocumentResultScreen(
    docId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DocumentResultViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var question by remember { mutableStateOf("") }
    var showRemindConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(docId) { viewModel.load(docId) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SmartCessTopBar(title = stringResource(R.string.document_title), onBack = onBack)
        if (state.loading) {
            Text(
                text = stringResource(R.string.status_loading),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            return@Column
        }
        val doc = state.document
        if (state.notFound || doc == null) {
            SmartCessBanner(
                message = stringResource(R.string.error_not_found),
                kind = BannerKind.ERROR,
            )
            return@Column
        }
        if (doc.confidence < LOW_CONFIDENCE_THRESHOLD) {
            SmartCessBanner(
                message = stringResource(R.string.bill_low_confidence),
                kind = BannerKind.WARNING,
            )
        }
        state.messageRes?.let {
            SmartCessBanner(message = stringResource(it), kind = BannerKind.INFO)
        }
        Text(
            text = doc.title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        if (doc.confidence < LOW_CONFIDENCE_THRESHOLD) {
            SmartCessStatusChip(stringResource(R.string.chip_low_confidence))
        }
        Text(
            text = stringResource(R.string.documents_summary_label),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        SmartCessCard {
            Text(text = doc.summary, style = MaterialTheme.typography.bodyLarge)
        }
        SmartCessPrimaryButton(
            text = stringResource(R.string.action_listen),
            onClick = { viewModel.speak(doc.summary) },
            modifier = Modifier.fillMaxWidth(),
        )
        if (doc.keyPoints.isNotEmpty()) {
            Text(
                text = stringResource(R.string.documents_key_points),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            SmartCessCard {
                doc.keyPoints.forEach { point -> SmartCessListItem(title = point) }
            }
        }
        doc.deadline?.let {
            SmartCessCard {
                SmartCessListItem(
                    title = stringResource(R.string.documents_deadline, SmartCessFormat.isoDate(it)),
                )
            }
            SmartCessSecondaryButton(
                text = stringResource(R.string.action_remind),
                onClick = { showRemindConfirm = true },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        doc.amount?.let {
            SmartCessCard {
                SmartCessListItem(
                    title = stringResource(R.string.documents_amount, it.toString()),
                )
            }
        }
        Text(
            text = stringResource(R.string.documents_ask_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        SmartCessTextField(
            value = question,
            onValueChange = { question = it },
            label = stringResource(R.string.documents_ask_title),
            hint = stringResource(R.string.documents_ask_hint),
        )
        SmartCessSecondaryButton(
            text = stringResource(R.string.documents_ask_button),
            onClick = { viewModel.ask(question) },
            enabled = question.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        )
        state.answer?.let { answer ->
            Text(
                text = stringResource(R.string.documents_answer_label),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            SmartCessCard {
                Text(
                    text = answer,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            SmartCessSecondaryButton(
                text = stringResource(R.string.action_listen),
                onClick = { viewModel.speak(answer) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (state.noAnswer) {
            SmartCessBanner(
                message = stringResource(R.string.documents_no_answer),
                kind = BannerKind.INFO,
            )
        }
    }

    val doc = state.document
    if (showRemindConfirm && doc?.deadline != null) {
        SmartCessConfirmDialog(
            title = stringResource(R.string.action_remind),
            message = stringResource(
                R.string.reminder_confirm_message,
                doc.title,
                SmartCessFormat.isoDate(doc.deadline!!),
            ),
            confirmText = stringResource(R.string.action_remind),
            onConfirm = { showRemindConfirm = false; viewModel.confirmDeadlineReminder() },
            onDismiss = { showRemindConfirm = false },
        )
    }
}
