package com.shenyu.chat.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SettingsStoreProviderTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val prefs
        get() = context.getSharedPreferences("shenyu_settings", Context.MODE_PRIVATE)

    @Before
    fun reset() {
        prefs.edit().clear().commit()
    }

    @Test
    fun migratesLegacyCustomOriginIntoActiveProvider() {
        prefs.edit()
            .putString("api_key", "sk-legacy")
            .putString("api_origin", "https://mirror.example.com")
            .commit()

        val store = SettingsStore(context)

        assertEquals("legacy-custom", store.current.activeProviderId)
        assertEquals("sk-legacy", store.current.apiKey)
        assertEquals("https://mirror.example.com", store.current.apiOrigin)
        assertEquals(2, store.current.providers.size)
    }

    @Test
    fun providersKeepIndependentKeysAndSurviveRestart() {
        val store = SettingsStore(context)
        val custom = ProviderConfig(
            id = "custom-1",
            name = "中转",
            baseUrl = "https://relay.example.com/v1",
            apiKey = "sk-custom",
            model = "claude-3-5-sonnet-latest",
            protocol = ProviderProtocol.ANTHROPIC,
        )
        store.update {
            it.copy(providers = it.providers + custom, activeProviderId = custom.id)
        }
        store.update {
            it.updateProvider(it.activeProviderId) { provider -> provider.copy(apiKey = "sk-custom-2") }
        }
        store.update {
            it.updateProvider(SettingsState.OFFICIAL_PROVIDER_ID) { provider -> provider.copy(apiKey = "sk-official") }
        }

        val reloaded = SettingsStore(context)
        assertEquals("custom-1", reloaded.current.activeProviderId)
        assertEquals("sk-custom-2", reloaded.current.apiKey)
        assertEquals("claude-3-5-sonnet-latest", reloaded.current.model)
        assertEquals(
            "sk-official",
            reloaded.current.providers.first { it.id == SettingsState.OFFICIAL_PROVIDER_ID }.apiKey,
        )
    }

    @Test
    fun missingActiveIdFallsBackToFirstProvider() {
        val store = SettingsStore(context)
        store.update { it.copy(activeProviderId = "does-not-exist") }

        val reloaded = SettingsStore(context)

        assertEquals(SettingsState.OFFICIAL_PROVIDER_ID, reloaded.current.activeProviderId)
        assertTrue(reloaded.current.activeProvider.builtin)
    }
}
