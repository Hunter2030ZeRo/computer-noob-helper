package com.example.commaengdoughme

import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

object AgentClient {
    fun endpoint(value: String): URI {
        val uri = URI(value.trim())
        require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank()) { "HTTPS 주소를 입력하세요" }
        require(uri.userInfo == null && uri.fragment == null) { "주소에 인증 정보나 fragment를 넣을 수 없습니다" }
        return uri
    }

    fun payload(result: VisionResult, includeImage: Boolean, goal: String = "화면의 컴퓨터 문제를 설명하고 해결 방법을 안내해 주세요", allowPc: Boolean = false): String = JSONObject().apply {
        put("schema_version", 1)
        put("request_id", result.id)
        put("captured_at_ms", result.capturedAtMs)
        put("text", result.text)
        put("width", result.width)
        put("height", result.height)
        put("image_rotation_degrees", 0)
        put("goal", goal)
        put("allow_pc", allowPc)
        if (includeImage) {
            put("image_mime_type", "image/jpeg")
            put("image_base64", Base64.encodeToString(result.jpeg, Base64.NO_WRAP))
        }
    }.toString()

    fun send(address: String, token: String, result: VisionResult, includeImage: Boolean,
             goal: String = "화면의 컴퓨터 문제를 설명하고 해결 방법을 안내해 주세요", allowPc: Boolean = false): String {
        val uri = endpoint(address)
        require(token.none { it == '\r' || it == '\n' }) { "잘못된 토큰" }
        val body = payload(result, includeImage, goal, allowPc).toByteArray(Charsets.UTF_8)
        return request(uri, token, body, result.id)
    }

    fun poll(address: String, token: String, requestId: String): String {
        require(requestId.matches(Regex("[a-zA-Z0-9-]{1,64}")))
        val base = endpoint(address)
        return request(URI(base.toString().trimEnd('/') + "/" + requestId), token, null, requestId)
    }

    private fun request(uri: URI, token: String, body: ByteArray?, id: String): String {
        require(token.none { it == '\r' || it == '\n' }) { "잘못된 토큰" }
        val connection = uri.toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = if (body == null) "GET" else "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = false
            connection.doOutput = body != null
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Idempotency-Key", id)
            if (token.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val code = connection.responseCode
            check(code in 200..299) { "에이전트 HTTP $code (실패/시간 초과 시 서버 처리 여부를 확인하세요)" }
            // ponytail: cap the response at 64 KiB; add streaming only for a streaming agent API.
            val response = connection.inputStream.use { input ->
                val bytes = ByteArray(65_537)
                var count = 0
                while (count < bytes.size) {
                    val read = input.read(bytes, count, bytes.size - count)
                    if (read < 0) break
                    count += read
                }
                check(count <= 65_536) { "에이전트 응답이 64 KiB를 초과했습니다" }
                String(bytes, 0, count, Charsets.UTF_8)
            }
            return response.ifBlank { "전송 완료 (HTTP $code)" }
        } finally {
            connection.disconnect()
        }
    }
}
