package com.example.commaengdoughme

import org.junit.Assert.*
import org.junit.Test

class AgentClientTest {
    @Test fun endpointValidation() {
        assertEquals("agent.example", AgentClient.endpoint(" https://agent.example/vision ").host)
        listOf("http://agent.example", "https:///missing", "https://user:secret@agent.example",
            "https://agent.example/#fragment", "file:///tmp/agent").forEach {
            assertThrows(IllegalArgumentException::class.java) { AgentClient.endpoint(it) }
        }
    }
}
