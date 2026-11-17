package com.nuvio.tv.ui.screens.home

/** Cached CW is useful content; catalogue refresh must not keep it behind the splash. */
internal fun homeContentReady(
    cwResolved: Boolean, hasCw: Boolean, presentationReady: Boolean,
    catalogStarted: Boolean, catalogLoading: Boolean, hasCatalog: Boolean, addonCount: Int
): Boolean = cwResolved && presentationReady &&
    (hasCw || (catalogStarted && !catalogLoading && (hasCatalog || addonCount == 0)))
