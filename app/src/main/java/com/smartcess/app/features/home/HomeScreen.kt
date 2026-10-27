package com.smartcess.app.features.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.smartcess.app.R
import com.smartcess.app.ui.components.OpsCommandBar
import com.smartcess.app.ui.components.OpsHeroAction
import com.smartcess.app.ui.components.OpsQueueRow
import com.smartcess.app.ui.components.SmartCessCard
import com.smartcess.app.ui.components.SmartCessHomeHeader
import com.smartcess.app.ui.components.SmartCessMoreSheet
import com.smartcess.app.ui.components.SmartCessSecondaryButton
import com.smartcess.app.ui.components.SmartCessTextAction
import com.smartcess.app.ui.theme.SmartCessTheme

/**
 * Home with progressive disclosure ONLY: brand, one Today card (max 3 urgent
 * + See all) up top; Talk, one primary Scan Bill action, More bottom sheet,
 * and Settings pinned to the bottom thumb-reach zone. No module-button grid.
 * TalkBack order follows the visual order: brand, greeting, Today queue,
 * Talk, Scan, More, Settings.
 *
 * Night Ops expression: the screen is a console — numbered Today queue with
 * status words, Talk command bar, one monumental Scan hero. Day Ops light
 * mode carries the same system on paper grounds.
 */
@Composable
fun HomeScreen(
    onTalk: () -> Unit,
    onToday: () -> Unit,
    onScanBill: () -> Unit,
    onBills: () -> Unit,
    onDocuments: () -> Unit,
    onAppointments: () -> Unit,
    onRoutines: () -> Unit,
    onEmail: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var showMore by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SmartCessHomeHeader()
        Text(
            text = stringResource(R.string.home_greeting),
            style = MaterialTheme.typography.bodyLarge,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            SmartCessCard(
                heading = stringResource(R.string.home_today),
                onClick = onToday,
            ) {
                Text(
                    text = stringResource(R.string.home_today),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                if (state.loading) {
                    Text(
                        text = stringResource(R.string.status_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .padding(horizontal = 8.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                } else if (state.items.isEmpty()) {
                    Text(
                        text = stringResource(R.string.home_today_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                } else {
                    state.items.forEachIndexed { rank, item ->
                        val full = when (item.args.size) {
                            0 -> stringResource(item.textRes)
                            1 -> stringResource(item.textRes, item.args[0])
                            else -> stringResource(item.textRes, item.args[0], item.args[1])
                        }
                        OpsQueueRow(
                            index = "%03d".format(rank + 1),
                            title = item.args.firstOrNull() ?: full,
                            meta = full,
                            status = stringResource(statusFor(item.textRes)),
                            statusColor = statusColorFor(item.textRes),
                        )
                    }
                    if (state.extraCount > 0) {
                        Text(
                            text = stringResource(R.string.home_today_more, state.extraCount),
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                }
                SmartCessTextAction(
                    text = stringResource(R.string.home_today_see_all),
                    onClick = onToday,
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }
        OpsCommandBar(
            text = stringResource(R.string.home_talk),
            contentDescription = stringResource(R.string.home_talk_desc),
            icon = Icons.Filled.Mic,
            onClick = onTalk,
        )
        OpsHeroAction(
            text = stringResource(R.string.home_scan_bill),
            contentDescription = stringResource(R.string.home_scan_bill_desc),
            icon = Icons.Filled.PhotoCamera,
            onClick = onScanBill,
        )
        SmartCessSecondaryButton(
            text = stringResource(R.string.home_more),
            contentDescription = stringResource(R.string.home_more_desc),
            onClick = { showMore = true },
            modifier = Modifier.fillMaxWidth(),
        )
        SmartCessTextAction(
            text = stringResource(R.string.home_settings),
            onClick = onSettings,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }

    if (showMore) {
        SmartCessMoreSheet(
            onBills = { showMore = false; onBills() },
            onDocuments = { showMore = false; onDocuments() },
            onAppointments = { showMore = false; onAppointments() },
            onRoutines = { showMore = false; onRoutines() },
            onEmail = { showMore = false; onEmail() },
            onSettings = { showMore = false; onSettings() },
            onDismiss = { showMore = false },
        )
    }
}

/** Queue status words: overdue acts now, time-bound is due soon, rest queues. */
private fun statusFor(textRes: Int): Int {
    return when (textRes) {
        R.string.home_today_overdue -> R.string.queue_status_overdue
        R.string.home_today_due_soon,
        R.string.home_today_appointment,
        R.string.home_today_document,
        -> R.string.queue_status_due_soon
        else -> R.string.queue_status_queued
    }
}

/** Status tint follows the words; the words carry the meaning, never color alone. */
@Composable
private fun statusColorFor(textRes: Int): Color {
    return when (textRes) {
        R.string.home_today_overdue -> MaterialTheme.colorScheme.error
        R.string.home_today_due_soon,
        R.string.home_today_appointment,
        R.string.home_today_document,
        -> SmartCessTheme.extra.warning
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}
