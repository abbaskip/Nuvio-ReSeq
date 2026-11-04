package com.nuvio.tv.core.util

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.nuvio.tv.R

/**
 * Raw bytes of the app wordmark image, served to the web-config pages,
 * or null when the resource cannot be read.
 */
fun Context.readAppLogoBytes(): ByteArray? =
    try {
        val logo = Uri.Builder()
            .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
            .authority(packageName)
            .appendPath(R.drawable.app_logo_wordmark.toString())
            .build()
        contentResolver.openInputStream(logo)?.use { it.readBytes() }
    } catch (_: Exception) {
        null
    }
