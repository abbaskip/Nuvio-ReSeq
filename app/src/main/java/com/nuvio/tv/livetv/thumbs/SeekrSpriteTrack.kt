package com.nuvio.tv.livetv.thumbs

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import tv.seekr.previews.core.PreviewTrack
import tv.seekr.previews.core.SeekrTile
import java.io.IOException

/** Keep just the current compressed sprite sheet. Decode only the rectangle shown on screen. */
internal class SeekrSpriteTrack(private val track: PreviewTrack, private val http: OkHttpClient) {
    private val mutex = Mutex()
    private var sheetUrl: String? = null
    private var decoder: BitmapRegionDecoder? = null

    fun cueAt(positionMs: Long) = track.cueAt(positionMs)

    @Suppress("DEPRECATION")
    suspend fun bitmap(tile: SeekrTile): Bitmap? = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                if (sheetUrl != tile.sheetUrl) {
                    val request = Request.Builder().url(tile.sheetUrl).build()
                    if (!request.url.isHttps || request.url.host != "sprites.seekr.tv") return@withLock null
                    val next = http.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@withLock null
                        val source = response.body.source()
                        if (source.request(MAX_SHEET_BYTES + 1)) return@withLock null
                        val bytes = source.readByteArray()
                        BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
                    }
                    decoder?.recycle()
                    decoder = next
                    sheetUrl = tile.sheetUrl
                }
                val image = decoder ?: return@withLock null
                if (tile.w !in 1..640 || tile.h !in 1..360 ||
                    tile.x !in 0..(image.width - tile.w) || tile.y !in 0..(image.height - tile.h)) return@withLock null
                val rect = Rect(tile.x, tile.y, tile.x + tile.w, tile.y + tile.h)
                image.decodeRegion(rect, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })
            } catch (_: IOException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }

    private companion object { const val MAX_SHEET_BYTES = 4L * 1024 * 1024 }
}
