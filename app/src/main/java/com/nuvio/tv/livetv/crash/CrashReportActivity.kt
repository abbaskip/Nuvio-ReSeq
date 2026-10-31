package com.nuvio.tv.livetv.crash

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Plain Android views only (no Compose, no Hilt), so it still works when the crash came from the
 * app's own screens. It used to run in a separate process, but the app's start-up code doesn't
 * cope with a second copy of itself running, so opening it closed the app on some boxes.
 */
class CrashReportActivity : Activity() {

    private var server: ReportServer.Handle? = null

    override fun onDestroy() {
        server?.stop()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = intent.getStringExtra(CrashCatcher.EXTRA_REPORT)
            ?: CrashCatcher.lastReport(this)
            ?: "No crash details were saved."

        fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

        val title = TextView(this).apply {
            text = "Nuvio + IPTV stopped"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val hint = TextView(this).apply {
            text = "To send this report: put your phone on the same Wi-Fi as this TV, scan the code with " +
                "your phone's camera, tap Copy report and paste it in your message. No Wi-Fi? Take a " +
                "photo of this screen instead. Use up/down to scroll."
            setTextColor(Color.parseColor("#B0B0B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(6), dp(16), dp(12))
        }
        // The important part (version, device, root cause) as a QR code; a QR code holds about
        // 2,000 characters comfortably, so the full trace stays on screen only.
        // The whole report is a web page on the home network (opened from a phone on the same
        // Wi-Fi); the code holds its address. Without a network, the start of the report instead.
        server = ReportServer.start("Nuvio ReSeq crash report") { report }
        val summary = report.substringBefore("FULL TRACE:").trim().take(1_800)
        val qr = runCatching {
            com.nuvio.tv.core.qr.QrCodeGenerator.generate(server?.url ?: summary, dp(200))
        }.getOrNull()
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val texts = LinearLayout(this@CrashReportActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(title)
                addView(hint)
            }
            addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (qr != null) {
                addView(
                    android.widget.ImageView(this@CrashReportActivity).apply {
                        setImageBitmap(qr)
                        setBackgroundColor(Color.WHITE)
                        setPadding(dp(6), dp(6), dp(6), dp(6))
                    },
                    LinearLayout.LayoutParams(dp(170), dp(170)).apply { bottomMargin = dp(10) }
                )
            }
        }
        val body = TextView(this).apply {
            text = report
            setTextColor(Color.parseColor("#E6E6EA"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(false)
        }
        val scroll = ScrollView(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setBackgroundColor(Color.parseColor("#16161A"))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addView(body)
            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> { smoothScrollBy(0, dp(120)); true }
                    KeyEvent.KEYCODE_DPAD_UP -> { smoothScrollBy(0, -dp(120)); true }
                    else -> false
                }
            }
        }
        // Only offered after a crash, when this screen is all that's left of the app.
        val restart = Button(this).apply {
            text = "Restart app"
            visibility = if (isTaskRoot) View.VISIBLE else View.GONE
            setOnClickListener {
                packageManager.getLaunchIntentForPackage(packageName)?.let {
                    startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                }
                finish()
            }
        }
        val close = Button(this).apply {
            text = "Close"
            setOnClickListener { finish() }
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(12), 0, 0)
            addView(restart)
            addView(close)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0B0D"))
            setPadding(dp(48), dp(28), dp(48), dp(24))
            addView(header)
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(buttons)
        }
        setContentView(root)
        scroll.requestFocus()
        scroll.nextFocusDownId = View.NO_ID
    }
}
