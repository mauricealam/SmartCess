package com.smartcess.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.smartcess.app.R

/**
 * SmartCess brand mark: the script S from the approved wordmark
 * (res/drawable[-night]/ic_brand_s.xml, auto light/dark).
 * Launcher icon is the adaptive res/mipmap-anydpi-v26/ic_launcher.xml.
 */
@Composable
fun SmartCessLogo(
    modifier: Modifier = Modifier,
) {
    Image(
        painter = painterResource(R.drawable.ic_brand_s),
        contentDescription = stringResource(R.string.logo_content_desc),
        modifier = modifier,
    )
}
