package com.example.commaengdoughme

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DirectVisionClientTest {
    @Test fun openRouterUsesImageJsonAndFreeRoutingAndRestoresQr() {
        val router = ProviderConfig(ModelProvider.OPENROUTER, "google/gemma-4-26b-a4b-it:free", "fake-key-123456789")
        assertEquals(router.model, ProviderConfig.parse(router.encode()).model)
        assertEquals(ModelProvider.OPENROUTER, ProviderConfig.parse(router.encode()).provider)
        val body = JSONObject(DirectVisionClient.body(router, VisionResult("test", "ocr", 1L, 1, 1, byteArrayOf(1)), "help"))
        assertEquals(router.model, body.getString("model"))
        assertFalse(body.has("response_format"))
        assertTrue(body.getJSONObject("provider").getBoolean("require_parameters"))
        for (field in listOf("prompt", "completion", "request", "image")) {
            assertEquals(0, body.getJSONObject("provider").getJSONObject("max_price").getInt(field))
        }
        assertEquals("data:image/jpeg;base64,AQ==", body.getJSONArray("messages").getJSONObject(1)
            .getJSONArray("content").getJSONObject(1).getJSONObject("image_url").getString("url"))
        assertFalse(body.toString().contains(router.apiKey))
        val answer = JSONObject().put("advice", "안내").put("steps", org.json.JSONArray())
        val response = JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("finish_reason", "stop")
            .put("message", JSONObject().put("content", answer.toString()))))
        assertEquals(listOf("안내"), DirectVisionClient.parse(ModelProvider.OPENROUTER, response.toString()))
        response.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            .put("content", "```json\n$answer\n```")
        assertEquals(listOf("안내"), DirectVisionClient.parse(ModelProvider.OPENROUTER, response.toString()))
    }
    @Test fun router404HintsDoNotEchoUpstreamSecrets() {
        val raw = """{"error":{"message":"No endpoints found matching your data policy secret-api-key"}}"""
        val hint = DirectVisionClient.openRouterNotFoundHint(raw)
        assertTrue(hint.contains("정책")); assertFalse(hint.contains("secret-api-key"))
        assertTrue(DirectVisionClient.openRouterNotFoundHint("not json secret-api-key").contains("무료 경로"))
    }
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
