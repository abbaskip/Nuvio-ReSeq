package com.nuvio.tv.mobile;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.widget.TextView;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Standalone test APK activity with its own UID, no storage permission and no app classpath. */
public class DownloadReaderActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        byte[] header = new byte[8];
        try (InputStream input = getContentResolver().openInputStream(getIntent().getData())) {
            if (input == null) throw new IOException("Missing video");
            new DataInputStream(input).readFully(header);
        } catch (IOException | SecurityException error) {
            setResult(RESULT_CANCELED);
            finish();
            return;
        }
        ResultReceiver reply = getIntent().getParcelableExtra("reply");
        if (reply != null) {
            Bundle result = new Bundle();
            result.putByteArray("header", header);
            reply.send(RESULT_OK, result);
            finish();
            return;
        }
        TextView status = new TextView(this);
        String type = new String(header, 4, 4, StandardCharsets.US_ASCII);
        status.setText("ftyp".equals(type) ? "Opened downloaded video" : "Unrecognized video");
        status.setOnClickListener(view -> {
            setResult(RESULT_OK, new Intent().putExtra("position", 3500).putExtra("duration", 130000));
            finish();
        });
        setContentView(status);
    }
}
