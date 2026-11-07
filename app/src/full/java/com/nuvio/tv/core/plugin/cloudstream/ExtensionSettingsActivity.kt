package com.nuvio.tv.core.plugin.cloudstream

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.CommonActivity
import com.nuvio.tv.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Hosts the extension's native UI above the TV provider list, following Nuvio Echo's AppCompat approach. */
@AndroidEntryPoint
class ExtensionSettingsActivity : AppCompatActivity() {
    @Inject lateinit var loader: ExternalExtensionLoader

    private var previousCommon: Activity? = null
    private var previousAcra: Activity? = null
    private var pending: ((Context) -> Unit)? = null
    private var callbackStarted = false
    private var dialogTookFocus = false

    private val fragments = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentDestroyed(fm: FragmentManager, f: Fragment) {
            window.decorView.post {
                if (!isDestroyed && !isFinishing && !isChangingConfigurations &&
                    fm.fragments.none { !it.isRemoving }) finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Plugin fragments may require constructor arguments and a DEX class loader. On recreation
        // reopen via the plugin rather than asking Android to instantiate its old fragment by name.
        super.onCreate(null)
        val id = intent.getStringExtra(EXTRA_ID)
        if (id == null) { finish(); return }
        supportFragmentManager.registerFragmentLifecycleCallbacks(fragments, false)
        lifecycleScope.launch {
            try {
                pending = withContext(Dispatchers.IO) { checkNotNull(loader.settingsCallback(id)) }
                if (hasWindowFocus()) showPendingSettings()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                failed(e)
            }
        }
    }

    private fun showPendingSettings() {
        val callback = pending ?: return
        pending = null
        previousCommon = CommonActivity.activity
        previousAcra = AcraApplication.getActivity()
        CommonActivity.activity = this
        AcraApplication.setActivity(this)
        callbackStarted = true
        try {
            callback(this)
            supportFragmentManager.executePendingTransactions()
        } catch (e: Throwable) {
            failed(e)
        }
    }

    private fun failed(e: Throwable) {
        if (e is VirtualMachineError || e is ThreadDeath) throw e
        if (e is LinkageError) {
            Log.w("ExtensionSettings", "Settings callback linkage failure", e)
        } else {
            Log.w("ExtensionSettings", "Settings callback failed: ${e.javaClass.simpleName}")
        }
        Toast.makeText(this, R.string.plugin_extension_settings_failed, Toast.LENGTH_SHORT).show()
        finish()
    }

    // A plain AlertDialog has no fragment lifecycle. Close the host when that window gives focus back.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && pending != null) { showPendingSettings(); return }
        if (!callbackStarted) return
        if (!hasFocus) dialogTookFocus = true
        else if (dialogTookFocus && supportFragmentManager.fragments.isEmpty()) finish()
    }

    override fun onDestroy() {
        supportFragmentManager.unregisterFragmentLifecycleCallbacks(fragments)
        if (CommonActivity.activity === this) CommonActivity.activity = previousCommon?.takeUnless { it.isDestroyed }
        if (AcraApplication.getActivity() === this) AcraApplication.setActivity(previousAcra?.takeUnless { it.isDestroyed })
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_ID = "scraper_id"

        fun open(activity: Activity, scraperId: String) {
            activity.startActivity(Intent(activity, ExtensionSettingsActivity::class.java).putExtra(EXTRA_ID, scraperId))
        }
    }
}
