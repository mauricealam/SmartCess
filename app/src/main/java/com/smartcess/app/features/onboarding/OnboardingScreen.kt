package com.smartcess.app.features.onboarding

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.smartcess.app.R
import com.smartcess.app.ui.components.OpsButton
import com.smartcess.app.ui.components.SmartCessBanner
import com.smartcess.app.ui.components.SmartCessSecondaryButton
import com.smartcess.app.ui.components.SmartCessTextAction
import com.smartcess.app.ui.theme.SmartCessTheme
import kotlinx.coroutines.launch

private const val ONBOARDING_PREFS = "smartcess_onboarding"
private const val KEY_DONE = "done"

fun isOnboardingDone(context: Context): Boolean =
    context.getSharedPreferences(ONBOARDING_PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DONE, false)

private fun setOnboardingDone(context: Context) {
    context.getSharedPreferences(ONBOARDING_PREFS, Context.MODE_PRIVATE)
        .edit().putBoolean(KEY_DONE, true).apply()
}

fun isTalkBackOn(context: Context): Boolean {
    val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        ?: return false
    return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN).isNotEmpty()
}

private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/**
 * First-run onboarding (spec): welcome, TalkBack check, staged permissions,
 * voice setup, first scan, privacy. Every step skippable; nothing blocks
 * exploring the app. TalkBack headings + live regions throughout.
 */
@Composable
fun OnboardingScreen(
    onDone: () -> Unit,
    onScanBill: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var page by remember { mutableIntStateOf(0) }
    val titles = listOf(
        stringResource(R.string.onboarding_title_1),
        stringResource(R.string.onboarding_talkback_title),
        stringResource(R.string.onboarding_perm_title),
        stringResource(R.string.onboarding_voice_title),
        stringResource(R.string.onboarding_scan_title),
        stringResource(R.string.onboarding_title_3),
    )
    val finish: () -> Unit = {
        scope.launch { setOnboardingDone(context) }
        onDone()
    }
    val finishAndScan: () -> Unit = {
        scope.launch { setOnboardingDone(context) }
        onScanBill()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.onboarding_page_desc, page + 1, titles.size),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (page < titles.lastIndex) {
                SmartCessTextAction(
                    text = stringResource(R.string.action_skip),
                    onClick = finish,
                )
            }
        }
        LinearProgressIndicator(
            progress = { (page + 1) / titles.size.toFloat() },
            color = SmartCessTheme.extra.ops,
            modifier = Modifier.fillMaxWidth().semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = (page + 1).toFloat(),
                    range = 0f..titles.size.toFloat(),
                )
            },
        )
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = titles[page],
                style = MaterialTheme.typography.displayLarge,
                modifier = Modifier.semantics { heading() },
            )
            when (page) {
                0 -> Text(
                    text = stringResource(R.string.onboarding_body_1),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                1 -> TalkBackStep()
                2 -> PermissionsStep()
                3 -> VoiceStep(
                    onPlaySample = {
                        viewModel.playSample(context.getString(R.string.onboarding_voice_sample_text))
                    },
                    onOpenSettings = onOpenSettings,
                )
                4 -> ScanStep(onScan = finishAndScan)
                else -> PrivacyStep()
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (page > 0) {
                SmartCessSecondaryButton(
                    text = stringResource(R.string.action_back),
                    onClick = { page-- },
                    modifier = Modifier.weight(1f),
                )
            }
            if (page < titles.lastIndex) {
                OpsButton(
                    text = stringResource(R.string.action_next),
                    onClick = { page++ },
                    modifier = Modifier.weight(1f),
                )
            } else {
                OpsButton(
                    text = stringResource(R.string.action_get_started),
                    onClick = finish,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun TalkBackStep(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val talkBackOn = remember { isTalkBackOn(context) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(
                if (talkBackOn) R.string.onboarding_talkback_on else R.string.onboarding_talkback_off,
            ),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (!talkBackOn) {
            SmartCessSecondaryButton(
                text = stringResource(R.string.onboarding_talkback_open),
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PermissionsStep(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var micGranted by remember { mutableStateOf(hasPermission(context, Manifest.permission.RECORD_AUDIO)) }
    var camGranted by remember { mutableStateOf(hasPermission(context, Manifest.permission.CAMERA)) }
    var notifGranted by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= 33) {
                hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            } else {
                NotificationManagerCompat.from(context).areNotificationsEnabled()
            },
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        micGranted = hasPermission(context, Manifest.permission.RECORD_AUDIO)
        camGranted = hasPermission(context, Manifest.permission.CAMERA)
        notifGranted = if (Build.VERSION.SDK_INT >= 33) {
            hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PermissionRow(
            label = stringResource(R.string.onboarding_perm_mic),
            allowLabel = stringResource(R.string.perm_allow_mic),
            granted = micGranted,
            onAllow = { launcher.launch(Manifest.permission.RECORD_AUDIO) },
        )
        PermissionRow(
            label = stringResource(R.string.onboarding_perm_camera),
            allowLabel = stringResource(R.string.perm_allow_camera),
            granted = camGranted,
            onAllow = { launcher.launch(Manifest.permission.CAMERA) },
        )
        if (Build.VERSION.SDK_INT >= 33) {
            PermissionRow(
                label = stringResource(R.string.onboarding_perm_notif),
                allowLabel = stringResource(R.string.perm_allow_notif),
                granted = notifGranted,
                onAllow = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) },
            )
        }
        Text(
            text = stringResource(R.string.onboarding_perm_skip_note),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun PermissionRow(
    label: String,
    allowLabel: String,
    granted: Boolean,
    onAllow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(
                    if (granted) R.string.onboarding_perm_granted else R.string.onboarding_perm_not_yet,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (!granted) {
            SmartCessSecondaryButton(
                text = allowLabel,
                onClick = onAllow,
            )
        }
    }
}

@Composable
private fun VoiceStep(
    onPlaySample: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.onboarding_voice_body),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        SmartCessSecondaryButton(
            text = stringResource(R.string.onboarding_voice_sample),
            onClick = onPlaySample,
            modifier = Modifier.fillMaxWidth(),
        )
        SmartCessSecondaryButton(
            text = stringResource(R.string.onboarding_voice_open_settings),
            onClick = onOpenSettings,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ScanStep(
    onScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.onboarding_scan_body),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        SmartCessSecondaryButton(
            text = stringResource(R.string.onboarding_scan_button),
            onClick = onScan,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PrivacyStep(modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.onboarding_privacy_body),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        SmartCessBanner(message = stringResource(R.string.onboarding_privacy_note))
    }
}
