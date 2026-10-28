package com.smartcess.app.features.bills

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
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

/** Days-before-due choices for bill reminders (M3). */
private val REMIND_DAY_OPTIONS = listOf(0, 1, 3, 7, 14)

/** Bills list: current + archived, TalkBack-friendly rows. */
@Composable
fun BillsScreen(
    onBill: (Long) -> Unit,
    onScan: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BillsViewModel = hiltViewModel(),
) {
    val active by viewModel.active.collectAsState()
    val archived by viewModel.archived.collectAsState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SmartCessTopBar(title = stringResource(R.string.bills_title), onBack = onBack)
        if (active.isEmpty() && archived.isEmpty()) {
            Text(text = stringResource(R.string.bills_empty), style = MaterialTheme.typography.bodyLarge)
            SmartCessPrimaryButton(
                text = stringResource(R.string.bills_scan_first),
                onClick = onScan,
                modifier = Modifier.fillMaxWidth(),
            )
            return@Column
        }
        if (active.isNotEmpty()) {
            Text(
                text = stringResource(R.string.bills_active_section),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            SmartCessCard {
                active.forEach { bill ->
                    SmartCessListItem(
                        title = bill.provider,
                        subtitle = SmartCessFormat.money(bill.amount, bill.currency) +
                            " · " + SmartCessFormat.isoDate(bill.dueDate),
                        contentDescription = stringResource(
                            R.string.bill_item_desc,
                            bill.provider,
                            SmartCessFormat.money(bill.amount, bill.currency),
                            SmartCessFormat.isoDate(bill.dueDate),
                        ),
                        onClick = { onBill(bill.id) },
                        trailing = {
                            if (bill.confidence < LOW_CONFIDENCE_THRESHOLD) {
                                SmartCessStatusChip(stringResource(R.string.chip_low_confidence))
                            }
                        },
                    )
                }
            }
        }
        if (archived.isNotEmpty()) {
            Text(
                text = stringResource(R.string.bills_archived_section),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            SmartCessCard {
                archived.forEach { bill ->
                    SmartCessListItem(
                        title = bill.provider,
                        subtitle = SmartCessFormat.money(bill.amount, bill.currency) +
                            " · " + SmartCessFormat.isoDate(bill.dueDate),
                        onClick = { onBill(bill.id) },
                    )
                }
            }
        }
        // M2: scanning stays reachable when bills already exist.
        SmartCessSecondaryButton(
            text = stringResource(R.string.bills_scan_first),
            onClick = onScan,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Bill result: scan → extract → validate → review → compare → save/archive → remind.
 * Saves, archives, and reminders all ask for explicit confirmation first.
 */
@Composable
fun BillResultScreen(
    billId: Long,
    onCompare: (Long) -> Unit,
    onRescan: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BillResultViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var showSaveConfirm by remember { mutableStateOf(false) }
    var showArchiveConfirm by remember { mutableStateOf(false) }
    var showRemindOptions by remember { mutableStateOf(false) }
    var showDiscardConfirm by remember { mutableStateOf(false) }
    var remindDays by remember { mutableIntStateOf(3) }

    LaunchedEffect(billId) { viewModel.load(billId) }
    LaunchedEffect(showRemindOptions) {
        if (showRemindOptions) remindDays = viewModel.defaultReminderDays()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SmartCessTopBar(title = stringResource(R.string.bill_result_title), onBack = onBack)
        if (state.loading) {
            Text(
                text = stringResource(R.string.status_loading),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            return@Column
        }
        if (state.notFound || state.bill == null) {
            SmartCessBanner(
                title = stringResource(R.string.banner_error_title),
                message = stringResource(R.string.error_not_found),
                kind = BannerKind.ERROR,
            )
            return@Column
        }
        val bill = state.bill!!
        if (bill.confidence < LOW_CONFIDENCE_THRESHOLD) {
            SmartCessBanner(
                message = stringResource(R.string.bill_low_confidence),
                kind = BannerKind.WARNING,
            )
        }
        state.errorRes?.let {
            SmartCessBanner(
                title = stringResource(R.string.banner_error_title),
                message = stringResource(it),
                kind = BannerKind.ERROR,
            )
        }
        state.savedMessageRes?.let {
            SmartCessBanner(message = stringResource(it), kind = BannerKind.INFO)
        }
        state.reminderMessageRes?.let {
            SmartCessBanner(message = stringResource(it), kind = BannerKind.INFO)
        }
        Text(
            text = stringResource(R.string.bill_review_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        SmartCessCard {
            Column(
                modifier = Modifier.padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SmartCessTextField(
                    value = state.provider,
                    onValueChange = { viewModel.onField(it, state.amount, state.currency, state.dueDate) },
                    label = stringResource(R.string.bill_provider),
                )
                SmartCessTextField(
                    value = state.amount,
                    onValueChange = { viewModel.onField(state.provider, it, state.currency, state.dueDate) },
                    label = stringResource(R.string.bill_amount),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                SmartCessTextField(
                    value = state.currency,
                    onValueChange = { viewModel.onField(state.provider, state.amount, it, state.dueDate) },
                    label = stringResource(R.string.bill_currency),
                )
                SmartCessTextField(
                    value = state.dueDate,
                    onValueChange = { viewModel.onField(state.provider, state.amount, state.currency, it) },
                    label = stringResource(R.string.bill_due_date),
                )
                bill.accountReference?.let {
                    SmartCessListItem(
                        title = stringResource(R.string.bill_account),
                        subtitle = it,
                    )
                }
                bill.displayPeriod()?.let {
                    SmartCessListItem(
                        title = stringResource(R.string.bill_period),
                        subtitle = it,
                    )
                }
                if (bill.summary.isNotBlank()) {
                    SmartCessListItem(
                        title = stringResource(R.string.bill_summary_label),
                        subtitle = bill.summary,
                    )
                }
                if (bill.missingFields.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.bill_missing_fields, bill.missingFields.joinToString()),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        }
        val spoken = stringResource(
            R.string.bill_spoken,
            bill.provider,
            SmartCessFormat.money(bill.amount, bill.currency),
            SmartCessFormat.isoDate(bill.dueDate),
            bill.summary,
        )
        SmartCessSecondaryButton(
            text = stringResource(R.string.bill_read_aloud),
            onClick = { viewModel.speak(spoken) },
            modifier = Modifier.fillMaxWidth(),
        )
        SmartCessCard(heading = stringResource(R.string.bill_question_label)) {
            SmartCessTextField(
                value = state.question,
                onValueChange = viewModel::onQuestion,
                label = stringResource(R.string.bill_question_label),
                hint = stringResource(R.string.bill_question_hint),
            )
            SmartCessSecondaryButton(
                text = stringResource(R.string.bill_ask),
                onClick = viewModel::ask,
                modifier = Modifier.fillMaxWidth(),
            )
            state.answer?.let { answer ->
                Text(
                    text = answer,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            if (state.noAnswer) {
                Text(
                    text = stringResource(R.string.bill_no_answer),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        SmartCessSecondaryButton(
            text = stringResource(R.string.action_remind),
            onClick = { showRemindOptions = true },
            modifier = Modifier.fillMaxWidth(),
        )
        SmartCessPrimaryButton(
            text = stringResource(R.string.bill_save),
            onClick = { showSaveConfirm = true },
            modifier = Modifier.fillMaxWidth(),
        )
        SmartCessSecondaryButton(
            text = stringResource(R.string.action_compare),
            onClick = { onCompare(bill.id) },
            modifier = Modifier.fillMaxWidth(),
        )
        SmartCessSecondaryButton(
            text = stringResource(R.string.action_archive),
            onClick = { showArchiveConfirm = true },
            modifier = Modifier.fillMaxWidth(),
        )
        SmartCessSecondaryButton(
            text = stringResource(R.string.bill_rescan),
            onClick = onRescan,
            modifier = Modifier.fillMaxWidth(),
        )
        SmartCessSecondaryButton(
            text = stringResource(R.string.bill_discard),
            onClick = { showDiscardConfirm = true },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    if (showSaveConfirm) {
        SmartCessConfirmDialog(
            title = stringResource(R.string.bill_save_confirm_title),
            message = stringResource(
                R.string.bill_save_confirm_message,
                state.provider,
                state.amount,
                state.dueDate,
            ),
            confirmText = stringResource(R.string.bill_save),
            onConfirm = { showSaveConfirm = false; viewModel.confirmSave() },
            onDismiss = { showSaveConfirm = false },
        )
    }
    if (showArchiveConfirm) {
        SmartCessConfirmDialog(
            title = stringResource(R.string.bill_archive_confirm_title),
            message = stringResource(R.string.bill_archive_confirm_message),
            confirmText = stringResource(R.string.action_archive),
            onConfirm = { showArchiveConfirm = false; viewModel.confirmArchive(onBack) },
            onDismiss = { showArchiveConfirm = false },
        )
    }
    if (showRemindOptions) {
        SmartCessConfirmDialog(
            title = stringResource(R.string.bill_remind_options),
            message = pluralStringResource(R.plurals.bill_remind_days, remindDays, remindDays),
            confirmText = stringResource(R.string.action_remind),
            onConfirm = { showRemindOptions = false; viewModel.confirmReminder(remindDays) },
            onDismiss = { showRemindOptions = false },
        ) {
            // M3: days-before picker (preselected from Settings default).
            REMIND_DAY_OPTIONS.forEach { days ->
                SmartCessListItem(
                    title = pluralStringResource(R.plurals.bill_remind_days, days, days),
                    onClick = { remindDays = days },
                    trailing = {
                        if (remindDays == days) {
                            Text(
                                text = stringResource(R.string.option_selected),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    },
                )
            }
        }
    }
    if (showDiscardConfirm) {
        SmartCessConfirmDialog(
            title = stringResource(R.string.bill_discard_confirm_title),
            message = stringResource(R.string.bill_discard_confirm_message, state.provider),
            confirmText = stringResource(R.string.bill_discard),
            onConfirm = { showDiscardConfirm = false; viewModel.confirmDiscard(onBack) },
            onDismiss = { showDiscardConfirm = false },
        )
    }
}

/** Bill comparison: current vs previous bill with change summary. */
@Composable
fun ComparisonScreen(
    billId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ComparisonViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(billId) { viewModel.load(billId) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SmartCessTopBar(title = stringResource(R.string.bill_compare_title), onBack = onBack)
        if (state.loading) {
            Text(
                text = stringResource(R.string.status_loading),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            return@Column
        }
        val current = state.current
        val previous = state.previous
        if (current == null) {
            SmartCessBanner(
                message = stringResource(R.string.error_not_found),
                kind = BannerKind.ERROR,
            )
            return@Column
        }
        if (previous == null) {
            Text(text = stringResource(R.string.bill_no_previous), style = MaterialTheme.typography.bodyLarge)
            return@Column
        }
        if (state.offline) {
            SmartCessBanner(message = stringResource(R.string.banner_offline), kind = BannerKind.OFFLINE)
        }
        SmartCessCard(heading = stringResource(R.string.comparison_current)) {
            SmartCessListItem(
                title = stringResource(R.string.comparison_current),
                subtitle = SmartCessFormat.money(current.amount, current.currency) +
                    " · " + SmartCessFormat.isoDate(current.dueDate),
            )
            SmartCessListItem(
                title = stringResource(R.string.comparison_previous),
                subtitle = SmartCessFormat.money(previous.amount, previous.currency) +
                    " · " + SmartCessFormat.isoDate(previous.dueDate),
            )
        }
        SmartCessCard {
            Text(
                text = comparisonSummary(current.amount, previous.amount, current.currency),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        state.recommendation?.let {
            SmartCessCard(heading = stringResource(R.string.comparison_recommendation)) {
                Text(text = it, style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (state.reasons.isNotEmpty()) {
            Text(
                text = stringResource(R.string.comparison_reasons),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            SmartCessCard {
                state.reasons.forEach { reason ->
                    SmartCessListItem(title = reason)
                }
            }
        }
    }
}

@Composable
private fun comparisonSummary(current: Double, previous: Double, currency: String): String {
    val difference = current - previous
    if (difference == 0.0) return stringResource(R.string.comparison_no_change)
    val percentValue = if (previous != 0.0) kotlin.math.abs(difference / previous) * 100 else 0.0
    val percent = stringResource(R.string.comparison_percent, percentValue)
    return if (difference > 0) {
        stringResource(
            R.string.comparison_difference_up,
            SmartCessFormat.money(difference, currency),
            percent,
        )
    } else {
        stringResource(
            R.string.comparison_difference_down,
            SmartCessFormat.money(-difference, currency),
            percent,
        )
    }
}
