package com.example.commaengdoughme

import org.junit.Assert.*
import org.junit.Test

class ProviderConfigTest {
    @Test fun openRouterOnlyAcceptsFreeModelIdsOnFixedHost() {
        val config = ProviderConfig(ModelProvider.OPENROUTER, "google/gemma-4-26b-a4b-it:free", "fake-test-key-123456789")
        assertEquals("https://openrouter.ai/api/v1/chat/completions", config.endpoint())
        ProviderConfig(ModelProvider.OPENROUTER, "openrouter/free", config.apiKey)
        for (model in listOf("google/gemma-4-26b-a4b-it", "../model:free", "google/model:free?x=1", "https://evil.invalid/model:free")) {
            assertThrows(IllegalArgumentException::class.java) { ProviderConfig(ModelProvider.OPENROUTER, model, config.apiKey) }
        }
    }
    private val key = "fake-test-key-123456789"
    @Test fun endpointsAreFixedAndSecretsRedacted() {
        val openai = ProviderConfig(ModelProvider.OPENAI, "vision-model", key)
        assertEquals("https://api.openai.com/v1/chat/completions", openai.endpoint())
        val gemini = ProviderConfig(ModelProvider.GEMINI, "gemini-test", key)
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-test:generateContent", gemini.endpoint())
        assertFalse(gemini.toString().contains(key))
    }
    @Test fun rejectsUnknownProviderUrlInjectionAndHeaderInjection() {
        assertThrows(IllegalArgumentException::class.java) { ModelProvider.fromId("chatgpt") }
        assertThrows(IllegalArgumentException::class.java) { ProviderConfig(ModelProvider.GEMINI, "../evil?key=x", key) }
        assertThrows(IllegalArgumentException::class.java) { ProviderConfig(ModelProvider.OPENAI, "model", key + "\r\nX: evil") }
    }
}
