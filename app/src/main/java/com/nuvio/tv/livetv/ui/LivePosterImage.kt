package com.nuvio.tv.livetv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale

/**
 * A show's picture in a portrait poster box. Posters fill the box; a wide picture (a guide's
 * own show image, usually landscape) is shown whole, with black above and below, instead of
 * being cut down to a sliver of its middle.
 */
@Composable
fun LivePosterImage(url: String, modifier: Modifier = Modifier) {
    var wide by remember(url) { mutableStateOf(false) }
    Box(
        modifier = modifier.then(if (wide) Modifier.background(Color.Black) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        coil3.compose.AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = if (wide) ContentScale.Fit else ContentScale.Crop,
            onSuccess = { s ->
                val size = s.painter.intrinsicSize
                wide = size.width > 0f && size.height > 0f && size.width > size.height * 1.1f
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}
