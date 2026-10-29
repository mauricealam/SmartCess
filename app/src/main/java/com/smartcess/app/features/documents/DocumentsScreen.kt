package com.smartcess.app.features.documents

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

/** Document Reader entry: list, scan, import with on-device reading + summary. */
@Composable
fun DocumentsScreen(
    onDocument: (Long) -> Unit,
    onScan: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DocumentsViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsState()
    val state by viewModel.state.collectAsState()
    var showSaveConfirm by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.importFile(it) }
    }
    val otherType = stringResource(R.string.documents_type_other)

    Column(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SmartCessTopBar(title = stringResource(R.string.documents_title), onBack = onBack)
        if (state.reading) {
            Text(
                text = stringResource(R.string.documents_reading),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (state.offline) {
            SmartCessBanner(message = stringResource(R.string.banner_offline), kind = BannerKind.OFFLINE)
        }
        if (state.signInRequired) {
            SmartCessBanner(message = stringResource(R.string.auth_sign_in_required), kind = BannerKind.ERROR)
        }
        state.errorRes?.let {
            SmartCessBanner(
                title = stringResource(R.string.banner_error_title),
                message = stringResource(it),
                kind = BannerKind.ERROR,
            )
        }
        state.messageRes?.let {
            SmartCessBanner(message = stringResource(it), kind = BannerKind.INFO)
        }
        state.draft?.let { draft ->
            SmartCessCard(heading = draft.summary) {
                Text(text = draft.summary, style = MaterialTheme.typography.bodyLarge)
            }
            SmartCessPrimaryButton(
                text = stringResource(R.string.action_save),
                onClick = { showSaveConfirm = true },
                modifier = Modifier.fillMaxWidth(),
            )
            SmartCessSecondaryButton(
                text = stringResource(R.string.action_close),
                onClick = { viewModel.clearDraft() },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        SmartCessPrimaryButton(
            text = stringResource(R.string.documents_scan),
            onClick = onScan,
            modifier = Modifier.fillMaxWidth(),
        )
        SmartCessSecondaryButton(
            text = stringResource(R.string.documents_import),
            onClick = { picker.launch("*/*") },
            modifier = Modifier.fillMaxWidth(),
        )
        if (items.isEmpty()) {
            Text(text = stringResource(R.string.documents_empty), style = MaterialTheme.typography.bodyLarge)
        } else {
            SmartCessCard {
                items.forEach { doc ->
                    val typeLabel = doc.type.ifBlank { otherType }.let {
                        if (it == "other") otherType else it
                    }
                    SmartCessListItem(
                        title = doc.title,
                        subtitle = typeLabel,
                        contentDescription = stringResource(
                            R.string.documents_item_desc,
                            doc.title,
                            typeLabel,
                        ),
                        onClick = { onDocument(doc.id) },
                    )
                }
            }
        }
    }

    if (showSaveConfirm && state.draft != null) {
        SmartCessConfirmDialog(
            title = stringResource(R.string.documents_save_title),
            message = state.draft!!.summary,
            onConfirm = { showSaveConfirm = false; viewModel.confirmSaveDraft(onDocument) },
            onDismiss = { showSaveConfirm = false },
        )
    }
}
