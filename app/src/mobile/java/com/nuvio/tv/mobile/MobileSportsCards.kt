package com.nuvio.tv.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.tv.livetv.sports.*
import com.nuvio.tv.livetv.ui.formatClock

private val cardShape = RoundedCornerShape(4.dp)
private val oversSuffix = Regex("""\s+\(([^()]*\bov(?:ers?)?\.?)\)$""", RegexOption.IGNORE_CASE)

@Composable
internal fun sportBorderColor() = MaterialTheme.colorScheme.let { lerp(it.surface, it.onSurface, .27f) }

@Composable
internal fun SportFilter(text: String, selected: Boolean, choose: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Box(Modifier.selectable(selected, role = Role.Tab, onClick = choose).heightIn(min = 48.dp),
        contentAlignment = Alignment.Center) {
        Box(Modifier.background(if (selected) lerp(c.surface, c.primary, .22f) else c.surface, cardShape)
            .border(1.dp, if (selected) lerp(c.onSurface, c.primary, .6f) else sportBorderColor(), cardShape)
            .padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(text, color = c.onSurface, fontFamily = condensed, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 14.sp, lineHeight = 18.sp, maxLines = 1)
        }
    }
}

private data class FixtureScore(val value: String, val overs: String? = null)

private fun fixtureScore(value: String, cricket: Boolean): FixtureScore {
    val overs = if (cricket) oversSuffix.find(value) else null
    return if (overs == null) FixtureScore(value) else FixtureScore(value.substring(0, overs.range.first), overs.groupValues[1])
}

@Composable
internal fun FixtureTile(event: SportsEvent, hideScores: Boolean, use24h: Boolean, modifier: Modifier = Modifier,
    open: ((SportsEvent) -> Unit)? = null) {
    val c = MaterialTheme.colorScheme
    val fill = lerp(c.surface, c.onSurface, .065f)
    val edge = sportBorderColor()
    val league = SportsCatalog.league(event.leagueId)
    val sides = event.ordered(league?.sport ?: Sport.OTHER)
    val clock = if (event.hasTime) formatClock(event.startMs, use24h) else null
    val status = event.status?.takeIf { !hideScores && it.isNotBlank() }
    val interaction = if (open == null) Modifier else Modifier.clickable(role = Role.Button) { open(event) }
    Column(modifier.clip(cardShape).background(fill).border(1.dp, edge, cardShape).then(interaction).heightIn(min = 64.dp)) {
        if (league != null || clock != null || status != null) {
            Row(Modifier.fillMaxWidth().background(lerp(fill, c.primary, .085f)).padding(horizontal = 11.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    league?.let { Text(it.name, fontFamily = condensed, fontWeight = FontWeight.Medium, fontSize = 13.sp,
                        lineHeight = 16.sp, color = c.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    clock?.let { Text(it, fontFamily = condensed, fontSize = 12.sp, lineHeight = 15.sp, color = c.onSurfaceVariant) }
                }
                status?.let {
                    val live = event.state == GameState.LIVE && !event.awaitingUpdate
                    Text(it, Modifier.widthIn(max = 104.dp).background(if (live) lerp(fill, c.primary, .23f) else fill, cardShape)
                        .padding(horizontal = 7.dp, vertical = 3.dp), fontFamily = condensed, fontSize = 12.sp,
                        lineHeight = 15.sp, fontWeight = FontWeight.Medium, color = c.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            HorizontalDivider(color = edge.copy(alpha = .65f))
        }
        if (sides.size == 2) {
            val scores = remember(sides, hideScores, event.hasScore, league?.sport) {
                sides.map { side -> side.score?.takeIf { event.hasScore && !hideScores && it.isNotBlank() }
                    ?.let { fixtureScore(it, league?.sport == Sport.CRICKET) } }
            }
            FixtureScoreboard(sides, scores)
        } else {
            Text(event.title, Modifier.fillMaxWidth().padding(12.dp), color = c.onSurface, fontFamily = condensed,
                fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold,
                maxLines = if (open == null) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun FixtureScoreboard(sides: List<Side>, scores: List<FixtureScore?>) {
    val c = MaterialTheme.colorScheme
    val nameStyle = TextStyle(fontFamily = condensed, fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
    val scoreStyle = nameStyle.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")
    val oversStyle = nameStyle.copy(fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.Normal)
    val measure = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 4.dp)) {
        val available = with(density) { (maxWidth - 26.dp - 8.dp - 10.dp).roundToPx() }
        // Make the switch for the whole match so both teams share the same column layout.
        val stack = scores.any { it != null } && sides.indices.any { index ->
            val score = scores[index] ?: return@any false
            val scoreWidth = maxOf(measure.measure(score.value, scoreStyle, softWrap = false).size.width,
                score.overs?.let { measure.measure(it, oversStyle, softWrap = false).size.width } ?: 0)
            measure.measure(sides[index].team.name, nameStyle, softWrap = false).size.width + scoreWidth > available
        }
        Column {
            sides.forEachIndexed { index, side ->
                if (index > 0) HorizontalDivider(color = sportBorderColor().copy(alpha = .55f))
                Row(Modifier.fillMaxWidth().heightIn(min = 38.dp).padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TeamLogo(side.team, 26.dp)
                    val score = scores[index]
                    Column(Modifier.weight(1f)) {
                        Text(side.team.name, style = nameStyle, color = c.onSurface)
                        if (stack && score != null) {
                            Text(listOfNotNull(score.value, score.overs).joinToString("  ·  "), style = scoreStyle, color = c.onSurface)
                        }
                    }
                    if (!stack && score != null) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(score.value, style = scoreStyle, color = c.onSurface)
                            score.overs?.let { Text(it, style = oversStyle, color = c.onSurfaceVariant) }
                        }
                    }
                }
            }
        }
    }
}
