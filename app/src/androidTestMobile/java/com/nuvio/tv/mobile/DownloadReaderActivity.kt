package com.nuvio.tv.mobile

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.ResultReceiver
import android.widget.TextView

/** Installed in the test APK, with its own UID and no storage permissions. */
class DownloadReaderActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = requireNotNull(intent.data)
        val header = contentResolver.openInputStream(uri)?.use { it.readNBytes(8) }
        val reply = intent.getParcelableExtra<ResultReceiver>("reply")
        if (reply != null) {
            reply.send(RESULT_OK, Bundle().apply { putByteArray("header", header) })
            finish()
        } else {
            setContentView(TextView(this).apply {
                text = if (header?.decodeToString(4, 8) == "ftyp") "Opened downloaded video" else "Unrecognized video"
                setOnClickListener {
                    setResult(RESULT_OK, Intent().putExtra("position", 3500).putExtra("duration", 130000))
                    finish()
                }
            })
        }
    }
}
