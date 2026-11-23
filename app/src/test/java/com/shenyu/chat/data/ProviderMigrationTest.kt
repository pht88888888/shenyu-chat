package com.shenyu.chat.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderMigrationTest {

    @Test
    fun defaultOriginMigratesIntoOfficialProvider() {
        val (providers, activeId) = migrateLegacyProvider(
            origin = "https://api.deepseek.com",
            apiKey = "sk-old",
        )
        assertEquals(1, providers.size)
        assertEquals(SettingsState.OFFICIAL_PROVIDER_ID, activeId)
        assertEquals("sk-old", providers[0].apiKey)
        assertTrue(providers[0].builtin)
    }

    @Test
    fun customOriginBecomesActiveCustomProvider() {
        val (providers, activeId) = migrateLegacyProvider(
            origin = "https://mirror.example.com",
            apiKey = "sk-mirror",
        )
        assertEquals(2, providers.size)
        assertEquals("legacy-custom", activeId)
        assertEquals("https://mirror.example.com", providers[1].baseUrl)
        assertEquals("sk-mirror", providers[1].apiKey)
        assertEquals(ProviderProtocol.DEEPSEEK, providers[1].protocol)
    }

    @Test
    fun blankOriginFallsBackToOfficial() {
        val (providers, activeId) = migrateLegacyProvider(origin = null, apiKey = null)
        assertEquals(1, providers.size)
        assertEquals(SettingsState.OFFICIAL_PROVIDER_ID, activeId)
        assertEquals("", providers[0].apiKey)
    }
}
