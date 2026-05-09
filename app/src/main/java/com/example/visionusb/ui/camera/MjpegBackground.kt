package com.example.visionusb.ui.camera

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun MjpegBackground(
    url: String,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            MjpegStreamView(context).apply {
                startStream(url)
            }
        },
        update = { view ->
            view.startStream(url)
        }
    )
}
