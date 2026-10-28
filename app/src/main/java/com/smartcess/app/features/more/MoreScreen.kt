package com.smartcess.app.features.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartcess.app.R
import com.smartcess.app.ui.components.MoreSheetContent
import com.smartcess.app.ui.components.SmartCessSecondaryButton

/**
 * Back-compat destination for Routes.MORE. Home opens the same content as a
 * ModalBottomSheet; this full-screen variant remains for deep links.
 */
@Composable
fun MoreScreen(
    onBills: () -> Unit,
    onDocuments: () -> Unit,
    onAppointments: () -> Unit,
    onRoutines: () -> Unit,
    onEmail: () -> Unit,
    onSettings: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        MoreSheetContent(
            onBills = onBills,
            onDocuments = onDocuments,
            onAppointments = onAppointments,
            onRoutines = onRoutines,
            onEmail = onEmail,
            onSettings = onSettings,
        )
        SmartCessSecondaryButton(
            text = stringResource(R.string.action_back),
            onClick = onBack,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
