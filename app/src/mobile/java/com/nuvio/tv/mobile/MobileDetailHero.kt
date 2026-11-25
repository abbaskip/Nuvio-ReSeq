// Adaptive title and artwork placement follows MetaDetailsScreen in NuvioPlusIPTV-Mobile (GPLv3).
package com.nuvio.tv.mobile

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nuvio.tv.domain.model.Meta

@Composable
internal fun MobileDetailHero(meta: Meta?, title: String, wide: Boolean, back: () -> Unit,
    preview: @Composable () -> Unit = {}, actions: () -> Unit) {
    val c = MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxWidth().combinedClickable(onClick = actions, onLongClickLabel = "Title actions", onLongClick = actions)) {
        val tall = if (wide) (maxWidth * 1.12f).coerceIn(220.dp, 440.dp) else (maxWidth * .82f).coerceIn(180.dp, 385.dp)
        Box(Modifier.fillMaxWidth().height(tall)) {
            MediaArt(if (wide) meta?.poster ?: meta?.backdropUrl else meta?.backdropUrl, Icons.Default.Theaters, Modifier.fillMaxSize())
            preview()
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = .30f), .32f to Color.Transparent,
                .64f to c.background.copy(alpha = .24f), 1f to c.background)))
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                PlayerIcon("Back", Icons.AutoMirrored.Filled.ArrowBack, onClick = back)
                PlayerIcon("Title actions", Icons.Default.MoreHoriz, onClick = actions)
            }
            Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                TitleBrand(title, meta?.logo)
                meta?.let {
                    val facts = listOfNotNull(it.releaseInfo, it.runtime, it.ageRating, it.genres.firstOrNull()).filter(String::isNotBlank).joinToString("  ·  ")
                    if (facts.isNotEmpty()) Label(facts, small = true)
                }
            }
        }
    }
}
