package com.smartcess.app.features.routines

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
import androidx.compose.ui.semantics.LiveRegionMode
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

/** Routine detail: completion tracking with streak, delete with confirmation. */
@Composable
fun RoutineDetailScreen(
    routineId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RoutineDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var showCompleteConfirm by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(routineId) { viewModel.load(routineId) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SmartCessTopBar(title = stringResource(R.string.routine_title), onBack = onBack)
        if (state.loading) {
            Text(
                text = stringResource(R.string.status_loading),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            return@Column
        }
        val routine = state.routine
        if (state.notFound || routine == null) {
            SmartCessBanner(
                message = stringResource(R.string.error_not_found),
                kind = BannerKind.ERROR,
            )
            return@Column
        }
        state.messageRes?.let {
            SmartCessBanner(message = stringResource(it), kind = BannerKind.INFO)
        }
        SmartCessCard(heading = routine.title) {
            Text(text = routine.title, style = MaterialTheme.typography.titleMedium)
            SmartCessListItem(
                title = routineKindLabel(routine.kind),
                subtitle = routine.scheduleRule,
            )
            Text(
                text = stringResource(R.string.routine_streak, routine.streakCount),
                style = MaterialTheme.typography.bodyMedium,
            )
            routine.nextDueMillis?.let {
                Text(
                    text = stringResource(R.string.routine_next_due, SmartCessFormat.instantDateTime(it)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            routine.notes?.let {
                SmartCessListItem(
                    title = stringResource(R.string.routine_notes_label),
                    subtitle = it,
                )
            }
        }
        SmartCessPrimaryButton(
            text = stringResource(R.string.routine_complete),
            onClick = { showCompleteConfirm = true },
            modifier = Modifier.fillMaxWidth(),
        )
        SmartCessSecondaryButton(
            text = stringResource(R.string.action_delete),
            onClick = { showDeleteConfirm = true },
            modifier = Modifier.fillMaxWidth(),
        )
        SmartCessBanner(message = stringResource(R.string.routine_no_advice), kind = BannerKind.INFO)
    }

    val routine = state.routine
    if (showCompleteConfirm && routine != null) {
        SmartCessConfirmDialog(
            title = stringResource(R.string.routine_complete_title),
            message = stringResource(R.string.routine_complete_message, routine.title),
            confirmText = stringResource(R.string.routine_complete),
            onConfirm = { showCompleteConfirm = false; viewModel.confirmComplete() },
            onDismiss = { showCompleteConfirm = false },
        )
    }
    if (showDeleteConfirm && routine != null) {
        SmartCessConfirmDialog(
            title = stringResource(R.string.routine_delete_title),
            message = stringResource(R.string.routine_delete_message, routine.title),
            confirmText = stringResource(R.string.action_delete),
            onConfirm = { showDeleteConfirm = false; viewModel.delete(onBack) },
            onDismiss = { showDeleteConfirm = false },
        )
    }
}
