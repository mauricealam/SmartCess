package com.smartcess.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.smartcess.app.R

/** Spec More bottom sheet: Bills / Documents / Appointments / Routines / Settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartCessMoreSheet(
    onBills: () -> Unit,
    onDocuments: () -> Unit,
    onAppointments: () -> Unit,
    onRoutines: () -> Unit,
    onEmail: () -> Unit,
    onSettings: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = modifier,
    ) {
        MoreSheetContent(
            onBills = onBills,
            onDocuments = onDocuments,
            onAppointments = onAppointments,
            onRoutines = onRoutines,
            onEmail = onEmail,
            onSettings = onSettings,
        )
    }
}

@Composable
fun MoreSheetContent(
    onBills: () -> Unit,
    onDocuments: () -> Unit,
    onAppointments: () -> Unit,
    onRoutines: () -> Unit,
    onEmail: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.more_title),
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier
                .padding(bottom = 8.dp)
                .semantics { heading() },
        )
        SmartCessListItem(
            title = stringResource(R.string.more_bills),
            contentDescription = stringResource(R.string.more_bills),
            onClick = onBills,
        )
        SmartCessListItem(
            title = stringResource(R.string.more_documents),
            contentDescription = stringResource(R.string.more_documents),
            onClick = onDocuments,
        )
        SmartCessListItem(
            title = stringResource(R.string.more_appointments),
            contentDescription = stringResource(R.string.more_appointments),
            onClick = onAppointments,
        )
        SmartCessListItem(
            title = stringResource(R.string.more_routines),
            contentDescription = stringResource(R.string.more_routines),
            onClick = onRoutines,
        )
        SmartCessListItem(
            title = stringResource(R.string.more_email),
            contentDescription = stringResource(R.string.more_email),
            onClick = onEmail,
        )
        SmartCessListItem(
            title = stringResource(R.string.more_settings),
            contentDescription = stringResource(R.string.more_settings),
            onClick = onSettings,
        )
    }
}
