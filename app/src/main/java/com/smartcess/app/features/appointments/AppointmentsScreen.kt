package com.smartcess.app.features.appointments

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
import com.smartcess.app.ui.components.BannerKind
import com.smartcess.app.ui.components.SmartCessBanner
import com.smartcess.app.ui.components.SmartCessCard
import com.smartcess.app.ui.components.SmartCessConfirmDialog
import com.smartcess.app.ui.components.SmartCessListItem
import com.smartcess.app.ui.components.SmartCessPrimaryButton
import com.smartcess.app.ui.components.SmartCessSecondaryButton
import com.smartcess.app.ui.components.SmartCessTextField
import com.smartcess.app.ui.components.SmartCessTopBar
import com.smartcess.app.ui.format.SmartCessFormat

/** Appointment Assistant entry: list + manual add + fill-from-text extraction. */
@Composable
fun AppointmentsScreen(
    onAppointment: (Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AppointmentsViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsState()
    val form by viewModel.form.collectAsState()
    var showForm by remember { mutableStateOf(false) }
    var showSaveConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SmartCessTopBar(title = stringResource(R.string.appointments_title), onBack = onBack)
        form.errorRes?.let {
            SmartCessBanner(
                title = stringResource(R.string.banner_error_title),
                message = stringResource(it),
                kind = BannerKind.ERROR,
            )
        }
        form.messageRes?.let {
            SmartCessBanner(message = stringResource(it), kind = BannerKind.INFO)
        }
        if (!showForm) {
            SmartCessPrimaryButton(
                text = stringResource(R.string.appointments_add),
                onClick = { showForm = true },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            AppointmentForm(
                form = form,
                onForm = viewModel::onForm,
                onFillFromText = viewModel::fillFromText,
                onSave = { showSaveConfirm = true },
            )
        }
        if (items.isEmpty()) {
            Text(text = stringResource(R.string.appointments_empty), style = MaterialTheme.typography.bodyLarge)
        } else {
            SmartCessCard {
                items.forEach { appt ->
                    SmartCessListItem(
                        title = appt.title,
                        subtitle = stringResource(
                            R.string.appt_when,
                            SmartCessFormat.instantDate(appt.startMillis),
                            SmartCessFormat.instantTime(appt.startMillis),
                        ),
                        contentDescription = stringResource(
                            R.string.appt_item_desc,
                            appt.title,
                            SmartCessFormat.instantDate(appt.startMillis),
                            SmartCessFormat.instantTime(appt.startMillis),
                        ),
                        onClick = { onAppointment(appt.id) },
                    )
                }
            }
        }
    }

    if (showSaveConfirm) {
        SmartCessConfirmDialog(
            title = stringResource(R.string.appointments_add),
            message = form.title,
            onConfirm = {
                showSaveConfirm = false
                showForm = false
                viewModel.confirmSave(onAppointment)
            },
            onDismiss = { showSaveConfirm = false },
        )
    }
}

@Composable
private fun AppointmentForm(
    form: AppointmentFormState,
    onForm: (AppointmentFormState) -> Unit,
    onFillFromText: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.appointments_add),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        SmartCessTextField(
            value = form.extractSource,
            onValueChange = { onForm(form.copy(extractSource = it)) },
            label = stringResource(R.string.appt_extract_button),
            hint = stringResource(R.string.appt_extract_hint),
            singleLine = false,
            minLines = 3,
        )
        SmartCessSecondaryButton(
            text = stringResource(R.string.appt_extract_button),
            onClick = onFillFromText,
            enabled = form.extractSource.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        )
        SmartCessTextField(
            value = form.title,
            onValueChange = { onForm(form.copy(title = it)) },
            label = stringResource(R.string.appt_title_label),
            error = form.fieldErrorRes?.let { stringResource(it) },
        )
        SmartCessTextField(
            value = form.provider,
            onValueChange = { onForm(form.copy(provider = it)) },
            label = stringResource(R.string.appt_provider_label),
        )
        SmartCessTextField(
            value = form.location,
            onValueChange = { onForm(form.copy(location = it)) },
            label = stringResource(R.string.appt_location_label),
        )
        SmartCessTextField(
            value = form.doctor,
            onValueChange = { onForm(form.copy(doctor = it)) },
            label = stringResource(R.string.appt_doctor_label),
        )
        SmartCessTextField(
            value = form.startText,
            onValueChange = { onForm(form.copy(startText = it)) },
            label = stringResource(R.string.appt_start_label),
            hint = stringResource(R.string.appt_start_hint),
        )
        SmartCessTextField(
            value = form.endText,
            onValueChange = { onForm(form.copy(endText = it)) },
            label = stringResource(R.string.appt_end_label),
        )
        SmartCessTextField(
            value = form.prepNotes,
            onValueChange = { onForm(form.copy(prepNotes = it)) },
            label = stringResource(R.string.appt_prep_label),
            singleLine = false,
            minLines = 2,
        )
        SmartCessPrimaryButton(
            text = stringResource(R.string.action_save),
            onClick = onSave,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
