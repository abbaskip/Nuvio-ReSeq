package com.nuvio.tv.updater

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import okio.ByteString.Companion.toByteString
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@Singleton
class ApkDownloader @Inject constructor(
    private val okHttpClient: OkHttpClient
) {

    suspend fun download(
        url: String,
        destinationFile: File,
        expectedDigest: String? = null,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit
    ): Result<File> {
        val partial = File(destinationFile.parentFile, destinationFile.name + ".part")
        return try {
          currentCoroutineContext().ensureActive()
          Result.success(run {
            destinationFile.parentFile?.mkdirs()
            check(!partial.exists() || partial.delete()) { "Could not remove partial update" }

            val request = Request.Builder()
                .url(url)
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("Download failed: HTTP ${response.code}")
                }

                val body = response.body ?: error("Empty download body")
                val total = body.contentLength().takeIf { it > 0 }
                val sha256 = expectedDigest?.let { MessageDigest.getInstance("SHA-256") }

                body.byteStream().use { input ->
                    FileOutputStream(partial).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var downloaded = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            sha256?.update(buffer, 0, read)
                            downloaded += read
                            onProgress(downloaded, total)
                        }
                        output.flush()
                        check(total == null || downloaded == total) { "Incomplete update download" }
                        check(downloaded > 0) { "Empty update download" }
                        if (sha256 != null) {
                            val actualDigest = "sha256:${sha256.digest().toByteString().hex()}"
                            check(actualDigest.equals(expectedDigest, ignoreCase = true)) {
                                "Downloaded APK does not match the release checksum. Please download it again."
                            }
                        }
                    }
                }
            }

            currentCoroutineContext().ensureActive()
            check(partial.renameTo(destinationFile)) { "Could not save completed update" }
            destinationFile
          })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            partial.delete()
        }
    }
}
