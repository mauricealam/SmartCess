package com.smartcess.app.features.home

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.smartcess.app.R
import com.smartcess.app.ui.components.BannerKind
import com.smartcess.app.ui.components.OpsButton
import com.smartcess.app.ui.components.OpsCommandBar
import com.smartcess.app.ui.components.OpsQueueRow
import com.smartcess.app.ui.components.SmartCessBanner
import com.smartcess.app.ui.components.SmartCessCard
import com.smartcess.app.ui.components.SmartCessHomeHeader
import com.smartcess.app.ui.components.SmartCessMoreSheet
import com.smartcess.app.ui.components.SmartCessSecondaryButton
import com.smartcess.app.ui.components.SmartCessTextAction
import com.smartcess.app.core.session.HealthState
import com.smartcess.app.ui.theme.SmartCessTheme

/**
 * Home with progressive disclosure ONLY: brand, one Today card (max 3 urgent
 * + See all) up top; Talk, Scan Bill dual-action pair, More bottom sheet,
 * and Settings pinned to the bottom thumb-reach zone. No module-button grid.
 * TalkBack order follows the visual order: brand, greeting, Today queue,
 * Talk, Scan, More, Settings.
 *
 * Adaptation: compact width (phones, narrow multi-window) uses the portrait
 * layout above. Medium/expanded width (landscape, unfolded foldables, wide
 * windows) restructures side-by-side — queue informs on the left, actions
 * execute on the right — capped at 840dp and centered, never stretched.
 * Composition order always matches visual order in both directions.
 *
 * Night Ops expression: the screen is a console — numbered Today queue with
 * status words, matched Talk + Scan command bars. Day Ops light
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
    val health by viewModel.health.collectAsState()
    var showMore by remember { mutableStateOf(false) }
    val wide = isWideWindow()

    Box(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .displayCutoutPadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        if (wide) {
            HomeWide(
                state = state,
                health = health,
                onTalk = onTalk,
                onToday = onToday,
                onScanBill = onScanBill,
                onMore = { showMore = true },
                onSettings = onSettings,
                onRefresh = viewModel::refresh,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 840.dp)
                    .padding(16.dp),
            )
        } else {
            HomePortrait(
                state = state,
                health = health,
                onTalk = onTalk,
                onToday = onToday,
                onScanBill = onScanBill,
                onMore = { showMore = true },
                onSettings = onSettings,
                onRefresh = viewModel::refresh,
                modifier = Modifier.padding(16.dp),
            )
        }
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

/** Wide (non-compact) window: landscape, unfolded, wide multi-window. */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
private fun isWideWindow(): Boolean {
    val activity = LocalContext.current as? Activity ?: return false
    return calculateWindowSizeClass(activity).widthSizeClass != WindowWidthSizeClass.Compact
}

/**
 * Portrait: zoned rhythm (never uniform) — 8dp binds the header pair, 12dp
 * sections the Today region, a 12dp break sets off the pinned cluster.
 */
@Composable
private fun HomePortrait(
    state: HomeUiState,
    health: HealthState,
    onTalk: () -> Unit,
    onToday: () -> Unit,
    onScanBill: () -> Unit,
    onMore: () -> Unit,
    onSettings: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        SmartCessHomeHeader()
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.home_greeting),
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(12.dp))
        if (health == HealthState.WAKING) {
            SmartCessBanner(message = stringResource(R.string.backend_warming), kind = BannerKind.INFO)
            Spacer(Modifier.height(12.dp))
        }
        if (health == HealthState.DOWN) {
            SmartCessBanner(message = stringResource(R.string.backend_down), kind = BannerKind.ERROR)
            Spacer(Modifier.height(12.dp))
        }
        if (state.aiOffline) {
            SmartCessBanner(
                message = stringResource(R.string.banner_offline),
                kind = BannerKind.OFFLINE,
            )
            Spacer(Modifier.height(12.dp))
        }
        TodayPanel(
            state = state,
            onToday = onToday,
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.height(12.dp))
        ActionCluster(
            onTalk = onTalk,
            onScanBill = onScanBill,
            onMore = onMore,
            onSettings = onSettings,
        )
    }
}

/**
 * Wide: queue informs on the left, actions execute on the right. The action
 * pane scrolls instead of bottom-pinning: landscape is two-handed, and short
 * heights must never clip the cluster.
 */
@Composable
private fun HomeWide(
    state: HomeUiState,
    health: HealthState,
    onTalk: () -> Unit,
    onToday: () -> Unit,
    onScanBill: () -> Unit,
    onMore: () -> Unit,
    onSettings: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxHeight(),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            SmartCessHomeHeader()
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.home_greeting),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(12.dp))
            if (health == HealthState.WAKING) {
                SmartCessBanner(message = stringResource(R.string.backend_warming), kind = BannerKind.INFO)
                Spacer(Modifier.height(12.dp))
            }
            if (health == HealthState.DOWN) {
                SmartCessBanner(message = stringResource(R.string.backend_down), kind = BannerKind.ERROR)
                Spacer(Modifier.height(12.dp))
            }
            if (state.aiOffline) {
                SmartCessBanner(
                    message = stringResource(R.string.banner_offline),
                    kind = BannerKind.OFFLINE,
                )
                Spacer(Modifier.height(12.dp))
            }
            TodayPanel(
                state = state,
                onToday = onToday,
                onRefresh = onRefresh,
                modifier = Modifier.weight(1f),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
        ) {
            ActionCluster(
                onTalk = onTalk,
                onScanBill = onScanBill,
                onMore = onMore,
                onSettings = onSettings,
            )
        }
    }
}

/** Scrollable Today card: title, loading/error/empty/queue branches, actions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TodayPanel(
    state: HomeUiState,
    onToday: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        SmartCessCard {
            Text(
                text = stringResource(R.string.home_today),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .semantics { heading() },
            )
            if (state.loading) {
                Text(
                    text = stringResource(R.string.status_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            } else if (state.loadFailed) {
                Text(
                    text = stringResource(R.string.home_today_error),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
                OpsButton(
                    text = stringResource(R.string.action_retry),
                    onClick = onRefresh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
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
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SmartCessTextAction(
                    text = stringResource(R.string.home_today_refresh),
                    onClick = onRefresh,
                )
                SmartCessTextAction(
                    text = stringResource(R.string.home_today_see_all),
                    onClick = onToday,
                )
            }
        }
    }
}

/** Thumb-zone cluster: matched Talk + hint and Scan pair, More, Settings. */
@Composable
private fun ActionCluster(
    onTalk: () -> Unit,
    onScanBill: () -> Unit,
    onMore: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OpsCommandBar(
                text = stringResource(R.string.home_talk),
                contentDescription = stringResource(R.string.home_talk_desc),
                icon = Icons.Filled.Mic,
                onClick = onTalk,
            )
            Text(
                text = stringResource(R.string.home_talk_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        OpsCommandBar(
            text = stringResource(R.string.home_scan_bill),
            contentDescription = stringResource(R.string.home_scan_bill_desc),
            icon = Icons.Filled.PhotoCamera,
            onClick = onScanBill,
        )
        Spacer(Modifier.height(8.dp))
        SmartCessSecondaryButton(
            text = stringResource(R.string.home_more),
            contentDescription = stringResource(R.string.home_more_desc),
            onClick = onMore,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        SmartCessTextAction(
            text = stringResource(R.string.home_settings),
            onClick = onSettings,
            modifier = Modifier.align(Alignment.CenterHorizontally),
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
