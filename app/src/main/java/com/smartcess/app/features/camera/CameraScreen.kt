package com.smartcess.app.features.camera

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
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
 * Guided scanner (bill or document mode): live preview + framing overlay +
 * one spoken instruction at a time. Capture (touch or voice) sends only
 * the final compressed image to Muse Spark, shows the extraction for
 * explicit review, and hands the saved id to the result screen on Save.
 */
@Composable
fun CameraScreen(
    onBack: () -> Unit,
    onCaptured: (CaptureResult) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CameraViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    @StringRes val titleRes = if (viewModel.isDocumentMode) R.string.camera_title_doc else R.string.camera_title_bill
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
        viewModel.captured.collect { result -> onCaptured(result) }
    }

    if (!hasPermission) {
        CameraPermissionDenied(
            askedOnce = askedOnce,
            titleRes = titleRes,
            onAskAgain = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            onBack = onBack,
            onAnnounce = { viewModel.announce(R.string.error_permission) },
            modifier = modifier,
        )
        return
    }

    CameraPreviewContent(
        uiState = uiState,
        titleRes = titleRes,
        viewModel = viewModel,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
private fun CameraPreviewContent(
    uiState: CameraUiState,
    @StringRes titleRes: Int,
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
    // Hands-free: voice capture arms itself whenever framing starts (open,
    // discard, rescan) if the mic is granted. A manual toggle-off sticks
    // until the next phase change; review runs its own listener.
    LaunchedEffect(uiState.phase, hasMic) {
        if (hasMic && uiState.phase == CameraPhase.FRAMING && !uiState.voiceCapture) {
            viewModel.setVoiceCaptureEnabled(true)
        }
    }

    DisposableEffect(lifecycleOwner, previewView) {
        viewModel.bindAnalyzer()
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
            viewModel.onSessionEnd()
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
            RowHeader(onBack = onBack, titleRes = titleRes)

            // Temporary guidance-test diagnostics (numbers only, no content).
            val diag = uiState.diag
            if (uiState.demoNoCapture && diag != null && uiState.phase == CameraPhase.FRAMING) {
                DiagPanel(diag = diag, isDocument = uiState.isDocument)
            }

            Spacer(Modifier.weight(1f))

            if (uiState.isOffline) {
                Banner(text = stringResource(R.string.banner_offline))
                Spacer(Modifier.height(8.dp))
            }
            if (uiState.demoNoCapture) {
                Banner(text = stringResource(R.string.camera_demo_no_capture))
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
            val reviewBody = uiState.reviewBody
            if (uiState.phase == CameraPhase.REVIEW && reviewBody != null) {
                ReviewBlock(
                    body = reviewBody,
                    listening = uiState.reviewListening,
                    onSave = viewModel::confirmSave,
                    onDiscard = viewModel::discardDraft,
                )
            }
            OpsButton(
                text = stringResource(
                    if (busy) {
                        if (uiState.isDocument) R.string.camera_processing_doc else R.string.camera_processing
                    } else {
                        R.string.camera_capture
                    },
                ),
                onClick = viewModel::capture,
                enabled = uiState.captureEnabled && !busy &&
                    uiState.phase != CameraPhase.REVIEW && !uiState.demoNoCapture,
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
private fun ReviewBlock(
    body: String,
    listening: Boolean,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite }
            .background(
                MaterialTheme.colorScheme.surfaceContainerHighest,
                RoundedCornerShape(12.dp),
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.camera_review_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        Text(text = body, style = MaterialTheme.typography.bodyLarge)
        if (listening) {
            Text(
                text = stringResource(R.string.camera_review_listening),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        SmartCessPrimaryButton(
            text = stringResource(R.string.camera_review_save),
            onClick = onSave,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        )
        SmartCessSecondaryButton(
            text = stringResource(R.string.camera_review_discard),
            onClick = onDiscard,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun RowHeader(onBack: () -> Unit, @StringRes titleRes: Int) {
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
                text = stringResource(titleRes),
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
    @StringRes titleRes: Int,
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
            text = stringResource(titleRes),
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

/**
 * Temporary guidance-test readout. Numbers and condition names only —
 * never OCR text or document content. Deliberately silent to TalkBack:
 * it refreshes every analyzed frame.
 */
@Composable
private fun DiagPanel(diag: FramingDiag, isDocument: Boolean) {
    val lines = listOf(
        "mode=${if (isDocument) "doc" else "bill"} capture=OFF",
        "frame=${diag.frameWidth}x${diag.frameHeight} rot=${diag.rotation} blocks=${diag.blocks}",
        "cov=${format3(diag.coverage)} off=${formatSigned3(diag.offsetX)}/${formatSigned3(diag.offsetY)}",
        "margin=${format3(diag.margin)} cond=${diag.condition.name}",
        "spoke=${diag.outcome.name} gate=${if (diag.gateAllowed) "open" else "muted"}",
    )
    Text(
        text = lines.joinToString("\n"),
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        color = Color.White,
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(8.dp))
            .padding(8.dp),
    )
}

private fun format3(v: Float): String = "%.3f".format(v)

private fun formatSigned3(v: Float): String = "%+.3f".format(v)
