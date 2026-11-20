package com.nuvio.tv.livetv.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.nuvio.tv.R
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun CalendarDetailPreview(title: String, artwork: String?) {
    val context = LocalContext.current
    Box(Modifier.fillMaxSize().background(NuvioTheme.colors.BackgroundElevated)) {
        if (!artwork.isNullOrBlank()) {
            val image = remember(context, artwork) {
                ImageRequest.Builder(context).data(artwork).networkCachePolicy(CachePolicy.DISABLED).build()
            }
            AsyncImage(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = .88f), Color.Transparent))))
        Column(Modifier.align(Alignment.CenterStart).fillMaxWidth(.75f).padding(start = 48.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LiveText(title, size = 32.sp, color = Color.White, weight = FontWeight.Bold, maxLines = 2)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LoadingIndicator(Modifier.size(26.dp))
                LiveText(stringResource(R.string.calendar_loading_details), size = 16.sp, color = Color.White)
            }
        }
    }
}
