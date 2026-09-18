package com.example.commaengdoughme

import java.net.SocketTimeoutException
import org.junit.Assert.*
import org.junit.Test

class RequestRetryTest {
    @Test fun transientFailuresRecoverWithIncreasingDelay() {
        var attempts = 0
        var waited = 0L
        val progress = mutableListOf<String>()
        val result = RequestRetry.run(sleep = { waited += it }, jitter = { 0 }, progress = { progress += it }) {
            attempts++
            if (attempts < 3) throw HttpFailure(503)
            "ok"
        }
        assertEquals("ok", result); assertEquals(3, attempts); assertEquals(6000L, waited)
        assertEquals(2, progress.size)
    }
    @Test fun persistent503StopsAfterThreeAttempts() {
        var attempts = 0
        val error = assertThrows(IllegalStateException::class.java) {
            RequestRetry.run(sleep = {}, jitter = { 0 }) { attempts++; throw HttpFailure(503) }
        }
        assertEquals(3, attempts); assertTrue(error.message!!.contains("총 3회"))
    }
    @Test fun authenticationQuotaAndTransportFailuresAreNotRetried() {
        for (failure in listOf(HttpFailure(401), HttpFailure(429), SocketTimeoutException())) {
            var attempts = 0
            assertThrows(Exception::class.java) {
                RequestRetry.run(sleep = { fail("unexpected retry") }) { attempts++; throw failure }
            }
            assertEquals(1, attempts)
        }
    }
    @Test fun leavingAppCancelsPendingRetry() {
        var allowed = true
        var attempts = 0
        assertThrows(IllegalStateException::class.java) {
            RequestRetry.run(allowed = { allowed }, sleep = { allowed = false }, jitter = { 0 }) {
                attempts++; throw HttpFailure(503)
            }
        }
        assertEquals(1, attempts)
    }
    @Test fun serverRetryAfterIsRespectedWithinBound() {
        var attempts = 0
        var waited = 0L
        RequestRetry.run(sleep = { waited += it }, jitter = { 0 }) {
            if (++attempts == 1) throw HttpFailure(503, 7)
        }
        assertEquals(7000L, waited)
        attempts = 0
        assertThrows(IllegalStateException::class.java) {
            RequestRetry.run(sleep = { fail("must not retry early") }) { attempts++; throw HttpFailure(503, 60) }
        }
        assertEquals(1, attempts)
    }
}
