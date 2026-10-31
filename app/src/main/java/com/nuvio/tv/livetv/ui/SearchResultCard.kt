package com.nuvio.tv.livetv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.domain.model.CardDepthSurface
import com.nuvio.tv.ui.components.FocusMarqueeText
import com.nuvio.tv.ui.components.LocalCardDepthStyle
import com.nuvio.tv.ui.components.PosterCardDefaults
import com.nuvio.tv.ui.components.PosterCardStyle
import com.nuvio.tv.ui.components.nuvioCardDepth
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * A search results row for Live TV and On Demand, laid out like Nuvio's own catalog rows in
 * search (same heading, spacing and card size), so all of search looks the same.
 */
@Composable
internal fun SearchResultsRow(
    title: String,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = NuvioTheme.spacing.xxxl, end = NuvioTheme.spacing.xxxl, bottom = NuvioTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 1
            )
        }
        LazyRow(
            contentPadding = PaddingValues(start = NuvioTheme.spacing.xxxl, end = 200.dp),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg),
            content = content
        )
    }
}

/**
 * One result, drawn the way Nuvio draws addon results (poster card with its focus ring and
 * zoom, title and lines of text under it), instead of the guide-style box around everything.
 * With no poster, [logo] (a channel logo) sits in the middle of the card, uncropped.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun SearchResultCard(
    title: String,
    lines: List<String>,
    poster: String?,
    logo: String?,
    modifier: Modifier = Modifier,
    posterCardStyle: PosterCardStyle = PosterCardDefaults.Style,
    badge: (@Composable BoxScope.() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = remember(posterCardStyle.cornerRadius) { RoundedCornerShape(posterCardStyle.cornerRadius) }
    val depth = LocalCardDepthStyle.current
    Column(modifier = Modifier.width(posterCardStyle.width)) {
        Card(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused },
            shape = CardDefaults.shape(shape = shape),
            colors = CardDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
            border = CardDefaults.border(
                focusedBorder = Border(border = NuvioTheme.focusRing.border(posterCardStyle.focusedBorderWidth), shape = shape)
            ),
            scale = CardDefaults.scale(focusedScale = posterCardStyle.focusedScale)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(posterCardStyle.height)
                    .clip(shape)
                    .nuvioCardDepth(shape = shape, surface = CardDepthSurface.POSTERS, style = depth)
                    .background(NuvioTheme.colors.BackgroundCard),
                contentAlignment = Alignment.Center
            ) {
                when {
                    !poster.isNullOrBlank() -> AsyncImage(
                        model = poster,
                        contentDescription = title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    !logo.isNullOrBlank() -> AsyncImage(
                        model = logo,
                        contentDescription = title,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(18.dp)
                    )
                    else -> Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = NuvioTheme.colors.TextSecondary,
                        textAlign = TextAlign.Center,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(10.dp)
                    )
                }
                badge?.invoke(this)
            }
        }
        Column(modifier = Modifier.fillMaxWidth().padding(top = NuvioTheme.spacing.sm)) {
            FocusMarqueeText(
                text = title,
                focused = focused,
                style = MaterialTheme.typography.titleMedium,
                color = NuvioTheme.colors.TextPrimary
            )
            lines.forEach { line ->
                Spacer(Modifier.height(2.dp))
                FocusMarqueeText(
                    text = line,
                    focused = focused,
                    style = MaterialTheme.typography.labelMedium,
                    color = NuvioTheme.extendedColors.textSecondary
                )
            }
        }
    }
}
