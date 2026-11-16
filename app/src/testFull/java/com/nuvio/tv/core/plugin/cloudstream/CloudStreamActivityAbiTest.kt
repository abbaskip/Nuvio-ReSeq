package com.nuvio.tv.core.plugin.cloudstream

import android.app.Activity
import com.lagradost.cloudstream3.CommonActivity
import org.junit.Assert.assertNotNull
import org.junit.Test

class CloudStreamActivityAbiTest {
    @Test fun olderAndNewerExtensionsKeepTheirActivityAndToastEntryPoints() {
        val type = CommonActivity.javaClass
        assertNotNull(type.getField("activity"))
        assertNotNull(type.getMethod("getActivity"))
        assertNotNull(type.getMethod("setActivity", Activity::class.java))
        assertNotNull(type.getMethod("showToast", String::class.java, Int::class.javaPrimitiveType))
        assertNotNull(type.getMethod("showToast", String::class.java, Integer::class.java))
        assertNotNull(type.getMethod("showToast", Activity::class.java, String::class.java, Integer::class.java))
        assertNotNull(Class.forName("com.lagradost.cloudstream3.MainActivity"))
    }
}
