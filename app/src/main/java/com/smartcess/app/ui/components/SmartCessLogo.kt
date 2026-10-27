package com.smartcess.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.smartcess.app.R

/**
 * SmartCess brand logo (res/drawable/ic_smartcess_logo.png).
 * Launcher icon lives in the res/mipmap density folders as ic_launcher.png
 * and is wired in the manifest.
 */
@Composable
fun SmartCessLogo(
    modifier: Modifier = Modifier,
) {
    Image(
        painter = painterResource(R.drawable.ic_smartcess_logo),
        contentDescription = stringResource(R.string.logo_content_desc),
        modifier = modifier,
    )
}
