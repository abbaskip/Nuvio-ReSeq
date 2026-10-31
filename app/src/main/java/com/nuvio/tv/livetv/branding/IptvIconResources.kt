package com.nuvio.tv.livetv.branding

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.AppIconOption
import com.nuvio.tv.ui.screens.settings.bannerResource
import com.nuvio.tv.ui.screens.settings.iconResource
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * The "+ IPTV" icon and banner for each icon color (they live in the fork's resources, so they
 * are looked up by name and fall back to Nuvio's originals if missing).
 */
object IptvIconResources {
    private fun suffix(option: AppIconOption) = when (option) {
        AppIconOption.ORIGINAL -> ""
        AppIconOption.ARCTIC_BLUE -> "_arctic_blue"
        AppIconOption.EMERALD -> "_emerald"
        AppIconOption.ROSE_GOLD -> "_rose_gold"
        AppIconOption.COPPER -> "_copper"
        AppIconOption.GRAPHITE -> "_graphite"
    }

    private fun lookup(context: Context, name: String): Int =
        context.resources.getIdentifier(name, "mipmap", context.packageName)

    fun banner(context: Context, option: AppIconOption, iptv: Boolean): Int =
        if (!iptv) option.bannerResource
        else lookup(context, "iptv_banner${suffix(option)}").takeIf { it != 0 } ?: option.bannerResource

    fun icon(context: Context, option: AppIconOption, iptv: Boolean): Int =
        if (!iptv) option.iconResource
        else lookup(context, "iptv_ic_launcher${suffix(option)}").takeIf { it != 0 } ?: option.iconResource
}

/** The switch at the top of the App icon picker. */
@Composable
fun IptvIconToggle(enabled: Boolean, busy: Boolean, onToggle: (Boolean) -> Unit) {
    androidx.tv.material3.Surface(
        onClick = { if (!busy) onToggle(!enabled) },
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.BackgroundCard
        ),
        border = androidx.tv.material3.ClickableSurfaceDefaults.border(
            focusedBorder = androidx.tv.material3.Border(NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = RoundedCornerShape(12.dp))
        ),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Show \"+ IPTV\" on the icon", color = NuvioTheme.colors.TextPrimary)
                Text(
                    if (enabled) "On: the Nuvio + IPTV icon and banner in your chosen color"
                    else "Off: Nuvio's original icons, in your chosen color",
                    color = NuvioTheme.colors.TextSecondary,
                    style = androidx.tv.material3.MaterialTheme.typography.bodySmall
                )
            }
            Switch(checked = enabled, onCheckedChange = null)
        }
    }
}
