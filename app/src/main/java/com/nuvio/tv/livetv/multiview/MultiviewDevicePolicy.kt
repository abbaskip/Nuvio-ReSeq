package com.nuvio.tv.livetv.multiview

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecList
import com.nuvio.tv.ui.screens.settings.MemoryBudget

/** Reserve one decoder for system/UI work. A failed capability query assumes two instances. */
internal object MultiviewDevicePolicy {
    fun limit(avc: Int?, hevc: Int?, heapMb: Long, lowRam: Boolean, physicalRamMb: Long = 0): Int {
        val decoders = minOf(avc?.takeIf { it > 0 } ?: 2, hevc?.takeIf { it > 0 } ?: 2)
        val memory = when {
            lowRam || heapMb < 512 || physicalRamMb in 1..2304 -> 2
            heapMb < 1024 || physicalRamMb in 2305..3584 -> 3
            else -> 4
        }
        return minOf(4, (decoders - 1).coerceAtLeast(0), memory)
    }

    fun read(context: Context): Int {
        val am = context.getSystemService(ActivityManager::class.java)
        val ram = ActivityManager.MemoryInfo().also(am::getMemoryInfo).totalMem / (1024 * 1024)
        fun instances(mime: String): Int? = runCatching {
            val candidates = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.asSequence()
                .filter { !it.isEncoder && it.supportedTypes.any { type -> type.equals(mime, true) } }
                .toList()
            // Match the preferred decoder class. A software fallback's advertised count must
            // never inflate a TV's hardware budget; emulators use software when that is all they have.
            fun software(name: String) = name.startsWith("OMX.google.", true) || name.startsWith("c2.android.", true)
            val preferred = candidates.firstOrNull {
                if (android.os.Build.VERSION.SDK_INT >= 29) !it.isSoftwareOnly else !software(it.name)
            } ?: candidates.firstOrNull()
            preferred?.getCapabilitiesForType(mime)?.maxSupportedInstances
        }.getOrNull()
        return limit(instances("video/avc"), instances("video/hevc"), MemoryBudget.heapMb,
            am.isLowRamDevice || MemoryBudget.isLowRamTier, ram)
    }
}
