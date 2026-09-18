package com.example.commaengdoughme

import org.json.JSONObject

enum class ModelProvider(val id: String, val label: String) {
    OPENAI("openai", "OpenAI API"), GEMINI("gemini", "Gemini API"), OPENROUTER("openrouter", "OpenRouter 무료");
    companion object {
        fun fromId(id: String) = entries.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("지원하지 않는 Provider입니다")
    }
}

class ProviderConfig(val provider: ModelProvider, val model: String, val apiKey: String) {
    init {
        val validModel = if (provider == ModelProvider.OPENROUTER) {
            model == "openrouter/free" || model.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,59}/[A-Za-z0-9][A-Za-z0-9._-]{0,119}:free"))
        } else model.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,119}"))
        require(validModel) { "모델 ID를 확인하세요. OpenRouter는 :free 모델 또는 openrouter/free를 사용하세요" }
        require(apiKey.length in 16..512 && apiKey.all { it.code in 33..126 }) { "API 키 형식을 확인하세요" }
    }
    fun endpoint(): String = when (provider) {
        ModelProvider.OPENAI -> "https://api.openai.com/v1/chat/completions"
        ModelProvider.OPENROUTER -> "https://openrouter.ai/api/v1/chat/completions"
        ModelProvider.GEMINI -> "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
    }
    override fun toString() = "ProviderConfig(${provider.id}, $model, [REDACTED])"
    fun encode(): String = JSONObject().put("type", "computer-noob-helper.config").put("version", 1)
        .put("provider", provider.id).put("model", model).put("api_key", apiKey).toString()
    companion object {
        fun parse(raw: String): ProviderConfig {
            require(raw.length <= 4096) { "설정 QR이 너무 큽니다" }
            val json = try { JSONObject(raw) } catch (_: Exception) { throw IllegalArgumentException("설정 QR이 아닙니다") }
            require(json.optString("type") == "computer-noob-helper.config" && json.optInt("version") == 1) { "지원하지 않는 설정 QR입니다" }
            require(json.keys().asSequence().all { it in setOf("type", "version", "provider", "model", "api_key") }) { "알 수 없는 설정 항목입니다" }
            return ProviderConfig(ModelProvider.fromId(json.optString("provider")), json.optString("model"), json.optString("api_key"))
        }
    }
}
