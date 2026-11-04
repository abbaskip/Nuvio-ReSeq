package com.nuvio.tv.core.util

import android.content.Context
import android.net.Uri
import com.nuvio.tv.R

/**
 * Raw bytes of the app wordmark image, served to the web-config pages,
 * or null when the resource cannot be read.
 */
fun Context.readAppLogoBytes(): ByteArray? =
    try {
        // android.resource supports drawable bytes without treating a drawable ID as @RawRes.
        val uri = Uri.parse("android.resource://$packageName/${R.drawable.app_logo_wordmark}")
        contentResolver.openInputStream(uri)?.use { it.readBytes() }
    } catch (_: Exception) {
        null
    }
