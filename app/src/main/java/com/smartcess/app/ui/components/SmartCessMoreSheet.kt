package com.smartcess.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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

/**
 * Spec More bottom sheet, chunked 3 + 2 + 1: Review (Bills, Documents,
 * Email), Plan (Appointments, Routines), Settings as a separated utility
 * footer. Recents-first was rejected: reordering destinations would break
 * screen-reader spatial memory.
 */
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
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.more_title),
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier
                .padding(bottom = 8.dp)
                .semantics { heading() },
        )
        MoreSectionHead(text = stringResource(R.string.more_section_review))
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
            title = stringResource(R.string.more_email),
            contentDescription = stringResource(R.string.more_email),
            onClick = onEmail,
        )
        MoreSectionHead(
            text = stringResource(R.string.more_section_plan),
            modifier = Modifier.padding(top = 12.dp),
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
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        SmartCessListItem(
            title = stringResource(R.string.more_settings),
            contentDescription = stringResource(R.string.more_settings),
            onClick = onSettings,
        )
    }
}

/** Chunk heading: doubles as a TalkBack heading-navigation target. */
@Composable
private fun MoreSectionHead(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier
            .padding(horizontal = 8.dp)
            .semantics { heading() },
    )
}
