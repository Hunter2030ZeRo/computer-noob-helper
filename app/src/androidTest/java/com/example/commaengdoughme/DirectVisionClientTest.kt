package com.example.commaengdoughme

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DirectVisionClientTest {
    private val config = ProviderConfig(ModelProvider.GEMINI, "gemini-test", "fake-key-123456789")
    @Test fun setupRoundTripAndArbitraryEndpointRejected() {
        val restored = ProviderConfig.parse(config.encode())
        assertEquals(config.apiKey, restored.apiKey)
        assertThrows(IllegalArgumentException::class.java) {
            ProviderConfig.parse(JSONObject(config.encode()).put("endpoint", "https://evil.invalid").toString())
        }
    }
    @Test fun nativeGeminiBodyCarriesImageAndGoalWithoutToolsOrKey() {
        val frame = VisionResult("test", "observation", 123L, 1, 1, byteArrayOf(1, 2, 3))
        val raw = DirectVisionClient.body(config, frame, "help")
        val body = JSONObject(raw)
        assertEquals("AQID", body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(1)
            .getJSONObject("inlineData").getString("data"))
        assertFalse(raw.contains(config.apiKey)); assertFalse(body.has("tools"))
        val openai = JSONObject(DirectVisionClient.body(ProviderConfig(ModelProvider.OPENAI, "model", config.apiKey), frame, "help"))
        assertEquals("image_url", openai.getJSONArray("messages").getJSONObject(1).getJSONArray("content").getJSONObject(1).getString("type"))
    }
    @Test fun bothResponsesParseAndTruncatedResponsesFail() {
        val answer = "{\"advice\":\"요약\",\"steps\":[\"다음\"]}"
        val openai = JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("finish_reason", "stop")
            .put("message", JSONObject().put("content", answer))))
        assertEquals(listOf("요약", "다음"), DirectVisionClient.parse(ModelProvider.OPENAI, openai.toString()))
        val gemini = """{"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":${JSONObject.quote(answer)}}]}}]}"""
        assertEquals(listOf("요약", "다음"), DirectVisionClient.parse(ModelProvider.GEMINI, gemini))
        assertThrows(IllegalStateException::class.java) { DirectVisionClient.parse(ModelProvider.GEMINI, gemini.replace("STOP", "MAX_TOKENS")) }
    }
}
