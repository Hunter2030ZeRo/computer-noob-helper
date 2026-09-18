package com.example.commaengdoughme

internal class HttpFailure(val status: Int, val retryAfterSeconds: Long? = null, hint: String? = null) :
    IllegalStateException("HTTP $status · ${hint ?: ConnectionErrors.http(status)}")

/** Retry only explicit temporary HTTP failures, never uncertain transport failures. */
internal object RequestRetry {
    fun <T> run(
        allowed: () -> Boolean = { true },
        progress: (String) -> Unit = {},
        sleep: (Long) -> Unit = { Thread.sleep(it) },
        jitter: () -> Long = { kotlin.random.Random.nextLong(501) },
        request: () -> T,
    ): T {
        for (attempt in 0..2) {
            check(allowed()) { "요청 중지 · 앱으로 돌아와 시야를 다시 보내세요" }
            try { return request() }
            catch (error: HttpFailure) {
                val retryable = error.status in setOf(502, 503, 504)
                if (!retryable || attempt == 2 || (error.retryAfterSeconds ?: 0) > 30) {
                    throw IllegalStateException("${error.message}\n총 ${attempt + 1}회 시도 후 중지")
                }
                val delayMs = maxOf(2000L shl attempt, (error.retryAfterSeconds ?: 0).coerceAtLeast(0) * 1000) + jitter()
                progress("HTTP ${error.status} · ${ (delayMs + 999) / 1000 }초 후 재시도 ${attempt + 1}/2")
                var remaining = delayMs
                while (remaining > 0) {
                    check(allowed()) { "재시도 중지 · 시야를 다시 보내세요" }
                    val chunk = minOf(remaining, 200L)
                    sleep(chunk); remaining -= chunk
                }
            }
        }
        error("재시도 종료")
    }
}
