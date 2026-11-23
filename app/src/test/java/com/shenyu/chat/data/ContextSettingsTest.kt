package com.shenyu.chat.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ContextSettingsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun reset() {
        context.getSharedPreferences("shenyu_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `existing install defaults to automatic 1M and retains other preferences`() {
        context.getSharedPreferences("shenyu_settings", Context.MODE_PRIVATE).edit()
            .putBoolean("thinking", true).putBoolean("search", true).commit()
        val store = SettingsStore(context)
        assertEquals(LongDocMode.AUTO, store.current.longDocMode)
        assertEquals(true, store.current.thinkingEnabled)
        assertEquals(true, store.current.searchEnabled)
    }

    @Test
    fun `context mode persists after restart without changing search toggle`() {
        val store = SettingsStore(context)
        store.update { it.copy(longDocMode = LongDocMode.ALWAYS, searchEnabled = true) }
        assertEquals(LongDocMode.ALWAYS, SettingsStore(context).current.longDocMode)
        assertEquals(true, SettingsStore(context).current.searchEnabled)
        store.update { it.copy(longDocMode = LongDocMode.OFF) }
        assertEquals(LongDocMode.OFF, SettingsStore(context).current.longDocMode)
    }

    @Test
    fun `unknown context mode is recovered to automatic`() {
        context.getSharedPreferences("shenyu_settings", Context.MODE_PRIVATE).edit()
            .putString("long_doc_mode", "invalid").commit()
        assertEquals(LongDocMode.AUTO, SettingsStore(context).current.longDocMode)
    }
}
