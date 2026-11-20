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
    private String mode;
    private String actionLabel;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        mode = arguments.getString("mode", "stream");
        actionLabel = arguments.getString("actionLabel", "");
        index = Integer.parseInt(arguments.getString("index", "1"));
        start();
    }

    private static boolean contains(AccessibilityNodeInfo node, String text, String other) {
        if (node == null || !APP.equals(String.valueOf(node.getPackageName()))) return false;
        String value = String.valueOf(node.getText()) + " " + String.valueOf(node.getContentDescription());
        if (node.isVisibleToUser() && value.contains(text) && value.contains(other)) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            if (contains(node.getChild(i), text, other)) return true;
        }
        return false;
    }

    private boolean visible(AccessibilityNodeInfo root, String title) {
        if (root == null || !APP.equals(String.valueOf(root.getPackageName()))) return false;
        if (mode.equals("settings")) {
            return contains(root, "Backup & Sync", "") && contains(root, "Profiles", "");
        }
        if (mode.equals("detail")) {
            return contains(root, "Profile measurement series", "")
                    && (contains(root, "Season 1", "") || contains(root, "Experiment episode", "")
                    || contains(root, "Controlled series synopsis", ""));
        }
        // Both values occur in the description of one real stream. A page heading
        // and a provider filter chip on their own can never satisfy this check.
        return streamCard(root, title);
    }

    private static boolean streamCard(AccessibilityNodeInfo node, String title) {
        if (node == null || !APP.equals(String.valueOf(node.getPackageName()))) return false;
        if (node.isVisibleToUser() && node.isClickable() && contains(node, title, "")
                && contains(node, "NativeFixture", "")) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            if (streamCard(node.getChild(i), title)) return true;
        }
        return false;
    }

    private static AccessibilityNodeInfo button(AccessibilityNodeInfo node, String label) {
        if (node == null || !APP.equals(String.valueOf(node.getPackageName()))) return null;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = button(node.getChild(i), label);
            if (child != null) return child;
        }
        return node.isVisibleToUser() && node.isClickable() && contains(node, label, "") ? node : null;
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            if (index < 1 || index > 64) throw new IllegalArgumentException("Unknown fixture");
            String name = String.format(java.util.Locale.US, "Fixture Movie %03d", index);
            UiAutomation ui = getUiAutomation();
            if (mode.equals("discoverAction")) {
                if (!(actionLabel.equals("Advanced Discover") || actionLabel.equals("Show results") || actionLabel.equals("Reset"))) {
                    throw new IllegalArgumentException("Unknown Discovery action");
                }
                long deadline = SystemClock.elapsedRealtime() + 4_000;
                while (SystemClock.elapsedRealtime() < deadline) {
                    AccessibilityNodeInfo target = button(ui.getRootInActiveWindow(), actionLabel);
                    if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        result.putString("activated", actionLabel);
                        finish(Activity.RESULT_OK, result);
                        return;
                    }
                    SystemClock.sleep(100);
                }
                throw new IllegalStateException("No selectable Discovery button");
            }
            if (visible(ui.getRootInActiveWindow(), name)) throw new IllegalStateException("Target already visible");
            String command;
            switch (mode) {
                case "stream":
                    // The driver verifies Compose's focused menu row before starting the clock.
                case "settings":
                    command = "input keyevent KEYCODE_DPAD_CENTER";
                    break;
                case "detail":
                    command = "am start -a android.intent.action.VIEW -d nuvio://series/tt9900998 -p " + APP;
                    break;
                default:
                    throw new IllegalArgumentException("Unknown journey");
            }
            long start = SystemClock.elapsedRealtime();
            try (InputStream output = new ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command))) {
                byte[] data = new byte[512];
                StringBuilder response = new StringBuilder();
                for (int count; (count = output.read(data)) != -1; ) {
                    response.append(new String(data, 0, count, java.nio.charset.StandardCharsets.UTF_8));
                }
                if (response.toString().contains("Error") || response.toString().contains("Exception")) {
                    throw new IllegalStateException("Android could not launch the fixture intent");
                }
            }
            long previous = start;
            while (SystemClock.elapsedRealtime() - start < 35_000) {
                AccessibilityNodeInfo root = ui.getRootInActiveWindow();
                if (visible(root, name)) {
                    result.putLong("pickerMs", SystemClock.elapsedRealtime() - start);
                    result.putLong("previousMissMs", previous - start);
                    finish(Activity.RESULT_OK, result);
                    return;
                }
                previous = SystemClock.elapsedRealtime();
                SystemClock.sleep(25);
            }
            AccessibilityNodeInfo last = ui.getRootInActiveWindow();
            throw new IllegalStateException("Missing " + mode + " controls. Foreground package was "
                    + (last == null ? "unknown" : last.getPackageName()));
        } catch (Exception failure) {
            result.putString("error", failure.toString());
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
