package com.smartcess.app.features.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.smartcess.app.BuildConfig
import com.smartcess.app.R
import com.smartcess.app.ui.components.SmartCessBanner
import com.smartcess.app.ui.components.SmartCessCard
import com.smartcess.app.ui.components.SmartCessConfirmDialog
import com.smartcess.app.ui.components.SmartCessLogo
import com.smartcess.app.ui.components.SmartCessPrimaryButton
import com.smartcess.app.ui.components.SmartCessSecondaryButton
import com.smartcess.app.ui.components.SmartCessTextField
import com.smartcess.app.ui.components.SmartCessTopBar
import com.smartcess.app.ui.components.BannerKind

/**
 * Settings + About: API key, speech speed, reminder defaults, appearance,
 * privacy controls, data export/delete, and logo branding.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val prefs by viewModel.preferences.collectAsState()
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var feedbackText by remember { mutableStateOf("") }
    val jsonExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let { viewModel.writeExportFile(it, csv = false) } }
    val csvExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri -> uri?.let { viewModel.writeExportFile(it, csv = true) } }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SmartCessTopBar(title = stringResource(R.string.settings_title), onBack = onBack)
        SmartCessLogo(
            modifier = Modifier.size(72.dp).align(Alignment.CenterHorizontally),
        )
        state.messageRes?.let {
            SmartCessBanner(message = stringResource(it), kind = BannerKind.INFO)
        }

        SettingsSection(title = stringResource(R.string.settings_section_speech)) {
            SmartCessTextField(
                value = state.apiKeyInput,
                onValueChange = viewModel::onApiKeyInput,
                label = stringResource(R.string.settings_api_key_label),
                hint = stringResource(R.string.settings_api_key_hint),
            )
            SmartCessPrimaryButton(
                text = stringResource(R.string.action_save),
                onClick = viewModel::saveApiKey,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.hasApiKey) {
                SmartCessSecondaryButton(
                    text = stringResource(R.string.action_clear),
                    onClick = viewModel::clearApiKey,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                text = stringResource(
                    if (state.hasApiKey) R.string.settings_meta_on else R.string.settings_meta_off,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.settings_tts_rate_value, prefs.ttsRate),
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = prefs.ttsRate,
                onValueChange = viewModel::setTtsRate,
                valueRange = 0.5f..2.0f,
                steps = 5,
            )
        }

        SettingsSection(title = stringResource(R.string.settings_section_reminders)) {
            Text(
                text = stringResource(R.string.settings_bill_reminder_days_value, prefs.defaultBillReminderDays),
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = prefs.defaultBillReminderDays.toFloat(),
                onValueChange = { viewModel.setBillReminderDays(it.toInt()) },
                valueRange = 0f..14f,
                steps = 13,
            )
            val reminderHour = prefs.defaultReminderTime / 60
            val reminderMinute = prefs.defaultReminderTime % 60
            Text(
                text = stringResource(R.string.settings_reminder_time) + ": " +
                    reminderHour.toString().padStart(2, '0') + ":" +
                    reminderMinute.toString().padStart(2, '0'),
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = reminderHour.toFloat(),
                onValueChange = { viewModel.setDefaultReminderTime(it.toInt() * 60 + reminderMinute) },
                valueRange = 0f..23f,
                steps = 22,
            )
            Slider(
                value = reminderMinute.toFloat(),
                onValueChange = { viewModel.setDefaultReminderTime(reminderHour * 60 + it.toInt()) },
                valueRange = 0f..59f,
                steps = 58,
            )
        }

        SettingsSection(title = stringResource(R.string.settings_section_appearance)) {
            SettingSwitch(
                title = stringResource(R.string.settings_high_contrast),
                checked = prefs.highContrast,
                onChecked = viewModel::setHighContrast,
            )
            SettingSwitch(
                title = stringResource(R.string.settings_large_text),
                checked = prefs.largeText,
                onChecked = viewModel::setLargeText,
            )
            SettingSwitch(
                title = stringResource(R.string.settings_haptics),
                subtitle = stringResource(R.string.settings_haptics_desc),
                checked = prefs.hapticFeedbackEnabled,
                onChecked = viewModel::setHapticsEnabled,
            )
        }

        SettingsSection(title = stringResource(R.string.settings_section_privacy)) {
            Text(
                text = stringResource(R.string.settings_privacy_body),
                style = MaterialTheme.typography.bodyLarge,
            )
            SettingSwitch(
                title = stringResource(R.string.settings_analytics),
                subtitle = stringResource(R.string.settings_analytics_desc),
                checked = prefs.analyticsOptIn,
                onChecked = viewModel::setAnalyticsOptIn,
            )
            SettingSwitch(
                title = stringResource(R.string.settings_crash),
                subtitle = stringResource(R.string.settings_crash_desc),
                checked = prefs.crashOptIn,
                onChecked = viewModel::setCrashOptIn,
            )
        }

        SettingsSection(title = stringResource(R.string.settings_section_data)) {
            SmartCessSecondaryButton(
                text = stringResource(R.string.settings_export),
                onClick = viewModel::exportData,
                modifier = Modifier.fillMaxWidth(),
            )
            SmartCessSecondaryButton(
                text = stringResource(R.string.settings_export_json),
                onClick = { jsonExportLauncher.launch("smartcess-export.json") },
                modifier = Modifier.fillMaxWidth(),
            )
            SmartCessSecondaryButton(
                text = stringResource(R.string.settings_export_csv),
                onClick = { csvExportLauncher.launch("smartcess-export.csv") },
                modifier = Modifier.fillMaxWidth(),
            )
            SmartCessSecondaryButton(
                text = stringResource(R.string.settings_delete_button),
                onClick = { showDeleteConfirm = true },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        SettingsSection(title = stringResource(R.string.settings_feedback_label)) {
            SmartCessTextField(
                value = feedbackText,
                onValueChange = { feedbackText = it },
                label = stringResource(R.string.settings_feedback_label),
                hint = stringResource(R.string.settings_feedback_hint),
                singleLine = false,
                minLines = 3,
            )
            SmartCessSecondaryButton(
                text = stringResource(R.string.action_save),
                onClick = { viewModel.saveFeedback(feedbackText); feedbackText = "" },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        SettingsSection(title = stringResource(R.string.settings_about_title)) {
            Text(
                text = stringResource(R.string.settings_about_body),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }

    state.exportPayload?.let { payload ->
        val shareLabel = stringResource(R.string.settings_export)
        LaunchedEffect(payload) {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_TEXT, payload)
            }
            context.startActivity(Intent.createChooser(intent, shareLabel))
            viewModel.clearExport()
        }
    }

    if (showDeleteConfirm) {
        SmartCessConfirmDialog(
            title = stringResource(R.string.settings_delete_title),
            message = stringResource(R.string.settings_delete_message),
            confirmText = stringResource(R.string.action_delete),
            onConfirm = { showDeleteConfirm = false; viewModel.confirmDeleteAll() },
            onDismiss = { showDeleteConfirm = false },
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        SmartCessCard { content() }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            subtitle?.let { Text(text = it, style = MaterialTheme.typography.bodyMedium) }
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
