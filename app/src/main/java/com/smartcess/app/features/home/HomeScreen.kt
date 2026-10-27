package com.smartcess.app.features.home

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.smartcess.app.R
import com.smartcess.app.ui.components.SmartCessCard
import com.smartcess.app.ui.components.SmartCessHomeHeader
import com.smartcess.app.ui.components.SmartCessListItem
import com.smartcess.app.ui.components.SmartCessMoreSheet
import com.smartcess.app.ui.components.SmartCessPrimaryButton
import com.smartcess.app.ui.components.SmartCessSecondaryButton
import com.smartcess.app.ui.components.SmartCessTextAction

/**
 * Home with progressive disclosure ONLY: Talk, one Today card (max 3 urgent +
 * See all), one primary Scan Bill action, More bottom sheet, Settings link.
 * No module-button grid. At most 6 TalkBack stops before More.
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
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SmartCessHomeHeader()
        Text(
            text = stringResource(R.string.home_greeting),
            style = MaterialTheme.typography.bodyLarge,
        )
        SmartCessPrimaryButton(
            text = stringResource(R.string.home_talk),
            contentDescription = stringResource(R.string.home_talk_desc),
            onClick = onTalk,
            modifier = Modifier.fillMaxWidth(),
        )
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
                state.items.forEach { item ->
                    val text = when (item.args.size) {
                        0 -> stringResource(item.textRes)
                        1 -> stringResource(item.textRes, item.args[0])
                        else -> stringResource(item.textRes, item.args[0], item.args[1])
                    }
                    SmartCessListItem(title = text)
                }
                if (state.extraCount > 0) {
                    Text(
                        text = stringResource(R.string.home_today_more, state.extraCount),
                        style = MaterialTheme.typography.bodyMedium,
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
        SmartCessPrimaryButton(
            text = stringResource(R.string.home_scan_bill),
            contentDescription = stringResource(R.string.home_scan_bill_desc),
            onClick = onScanBill,
            modifier = Modifier.fillMaxWidth(),
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
