// Adaptive title and artwork placement follows MetaDetailsScreen in NuvioPlusIPTV-Mobile (GPLv3).
package com.nuvio.tv.mobile

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nuvio.tv.domain.model.Meta

@Composable
internal fun MobileDetailHero(meta: Meta?, title: String, wide: Boolean, back: () -> Unit, actions: () -> Unit) {
    val artwork = if (wide) meta?.poster ?: meta?.backdropUrl else meta?.backdropUrl ?: meta?.poster
    val menu = Modifier.combinedClickable(onClick = actions, onLongClickLabel = "Title actions", onLongClick = actions)
    Action("Back", onClick = back)
    if (!artwork.isNullOrBlank()) {
        AsyncImage(artwork, null, Modifier.fillMaxWidth().heightIn(max = if (wide) 420.dp else 300.dp)
            .aspectRatio(if (wide) 4f / 5f else 1.9f).then(menu), contentScale = ContentScale.Crop)
    }
    Box(Modifier.fillMaxWidth().then(menu)) { SectionTitle(title) }
    meta?.description?.let { Label(it, small = true, lines = if (wide) 12 else 5) }
}
