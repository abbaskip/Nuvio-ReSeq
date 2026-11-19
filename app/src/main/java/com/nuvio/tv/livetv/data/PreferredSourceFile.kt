package com.nuvio.tv.livetv.data

import java.io.File

/** During cache migration a newer, complete legacy file still wins. */
internal fun preferredSourceFile(cached: File, legacy: File): File =
    if (legacy.exists() && (!cached.exists() || legacy.lastModified() > cached.lastModified())) legacy else cached
