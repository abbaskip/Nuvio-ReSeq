package com.nuvio.tv.ui.components

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.nuvio.tv.R
import com.nuvio.tv.core.storage.DatabaseVersionIssue

/** Independent of the feature graph that cannot safely open its data. */
object DatabaseCompatibilityNotice {
    fun show(activity: Activity, issue: DatabaseVersionIssue) {
        val inset = (48 * activity.resources.displayMetrics.density).toInt()
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(inset, inset, inset, inset)
            setBackgroundColor(Color.BLACK)
        }
        layout.addView(TextView(activity).apply {
            text = activity.getString(R.string.database_compatibility_title)
            textSize = 28f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, inset / 2)
        })
        layout.addView(TextView(activity).apply {
            text = issue.description
            textSize = 22f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, inset / 2)
        })
        val close = Button(activity).apply {
            text = activity.getString(R.string.action_close)
            setOnClickListener { activity.finish() }
        }
        layout.addView(close, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        activity.setContentView(layout)
        close.requestFocus()
    }
}
