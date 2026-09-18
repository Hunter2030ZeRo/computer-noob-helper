package com.example.commaengdoughme

import org.junit.Assert.*
import org.junit.Test
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException

class ConnectionErrorsTest {
    @Test fun networkFailuresAreDistinguishedWithoutEchoingSecrets() {
        val secret = "secret-api-key-must-not-appear"
        val errors = listOf(UnknownHostException(secret), SocketTimeoutException(secret), SSLHandshakeException(secret))
        val messages = errors.map(ConnectionErrors::network)
        assertEquals(3, messages.toSet().size)
        messages.forEach { assertFalse(it.contains(secret)) }
        assertTrue(messages[0].contains("DNS"))
        assertTrue(messages[1].contains("시간 초과"))
        assertTrue(messages[2].contains("TLS"))
    }
    @Test fun authenticationModelAndQuotaFailuresHaveDifferentRemedies() {
        assertTrue(ConnectionErrors.http(401).contains("키를 다시 등록"))
        assertTrue(ConnectionErrors.http(404).contains("모델"))
        assertTrue(ConnectionErrors.http(429).contains("할당량"))
        assertTrue(ConnectionErrors.http(503).contains("잠시 후"))
    }
}
