@file:Suppress("unused")

package com.lagradost.cloudstream3

import android.app.Activity
import android.widget.Toast

/** Stub for CommonActivity referenced by some extensions. */
object CommonActivity {
    var activity: Activity? = null

    // Keep the primitive signature for older extensions and the boxed duration for newer ones.
    fun showToast(message: String, duration: Int = Toast.LENGTH_SHORT) = toast(message, duration)
    fun showToast(message: String?, duration: Int? = null) = toast(message, duration ?: Toast.LENGTH_SHORT)

    private fun toast(message: String?, duration: Int) {
        val host = activity ?: AcraApplication.getActivity() ?: return
        if (message != null) host.runOnUiThread { Toast.makeText(host, message, duration).show() }
    }
}
