package com.smartcess.app.features.settings

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.smartcess.app.R
import com.smartcess.app.ui.components.BannerKind
import com.smartcess.app.ui.components.SmartCessBanner
import com.smartcess.app.ui.components.SmartCessPrimaryButton
import com.smartcess.app.ui.components.SmartCessSecondaryButton
import com.smartcess.app.ui.components.SmartCessTextField
import com.smartcess.app.ui.components.SmartCessTopBar

/** Hidden developer screen: direct-Meta key override. Not for production use. */
@Composable
fun DevScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DevViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SmartCessTopBar(title = stringResource(R.string.dev_title), onBack = onBack)
        Text(
            text = stringResource(R.string.dev_key_hint),
            style = MaterialTheme.typography.bodyLarge,
        )
        state.messageRes?.let {
            SmartCessBanner(message = stringResource(it), kind = BannerKind.INFO)
        }
        SmartCessTextField(
            value = state.keyText,
            onValueChange = viewModel::onKeyChange,
            label = stringResource(R.string.dev_key_label),
            isPassword = true,
        )
        SmartCessPrimaryButton(
            text = stringResource(R.string.action_save),
            onClick = viewModel::save,
            enabled = state.keyText.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.hasKey) {
            SmartCessSecondaryButton(
                text = stringResource(R.string.action_delete),
                onClick = viewModel::clear,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
