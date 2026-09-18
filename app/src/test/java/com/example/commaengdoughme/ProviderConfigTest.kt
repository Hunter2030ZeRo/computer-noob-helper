package com.example.commaengdoughme

import org.junit.Assert.*
import org.junit.Test

class ProviderConfigTest {
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
