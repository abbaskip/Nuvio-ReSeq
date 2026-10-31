package com.nuvio.tv.livetv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * One show (or a gap with nothing listed) in a guide row, as [GuideRowStrip] draws it: the same
 * tile, text, tags, icons and progress bar as the highlighted show's [ProgramBlock] has.
 */
internal class StripItem(
    val x: Dp,
    val width: Dp,
    val title: String,
    val subtitle: String?,
    val fill: Color,
    val edge: Color,
    val text: Color,
    val progress: Float? = null,
    val liveTag: Boolean = false,
    val newTag: Boolean = false,
    val episode: String? = null,
    val reminder: Boolean = false,
    val catchup: Boolean = false,
    val recording: Boolean = false,
    /** "No information" in a gap: dim and italic. */
    val italic: Boolean = false
)

/**
 * A whole guide row's shows painted in one go, instead of each show being its own set of boxes,
 * columns and texts that all had to be measured and laid out whenever the guide moved (the
 * heaviest part of scrolling the guide on slower boxes). Text is measured once and kept, and
 * shows off the edge of the row are skipped. Looks the same as before.
 */
@Composable
internal fun GuideRowStrip(
    items: List<StripItem>,
    titleSize: TextUnit,
    smallSize: TextUnit,
    dotSize: Dp,
    iconSize: Dp
) {
    val measurer = rememberTextMeasurer(cacheSize = 256)
    val density = LocalDensity.current
    val base = androidx.compose.material3.LocalTextStyle.current
    val corner = liveCorner()
    val bell = rememberVectorPainter(Icons.Default.Notifications)
    val replay = rememberVectorPainter(Icons.Default.History)
    val secondary = NuvioTheme.colors.Secondary
    val onSecondary = NuvioTheme.colors.OnSecondary
    val styles = remember(base, titleSize, smallSize) {
        StripStyles(
            title = base.copy(fontSize = titleSize, fontWeight = FontWeight.Normal),
            italic = base.copy(fontSize = titleSize, fontStyle = FontStyle.Italic),
            small = base.copy(fontSize = smallSize),
            tag = base.copy(fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
        )
    }
    Canvas(Modifier.fillMaxSize()) {
        val outer = 3.dp.toPx()
        val pad = 10.dp.toPx()
        val gap = 6.dp.toPx()
        val stroke = 2.dp.toPx()
        val r = corner.toPx()
        val h = size.height
        for (item in items) {
            val left = item.x.toPx() + outer
            val w = item.width.toPx() - outer * 2
            if (w <= 0f || left > size.width || left + w < 0f) continue
            drawTile(item, left, w, h, r, stroke)
            val inner = (w - pad * 2).toInt()
            if (inner > 8) drawContent(
                item, left + pad, inner, h, measurer, styles, gap,
                dotSize.toPx(), iconSize.toPx(), bell, replay, secondary, onSecondary
            )
            item.progress?.let { f -> drawProgress(left, w, h, r, stroke, f, secondary) }
        }
    }
}

private class StripStyles(val title: TextStyle, val italic: TextStyle, val small: TextStyle, val tag: TextStyle)

private fun DrawScope.drawTile(item: StripItem, left: Float, w: Float, h: Float, r: Float, stroke: Float) {
    drawRoundRect(item.fill, topLeft = Offset(left, 0f), size = Size(w, h), cornerRadius = CornerRadius(r, r))
    // The glass edge, inside the tile (as the tiles' own outline was).
    val ir = r
    drawRoundRect(
        item.edge,
        topLeft = Offset(left + stroke / 2f, stroke / 2f),
        size = Size((w - stroke).coerceAtLeast(1f), (h - stroke).coerceAtLeast(1f)),
        cornerRadius = CornerRadius(ir, ir),
        style = Stroke(stroke)
    )
}

private fun DrawScope.drawProgress(left: Float, w: Float, h: Float, r: Float, stroke: Float, f: Float, color: Color) {
    val barH = 3.dp.toPx()
    val inset = stroke
    val tile = Path().apply { addRoundRect(RoundRect(left, 0f, left + w, h, CornerRadius(r, r))) }
    clipPath(tile) {
        drawRect(
            color.copy(alpha = 0.9f),
            topLeft = Offset(left + inset, h - inset - barH),
            size = Size(((w - inset * 2) * f.coerceIn(0f, 1f)).coerceAtLeast(0f), barH)
        )
    }
}

private fun DrawScope.drawContent(
    item: StripItem,
    startX: Float,
    innerW: Int,
    h: Float,
    measurer: androidx.compose.ui.text.TextMeasurer,
    styles: StripStyles,
    gap: Float,
    dot: Float,
    icon: Float,
    bell: androidx.compose.ui.graphics.vector.VectorPainter,
    replay: androidx.compose.ui.graphics.vector.VectorPainter,
    secondary: Color,
    onSecondary: Color
) {
    val widthDp = item.width // the show's width, as ProgramBlock checks it
    fun measure(text: String, style: TextStyle, maxW: Int): TextLayoutResult =
        measurer.measure(
            text, style, overflow = TextOverflow.Ellipsis, softWrap = false, maxLines = 1,
            constraints = Constraints(maxWidth = maxW.coerceAtLeast(1))
        )

    // What comes before and after the title, only where there's room (the same rules as ProgramBlock).
    val showDot = item.recording && widthDp > 40.dp
    val live = if (item.liveTag && widthDp > 90.dp) measure("LIVE", styles.tag.copy(color = Color.White), innerW) else null
    val wide = widthDp > 150.dp
    val episode = if (wide) item.episode?.let { e -> measure(e, styles.small.copy(color = item.text.copy(alpha = 0.6f)), innerW) } else null
    val new = if (wide && item.newTag) measure("NEW", styles.tag.copy(color = onSecondary), innerW) else null
    val showBell = item.reminder && widthDp > 60.dp
    val showReplay = item.catchup && widthDp > 60.dp
    val tagPadX = 6.dp.toPx()
    val tagPadY = 1.dp.toPx()
    fun tagW(t: TextLayoutResult) = t.size.width + tagPadX * 2

    var fixed = 0f
    if (showDot) fixed += dot + gap
    live?.let { fixed += tagW(it) + gap }
    episode?.let { fixed += gap + it.size.width }
    new?.let { fixed += gap + tagW(it) }
    if (showBell) fixed += gap + icon
    if (showReplay) fixed += gap + icon
    val titleStyle = (if (item.italic) styles.italic else styles.title).copy(color = item.text)
    val title = measure(item.title, titleStyle, (innerW - fixed).toInt())
    val sub = item.subtitle?.let { s -> measure(s, styles.small.copy(color = item.text.copy(alpha = 0.42f)), innerW) }

    val lineH = maxOf(title.size.height.toFloat(), live?.size?.height?.plus(tagPadY * 2) ?: 0f, icon)
    val totalH = lineH + (sub?.size?.height ?: 0)
    val top = (h - totalH) / 2f
    val mid = top + lineH / 2f
    var x = startX

    if (showDot) {
        drawCircle(LIVE_RED, radius = dot / 2f, center = Offset(x + dot / 2f, mid))
        x += dot + gap
    }
    live?.let { t ->
        drawTag(t, x, mid, tagPadX, tagPadY, LIVE_RED)
        x += tagW(t) + gap
    }
    drawText(title, topLeft = Offset(x, mid - title.size.height / 2f))
    x += title.size.width
    episode?.let { t ->
        x += gap
        drawText(t, topLeft = Offset(x, mid - t.size.height / 2f))
        x += t.size.width
    }
    new?.let { t ->
        x += gap
        drawTag(t, x, mid, tagPadX, tagPadY, secondary)
        x += tagW(t)
    }
    if (showBell) {
        x += gap
        translate(x, mid - icon / 2f) { with(bell) { draw(Size(icon, icon), colorFilter = ColorFilter.tint(item.text)) } }
        x += icon
    }
    if (showReplay) {
        x += gap
        translate(x, mid - icon / 2f) { with(replay) { draw(Size(icon, icon), colorFilter = ColorFilter.tint(item.text.copy(alpha = 0.8f))) } }
        x += icon
    }
    sub?.let { t -> drawText(t, topLeft = Offset(startX, top + lineH)) }
}

private fun DrawScope.drawTag(t: TextLayoutResult, x: Float, mid: Float, padX: Float, padY: Float, color: Color) {
    val w = t.size.width + padX * 2
    val hh = t.size.height + padY * 2
    val r = 5.dp.toPx()
    drawRoundRect(color, topLeft = Offset(x, mid - hh / 2f), size = Size(w, hh), cornerRadius = CornerRadius(r, r))
    drawText(t, topLeft = Offset(x + padX, mid - t.size.height / 2f))
}
