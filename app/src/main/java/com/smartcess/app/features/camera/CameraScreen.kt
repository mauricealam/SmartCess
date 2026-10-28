package com.smartcess.app.features.camera

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.smartcess.app.R
import com.smartcess.app.ui.components.OpsButton
import com.smartcess.app.ui.components.OpsNotice
import com.smartcess.app.ui.components.SmartCessPrimaryButton
import com.smartcess.app.ui.components.SmartCessSecondaryButton
import com.smartcess.app.ui.theme.SmartCessTheme

/**
 * Guided bill scanner: live preview + framing overlay + one spoken
 * instruction at a time. Capture (touch or voice) sends only the final
 * compressed image to Muse Spark, then hands the saved bill id to review.
 */
@Composable
fun CameraScreen(
    onBack: () -> Unit,
    onCaptured: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CameraViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var askedOnce by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        askedOnce = true
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }
    LaunchedEffect(Unit) {
        viewModel.captured.collect { billId -> onCaptured(billId) }
    }

    if (!hasPermission) {
        CameraPermissionDenied(
            askedOnce = askedOnce,
            onAskAgain = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            onBack = onBack,
            onAnnounce = { viewModel.announce(R.string.error_permission) },
            modifier = modifier,
        )
        return
    }

    CameraPreviewContent(
        uiState = uiState,
        viewModel = viewModel,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
private fun CameraPreviewContent(
    uiState: CameraUiState,
    viewModel: CameraViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    var hasMic by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasMic = granted
        if (granted && uiState.phase == CameraPhase.FRAMING) {
            viewModel.setVoiceCaptureEnabled(true)
        }
    }

    DisposableEffect(lifecycleOwner, previewView) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val listener = Runnable {
            val provider = providerFuture.get()
            viewModel.preview.setSurfaceProvider(previewView.surfaceProvider)
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    viewModel.preview,
                    viewModel.imageAnalysis,
                    viewModel.imageCapture,
                )
            }
        }
        providerFuture.addListener(listener, ContextCompat.getMainExecutor(context))
        onDispose {
            runCatching { providerFuture.get().unbindAll() }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        FramingOverlay(condition = uiState.condition)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(16.dp),
        ) {
            RowHeader(onBack = onBack)

            Spacer(Modifier.weight(1f))

            if (uiState.isOffline) {
                Banner(text = stringResource(R.string.banner_offline))
                Spacer(Modifier.height(8.dp))
            }
            val errorRes = uiState.errorRes
            if (uiState.phase == CameraPhase.ERROR && errorRes != null) {
                ErrorBanner(
                    text = stringResource(errorRes),
                    onRescan = viewModel::rescan,
                )
                Spacer(Modifier.height(8.dp))
            }

            // Spoken instruction, mirrored as a live region for TalkBack and
            // rendered as an ops notice strip: the journey layer floats over
            // the camera content without restyling it.
            OpsNotice(
                text = stringResource(uiState.instructionRes),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Spacer(Modifier.height(12.dp))

            val busy = uiState.phase == CameraPhase.CAPTURING ||
                uiState.phase == CameraPhase.PROCESSING
            OpsButton(
                text = stringResource(
                    if (busy) R.string.camera_processing else R.string.camera_capture,
                ),
                onClick = viewModel::capture,
                enabled = uiState.captureEnabled && !busy,
                modifier = Modifier.fillMaxWidth(),
                leadingContent = if (busy) {
                    {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 3.dp,
                            color = SmartCessTheme.extra.onOps,
                        )
                    }
                } else {
                    null
                },
            )
            Spacer(Modifier.height(8.dp))
            SmartCessSecondaryButton(
                text = stringResource(
                    if (uiState.voiceCapture) R.string.camera_voice_capture_on
                    else R.string.camera_voice_capture_off,
                ),
                onClick = {
                    if (hasMic) {
                        viewModel.setVoiceCaptureEnabled(!uiState.voiceCapture)
                    } else {
                        micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                enabled = uiState.phase == CameraPhase.FRAMING,
                modifier = Modifier.fillMaxWidth(),
            )
            if (uiState.voiceCapture) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.camera_voice_listening),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    modifier = Modifier
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .background(Color(0xFF0B0F0E), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            } else {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.camera_voice_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    modifier = Modifier
                        .background(Color(0xFF0B0F0E), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/**
 * Header on a solid console strip: white stays verifiable (~19:1) instead of
 * floating on raw video, and the back action gets the tonal circle.
 */
@Composable
private fun RowHeader(onBack: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF0B0F0E),
        contentColor = Color.White,
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHighest,
                        CircleShape,
                    ),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = stringResource(R.string.camera_title),
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.semantics { heading() },
            )
        }
    }
}

@Composable
private fun Banner(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite }
            .background(
                MaterialTheme.colorScheme.errorContainer,
                RoundedCornerShape(12.dp),
            )
            .padding(16.dp),
    )
}

@Composable
private fun ErrorBanner(text: String, onRescan: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite }
            .background(
                MaterialTheme.colorScheme.errorContainer,
                RoundedCornerShape(12.dp),
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.bodyLarge)
        SmartCessPrimaryButton(
            text = stringResource(R.string.action_retry),
            onClick = onRescan,
            modifier = Modifier.heightIn(min = 48.dp),
        )
    }
}

@Composable
private fun CameraPermissionDenied(
    askedOnce: Boolean,
    onAskAgain: () -> Unit,
    onBack: () -> Unit,
    onAnnounce: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    LaunchedEffect(askedOnce) {
        if (askedOnce) onAnnounce()
    }
    val permanentlyDenied = askedOnce &&
        (context as? Activity)?.let {
            !ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.CAMERA)
        } == true

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.camera_title),
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(R.string.camera_permission_desc),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (permanentlyDenied) {
            SmartCessPrimaryButton(
                text = stringResource(R.string.camera_open_settings),
                onClick = {
                    val intent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        } else {
            SmartCessPrimaryButton(
                text = stringResource(R.string.camera_grant_permission),
                onClick = onAskAgain,
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
        SmartCessSecondaryButton(
            text = stringResource(R.string.action_back),
            onClick = onBack,
            modifier = Modifier.heightIn(min = 48.dp),
        )
    }
}
