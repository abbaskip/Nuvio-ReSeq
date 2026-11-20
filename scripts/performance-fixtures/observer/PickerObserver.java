package com.nuvio.perf.observer;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.InputStream;

/** Observe the app from its TV accessibility tree, without instrumenting the signed app. */
public final class PickerObserver extends Instrumentation {
    private static final String APP = "io.github.abbaskip.nuvioreseq";
    private int index;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        index = Integer.parseInt(arguments.getString("index"));
        start();
    }

    private static boolean hasStreamCard(AccessibilityNodeInfo node, String name) {
        boolean title = false;
        boolean provider = false;
        for (AccessibilityNodeInfo n : node.findAccessibilityNodeInfosByText(name)) {
            CharSequence text = n.getText();
            if (text != null && text.toString().contains(name)) title = true;
        }
        for (AccessibilityNodeInfo n : node.findAccessibilityNodeInfosByText("NativeFixture")) {
            CharSequence text = n.getText();
            if (text != null && text.toString().contains("NativeFixture")) provider = true;
        }
        return title && provider;
    }

    private static boolean rendered(AccessibilityNodeInfo node, String title) {
        if (node == null || !APP.equals(String.valueOf(node.getPackageName()))) return false;
        if (node.isVisibleToUser() && node.isFocusable() && node.isClickable() && hasStreamCard(node, title)) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            if (rendered(node.getChild(i), title)) return true;
        }
        return false;
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            if (index < 1 || index > 64) throw new IllegalArgumentException("Unknown fixture");
            String name = String.format(java.util.Locale.US, "Fixture Movie %03d", index);
            String content = "tmdb:" + (1000 + index);
            UiAutomation ui = getUiAutomation();
            if (rendered(ui.getRootInActiveWindow(), name)) throw new IllegalStateException("Picker already visible");
            String command = "am start -n " + APP + "/com.nuvio.tv.launcher.AppIconDefault"
                    + " --es launchMode stream --es contentType movie --es contentId " + content
                    + " --es videoId " + content + " --es name '" + name + "'";
            long start = SystemClock.elapsedRealtime();
            try (InputStream output = new ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command))) {
                byte[] data = new byte[512];
                while (output.read(data) != -1) { }
            }
            long previous = start;
            while (SystemClock.elapsedRealtime() - start < 35_000) {
                AccessibilityNodeInfo root = ui.getRootInActiveWindow();
                if (rendered(root, name)) {
                    result.putLong("pickerMs", SystemClock.elapsedRealtime() - start);
                    result.putLong("previousMissMs", previous - start);
                    finish(Activity.RESULT_OK, result);
                    return;
                }
                previous = SystemClock.elapsedRealtime();
                SystemClock.sleep(25);
            }
            throw new IllegalStateException("The matching stream card did not render");
        } catch (Exception failure) {
            result.putString("error", failure.toString());
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
