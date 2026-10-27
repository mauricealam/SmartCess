package com.smartcess.app.features.voice

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.smartcess.app.R
import com.smartcess.app.ui.components.BannerKind
import com.smartcess.app.ui.components.SmartCessBanner
import com.smartcess.app.ui.components.SmartCessPrimaryButton
import com.smartcess.app.ui.components.SmartCessSecondaryButton
import com.smartcess.app.ui.components.SmartCessTextAction
import com.smartcess.app.ui.components.SmartCessTextField
import com.smartcess.app.ui.components.SmartCessTopBar

/**
 * Talk screen: listening indicator, live transcript, typed fallback, Stop,
 * Cancel, and spoken errors (spec screen inventory). Classified intents
 * navigate to the owning module; nothing is acted on without confirmation
 * downstream.
 */
@Composable
fun VoiceScreen(
    onBack: () -> Unit,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: VoiceViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    var hasMic by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var askedOnce by remember { mutableStateOf(false) }
    var autoStarted by remember { mutableStateOf(false) }
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasMic = granted
        askedOnce = true
    }

    LaunchedEffect(Unit) {
        viewModel.navigate.collect { route -> onNavigate(route) }
    }
    LaunchedEffect(hasMic) {
        if (hasMic && !autoStarted) {
            autoStarted = true
            viewModel.startListening()
        }
    }
    // Errors and static messages are both visible and spoken (spec).
    val errorRes = state.errorRes
    LaunchedEffect(errorRes) {
        errorRes?.let { viewModel.speak(context.getString(it)) }
    }
    val messageRes = state.messageRes
    LaunchedEffect(messageRes) {
        messageRes?.let { viewModel.speak(context.getString(it)) }
    }

    val goBack = { viewModel.cancel(); onBack() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SmartCessTopBar(title = stringResource(R.string.voice_title), onBack = goBack)

        if (!hasMic) {
            MicPermissionCard(
                askedOnce = askedOnce,
                onAsk = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
            )
        }

        Text(
            text = when {
                state.listening -> stringResource(R.string.voice_listening)
                state.processing -> stringResource(R.string.status_loading)
                else -> stringResource(R.string.voice_transcript_label)
            },
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite },
        )
        val liveText = state.partial.ifBlank { state.transcript }
        if (liveText.isNotBlank()) {
            Text(
                text = liveText,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }

        state.message?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            SmartCessSecondaryButton(
                text = stringResource(R.string.action_listen),
                onClick = { viewModel.speak(message) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        messageRes?.let {
            SmartCessBanner(message = stringResource(it), kind = BannerKind.INFO)
        }
        errorRes?.let {
            SmartCessBanner(
                title = stringResource(R.string.banner_error_title),
                message = stringResource(it),
                kind = BannerKind.ERROR,
            )
        }

        SmartCessTextField(
            value = state.typed,
            onValueChange = viewModel::onTypedChange,
            label = stringResource(R.string.voice_type_label),
            hint = stringResource(R.string.voice_type_hint),
        )
        SmartCessPrimaryButton(
            text = stringResource(R.string.voice_send),
            onClick = viewModel::submitTyped,
            modifier = Modifier.fillMaxWidth(),
        )

        if (state.listening) {
            SmartCessSecondaryButton(
                text = stringResource(R.string.voice_stop),
                onClick = viewModel::stopListening,
                modifier = Modifier.fillMaxWidth(),
            )
        } else if (hasMic) {
            SmartCessSecondaryButton(
                text = stringResource(R.string.voice_listen_again),
                onClick = viewModel::startListening,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        SmartCessTextAction(
            text = stringResource(R.string.action_cancel),
            onClick = goBack,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun MicPermissionCard(
    askedOnce: Boolean,
    onAsk: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val permanentlyDenied = askedOnce &&
        (context as? Activity)?.let {
            !ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.RECORD_AUDIO)
        } == true
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.perm_mic_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(R.string.perm_mic_rationale),
            style = MaterialTheme.typography.bodyLarge,
        )
        if (permanentlyDenied) {
            SmartCessSecondaryButton(
                text = stringResource(R.string.perm_open_settings),
                onClick = {
                    val intent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            SmartCessPrimaryButton(
                text = stringResource(R.string.perm_allow),
                onClick = onAsk,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
