package com.nuvio.tv.livetv.home

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Pictures for the launcher rows. Launchers fetch pictures themselves, and some can't (a plain
 * http address, a server that turns them away, a format they don't read), which left blank "!"
 * tiles. So each picture is fetched here once, drawn as a square tile (the logo on a dark
 * background, or the team's letters when there's no logo) and handed to the launcher from the
 * app's own storage.
 */
object LauncherImages {
    private const val DIR = "launcher_img"
    // Wide tiles (16:9), like the launcher's own, with the logo well inside them.
    private const val W = 640
    private const val H = 360

    private fun dir(context: Context) = File(context.cacheDir, DIR).apply { mkdirs() }

    private fun name(key: String): String =
        MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) } + ".png"

    /** The tile for [key] (made if it isn't there yet), as an address the launcher can open. */
    fun uriFor(context: Context, key: String, logoUrl: String?, label: String): Uri? = runCatching {
        val file = File(dir(context), name("v2|" + key + "|" + logoUrl))
        if (!file.exists() || file.length() == 0L) {
            val logo = logoUrl?.let { fetch(it) }
            val tile = draw(logo, label)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.outputStream().use { tile.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
        }
        Uri.parse("content://${context.packageName}.launcherimg/${file.name}")
    }.getOrNull()

    /** Tiles not used for two weeks go. */
    fun tidy(context: Context) {
        runCatching {
            val old = System.currentTimeMillis() - 14L * 24 * 3600 * 1000
            dir(context).listFiles()?.forEach { if (it.lastModified() < old) it.delete() }
        }
    }

    private fun fetch(url: String): Bitmap? {
        return try {
            var address = url
            var result: Bitmap? = null
            for (hop in 0 until 4) {
                val c = URL(address).openConnection() as HttpURLConnection
                c.connectTimeout = 6_000
                c.readTimeout = 8_000
                c.instanceFollowRedirects = false
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36")
                val code = c.responseCode
                if (code in 300..399) {
                    val next = c.getHeaderField("Location")
                    c.disconnect()
                    if (next == null) break
                    address = URL(URL(address), next).toString()
                    continue
                }
                if (code == 200) {
                    val bytes = c.inputStream.use { it.readBytes() }
                    c.disconnect()
                    if (bytes.size <= 3_000_000) result = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } else {
                    c.disconnect()
                }
                break
            }
            result
        } catch (e: Exception) {
            null
        }
    }

    private fun draw(logo: Bitmap?, label: String): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1A1E2A.toInt() }
        c.drawRoundRect(RectF(0f, 0f, W.toFloat(), H.toFloat()), 36f, 36f, bg)
        if (logo != null && logo.width > 0 && logo.height > 0) {
            // About half the tile's width and under two thirds of its height, so it sits in it with room around.
            val boxW = W * 0.5f
            val boxH = H * 0.62f
            val scale = minOf(boxW / logo.width, boxH / logo.height)
            val w = logo.width * scale
            val h = logo.height * scale
            val left = (W - w) / 2f
            val top = (H - h) / 2f
            c.drawBitmap(logo, null, RectF(left, top, left + w, top + h), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        } else {
            val text = label.trim().uppercase().take(4).ifBlank { "?" }
            val hue = (text.hashCode().toLong().and(0x7fffffff) % 360).toFloat()
            val r = H * 0.32f
            val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.62f)) }
            c.drawCircle(W / 2f, H / 2f, r, disc)
            val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 6f; color = 0xCCFFFFFF.toInt()
            }
            c.drawCircle(W / 2f, H / 2f, r, ring)
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                textAlign = Paint.Align.CENTER
                isFakeBoldText = true
                textSize = if (text.length <= 2) H * 0.30f else H * 0.21f
            }
            val y = H / 2f - (tp.descent() + tp.ascent()) / 2f
            c.drawText(text, W / 2f, y, tp)
        }
        return bmp
    }
}

/** Lets the launcher read the tiles above (and nothing else). */
class LauncherImageProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val name = uri.lastPathSegment ?: throw FileNotFoundException()
        if (!Regex("[0-9a-f]{40}\\.png").matches(name)) throw FileNotFoundException()
        val f = File(File(context!!.cacheDir, "launcher_img"), name)
        if (!f.exists()) throw FileNotFoundException()
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String = "image/png"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
