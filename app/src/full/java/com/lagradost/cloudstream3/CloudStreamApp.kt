@file:Suppress("unused")

package com.lagradost.cloudstream3

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import com.lagradost.cloudstream3.utils.DataStore
import java.lang.ref.WeakReference

/**
 * Stub for CloudStream's CloudStreamApp class.
 *
 * In the real CloudStream app this is the Application class. Extensions
 * reference its companion fields (context, getKey/setKey helpers, etc.).
 * Some plugins (e.g. AniDb) also reference cfUserAgent. Without this stub
 * the class fails to resolve at runtime, crashing the app with
 * NoClassDefFoundError inside OkHttp interceptors running on async threads.
 */
open class CloudStreamApp {
    companion object {
        /** Cloudflare bypass user-agent used by some extensions. */
        @JvmStatic
        var cfUserAgent: String = try {
            USER_AGENT
        } catch (_: Throwable) {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        }

        private var _context: WeakReference<Context>? = null

        @JvmStatic
        var context: Context?
            get() = _context?.get()
            set(value) {
                _context = if (value != null) WeakReference(value) else null
            }

        /** Retrieve Activity from Context (matches upstream tailrec helper). */
        @JvmStatic
        fun Context.getActivity(): Activity? {
            var ctx: Context? = this
            while (ctx != null) {
                if (ctx is Activity) return ctx
                ctx = (ctx as? ContextWrapper)?.baseContext
            }
            return null
        }

        @JvmStatic
        fun removeKeys(folder: String): Int? = with(DataStore) { context?.removeKeys(folder) }

        @JvmStatic
        fun <T> setKey(path: String, value: T) {
            with(DataStore) {
                if (value == null) context?.removeKey(path) else context?.setKey(path, value)
            }
        }

        @JvmStatic
        fun <T> setKey(folder: String, path: String, value: T) =
            setKey(DataStore.getFolderName(folder, path), value)

        @JvmStatic
        fun <T : Any> setKeyClass(path: String, value: T) = setKey(path, value)

        @JvmStatic
        fun <T : Any> getKeyClass(path: String, valueType: Class<T>): T? =
            with(DataStore) { context?.getKey(path, valueType) }

        @JvmStatic
        inline fun <reified T : Any> getKey(path: String, defVal: T?): T? =
            getKeyClass(path, T::class.java) ?: defVal

        @JvmStatic
        inline fun <reified T : Any> getKey(path: String): T? = getKeyClass(path, T::class.java)

        @JvmStatic
        inline fun <reified T : Any> getKey(folder: String, path: String): T? =
            getKeyClass(DataStore.getFolderName(folder, path), T::class.java)

        @JvmStatic
        inline fun <reified T : Any> getKey(folder: String, path: String, defVal: T?): T? =
            getKey<T>(folder, path) ?: defVal

        @JvmStatic
        fun getKeys(folder: String): List<String>? = with(DataStore) { context?.getKeys(folder) }

        @JvmStatic
        fun removeKey(folder: String, path: String) = removeKey(DataStore.getFolderName(folder, path))

        @JvmStatic
        fun removeKey(path: String) { with(DataStore) { context?.removeKey(path) } }

        @JvmStatic
        fun openBrowser(url: String, fallbackWebView: Boolean = false) {}
    }
}
