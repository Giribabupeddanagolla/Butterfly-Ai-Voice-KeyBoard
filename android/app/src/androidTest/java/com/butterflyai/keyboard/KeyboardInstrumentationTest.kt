package com.butterflyai.keyboard

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeyboardInstrumentationTest {

    @Test
    fun useAppContext() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.butterflyai.keyboard", appContext.packageName)
    }

    @Test
    fun testSharedPreferencesPersistence() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("butterfly_prefs_test", Context.MODE_PRIVATE)

        prefs.edit()
            .putString("server_url", "http://192.168.1.100:8000")
            .putString("source_language", "te")
            .putString("target_language", "en")
            .apply()

        assertEquals("http://192.168.1.100:8000", prefs.getString("server_url", null))
        assertEquals("te", prefs.getString("source_language", null))
        assertEquals("en", prefs.getString("target_language", null))

        // Clean up test preferences
        prefs.edit().clear().apply()
    }
}
