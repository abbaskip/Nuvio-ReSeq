// Poster shelves and touch actions adapt NuvioPlusIPTV-Mobile. See NOTICE-MOBILE.md.
package com.nuvio.tv.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.nuvio.tv.domain.model.MetaPreview

private val yearToken = Regex("""\b\d{4}\b""")
private val yearRange = Regex("""(\d{4})\s*[-–—]\s*(\d{4})?""")
internal val posterCorners = RoundedCornerShape(7.dp)

internal fun MetaPreview.posterYears(): String? {
    val range = releaseInfo?.trim()?.let(yearRange::matchEntire)
    if (range != null) return range.groupValues[1] + "–" + range.groupValues[2]
    return releaseInfo?.let { yearToken.find(it)?.value } ?: released?.let { yearToken.find(it)?.value }
}

@Composable
internal fun Poster(item: MetaPreview, modifier: Modifier, open: () -> Unit, more: () -> Unit, showCaption: Boolean = true) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.combinedClickable(onClick = open, onLongClickLabel = "Title actions", onLongClick = more)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(posterCorners).background(colors.surfaceVariant),
            contentAlignment = Alignment.Center) {
            if (item.apiType == com.nuvio.tv.domain.model.TvMixHomeCatalog.TYPE) {
                com.nuvio.tv.ui.screens.tvmix.TvMixArtwork(item.poster.orEmpty(), item.name, Modifier.fillMaxSize())
            } else if (item.poster.isNullOrBlank()) {
                Icon(Icons.Default.Theaters, if (showCaption) null else item.name, tint = colors.onSurfaceVariant)
            } else {
                AsyncImage(item.poster, if (showCaption) null else item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
        if (showCaption) {
            Text(item.name, Modifier.padding(top = 5.dp), color = colors.onSurface, fontFamily = condensed,
                fontSize = 14.sp, lineHeight = 17.sp, letterSpacing = 0.sp, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            val years = remember(item.releaseInfo, item.released) { item.posterYears() }
            if (years != null) Text(years, color = colors.onSurfaceVariant, fontFamily = condensed,
                fontSize = 12.sp, lineHeight = 15.sp, letterSpacing = 0.sp)
        }
    }
}

@Composable
internal fun ShelfHeading(title: String, gutter: Dp, source: String? = null, seeAll: (() -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    val normal = TextStyle(fontFamily = condensed, fontSize = 15.sp, lineHeight = 18.sp,
        fontWeight = FontWeight.Medium, letterSpacing = 0.sp)
    val compact = normal.copy(fontSize = 14.sp, lineHeight = 17.sp)
    val measure = rememberTextMeasurer()
    val action = if (seeAll == null) Modifier else Modifier.clickable(role = Role.Button, onClickLabel = "See all $title", onClick = seeAll)
    val endGutter = if (seeAll == null) gutter else (gutter - 6.dp).coerceAtLeast(8.dp)
    Row(Modifier.fillMaxWidth().then(action).heightIn(min = 48.dp)
        .padding(start = gutter, end = endGutter, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        BoxWithConstraints(Modifier.weight(1f)) {
            fun fits(style: TextStyle) = measure.measure(title, style, softWrap = false, maxLines = 1).size.width <= constraints.maxWidth
            val style = if (fits(normal) || !fits(compact)) normal else compact
            Column {
                Text(title, color = colors.onSurface, style = style, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!source.isNullOrBlank()) Text(source, color = colors.onSurfaceVariant, fontFamily = condensed,
                    fontSize = 12.sp, lineHeight = 15.sp, letterSpacing = 0.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (seeAll != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(17.dp), tint = colors.onSurfaceVariant)
    }
}

@Composable
internal fun HomeHighlights(titles: List<MetaPreview>, gutter: Dp, open: (MetaPreview) -> Unit, more: (MetaPreview) -> Unit) {
    val pager = rememberPagerState { titles.size }
    val colors = MaterialTheme.colorScheme
    HorizontalPager(pager, modifier = Modifier.fillMaxWidth()) { index ->
        val title = titles[index]
        BoxWithConstraints(Modifier.fillMaxWidth().combinedClickable(onClick = { open(title) },
            onLongClickLabel = "Title actions", onLongClick = { more(title) })) {
            val height = (maxWidth * .68f).coerceIn(180.dp, 325.dp)
            Box(Modifier.fillMaxWidth().height(height)) {
                MediaArt(title.backdropUrl, Icons.Default.Theaters, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to colors.background.copy(alpha = .48f),
                    .18f to colors.background.copy(alpha = 0f), .43f to colors.background.copy(alpha = .06f),
                    .76f to colors.background.copy(alpha = .71f), 1f to colors.background)))
                Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = gutter, end = gutter, bottom = 12.dp),
                    verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        TitleBrand(title.name, title.logo)
                        val facts = listOfNotNull(title.posterYears(), title.genres.firstOrNull()).joinToString("  ·  ")
                        if (facts.isNotBlank()) Label(facts, small = true)
                    }
                    if (titles.size > 1) Row(Modifier.padding(bottom = 5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(titles.size) { dot ->
                            Spacer(Modifier.size(if (dot == index) 15.dp else 4.dp, 4.dp)
                                .background(if (dot == index) colors.primary else colors.onSurfaceVariant.copy(alpha = .5f), CircleShape))
                        }
                    }
                }
            }
        }
    }
}
