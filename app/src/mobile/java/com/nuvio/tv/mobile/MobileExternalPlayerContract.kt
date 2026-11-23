package com.nuvio.tv.mobile

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContract
import com.nuvio.tv.core.player.ExternalPlayerInput
import com.nuvio.tv.core.player.ExternalPlayerResult
import com.nuvio.tv.core.player.ExternalPlayerResultContract

/** Keep the shared player extras and result formats while allowing a fresh choice on a phone with several players. */
internal class MobileExternalPlayerContract : ActivityResultContract<ExternalPlayerInput, ExternalPlayerResult?>() {
    private val contract = ExternalPlayerResultContract()
    override fun createIntent(context: Context, input: ExternalPlayerInput): Intent {
        val view = contract.createIntent(context, input)
        val players = context.packageManager.queryIntentActivities(view, PackageManager.MATCH_DEFAULT_ONLY)
        return if (players.map { it.activityInfo.packageName }.distinct().size > 1)
            Intent.createChooser(view, "Choose video player") else view
    }
    override fun parseResult(resultCode: Int, intent: Intent?) = contract.parseResult(resultCode, intent)
}
