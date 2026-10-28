package com.smartcess.app.features.appointments

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
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

/** Appointment detail: calendar event + travel reminder, both approval-gated. */
@Composable
fun AppointmentDetailScreen(
    appointmentId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AppointmentDetailViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    var showCalendarConfirm by remember { mutableStateOf(false) }
    var showTravelOptions by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var travelMinutes by remember { mutableStateOf(60) }
    val calendarLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.confirmCalendar() else viewModel.onCalendarDenied()
    }
    fun hasCalendarPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    LaunchedEffect(appointmentId) { viewModel.load(appointmentId) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SmartCessTopBar(title = stringResource(R.string.appointment_title), onBack = onBack)
        if (state.loading) {
            Text(
                text = stringResource(R.string.status_loading),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            return@Column
        }
        val appt = state.appointment
        if (state.notFound || appt == null) {
            SmartCessBanner(
                message = stringResource(R.string.error_not_found),
                kind = BannerKind.ERROR,
            )
            return@Column
        }
        state.messageRes?.let {
            SmartCessBanner(message = stringResource(it), kind = BannerKind.INFO)
        }
        SmartCessCard(heading = appt.title) {
            Text(text = appt.title, style = MaterialTheme.typography.titleMedium)
            SmartCessListItem(
                title = stringResource(R.string.reminder_when),
                subtitle = stringResource(
                    R.string.appt_when,
                    SmartCessFormat.instantDate(appt.startMillis),
                    SmartCessFormat.instantTime(appt.startMillis),
                ),
            )
            appt.provider?.let {
                SmartCessListItem(title = stringResource(R.string.appt_provider_label), subtitle = it)
            }
            appt.location?.let {
                SmartCessListItem(title = stringResource(R.string.appt_location_label), subtitle = it)
            }
            appt.doctorName?.let {
                SmartCessListItem(title = stringResource(R.string.appt_doctor_label), subtitle = it)
            }
            appt.prepNotes?.let {
                SmartCessListItem(title = stringResource(R.string.appt_prep_label), subtitle = it)
            }
            appt.travelReminderMinutes?.let {
                SmartCessListItem(
                    title = stringResource(R.string.appt_travel_minutes, it),
                )
            }
        }
        SmartCessPrimaryButton(
            text = stringResource(R.string.appt_calendar_title),
            onClick = { showCalendarConfirm = true },
            modifier = Modifier.fillMaxWidth(),
        )
        SmartCessSecondaryButton(
            text = stringResource(R.string.appt_travel_title),
            onClick = { showTravelOptions = true },
            modifier = Modifier.fillMaxWidth(),
        )
        SmartCessSecondaryButton(
            text = stringResource(R.string.action_delete),
            onClick = { showDeleteConfirm = true },
            modifier = Modifier.fillMaxWidth(),
        )
        if (showTravelOptions) {
            SmartCessCard {
                listOf(30, 60, 90).forEach { minutes ->
                    SmartCessListItem(
                        title = stringResource(R.string.appt_travel_minutes, minutes),
                        onClick = { travelMinutes = minutes },
                        trailing = {
                            if (travelMinutes == minutes) {
                                Text(
                                    text = stringResource(R.string.option_selected),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        },
                    )
                }
            }
            SmartCessPrimaryButton(
                text = stringResource(R.string.action_confirm),
                onClick = { showTravelOptions = false; viewModel.confirmTravelReminder(travelMinutes) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    val appt = state.appointment
    if (showCalendarConfirm && appt != null) {
        SmartCessConfirmDialog(
            title = stringResource(R.string.appt_calendar_title),
            message = stringResource(R.string.appt_calendar_message, appt.title),
            onConfirm = {
                showCalendarConfirm = false
                if (hasCalendarPermission()) {
                    viewModel.confirmCalendar()
                } else {
                    calendarLauncher.launch(Manifest.permission.WRITE_CALENDAR)
                }
            },
            onDismiss = { showCalendarConfirm = false },
        )
    }
    if (showDeleteConfirm && appt != null) {
        SmartCessConfirmDialog(
            title = stringResource(R.string.appt_delete_title),
            message = stringResource(R.string.appt_delete_message, appt.title),
            confirmText = stringResource(R.string.action_delete),
            onConfirm = { showDeleteConfirm = false; viewModel.delete(onBack) },
            onDismiss = { showDeleteConfirm = false },
        )
    }
}
