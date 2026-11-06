package com.nuvio.tv.core.plugin.cloudstream

import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile
import okhttp3.ResponseBody

private const val MAX_DEX_SIZE = 10L * 1024 * 1024

/** Build a replacement alongside the installed extension so a failed refresh leaves it usable. */
internal fun stageExtension(directory: File, body: ResponseBody, fileHash: String?): File {
    require(body.contentLength() <= MAX_DEX_SIZE) { "Extension exceeds size limit" }
    val temp = File.createTempFile("download-", ".cs3", directory)
    try {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var count = 0L
        body.byteStream().use { input ->
            temp.outputStream().use { output ->
                while (true) {
                    val size = input.read(buffer)
                    if (size < 0) break
                    count += size
                    require(count <= MAX_DEX_SIZE) { "Extension exceeds size limit" }
                    output.write(buffer, 0, size)
                    digest.update(buffer, 0, size)
                }
            }
        }
        if (body.contentLength() >= 0 && count != body.contentLength()) throw IOException("Incomplete download")
        if (!fileHash.isNullOrBlank()) {
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            require(fileHash.trim().equals("sha256-$hex", ignoreCase = true)) { "Invalid extension checksum" }
        }
        // Older repositories also distribute raw DEX. Standard .cs3 archives contain classes.dex.
        if (!temp.inputStream().use(::hasDexHeader)) {
            ZipFile(temp).use { archive ->
                val dex = requireNotNull(archive.getEntry("classes.dex")) { "Missing classes.dex" }
                require(archive.getInputStream(dex).use(::hasDexHeader)) { "Invalid classes.dex" }
            }
        }
        return temp
    } catch (failure: Throwable) {
        temp.delete()
        throw failure
    }
}

private fun hasDexHeader(input: InputStream): Boolean {
    val header = ByteArray(8)
    try {
        DataInputStream(input).readFully(header)
    } catch (_: java.io.EOFException) {
        return false
    }
    return header[0] == 'd'.code.toByte() && header[1] == 'e'.code.toByte() &&
        header[2] == 'x'.code.toByte() && header[3] == '\n'.code.toByte() &&
        (4..6).all { header[it].toInt() in '0'.code..'9'.code } && header[7] == 0.toByte()
}
