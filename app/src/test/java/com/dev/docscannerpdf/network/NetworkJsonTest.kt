package com.dev.docscannerpdf.network

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkJsonTest {
    @Test
    fun jsonParsing_ignoresUnknownFields() {
        val payload = """
            {
              "status": "ok",
              "service": "docscanner-api",
              "unexpected": "ignored"
            }
        """.trimIndent()

        val response = NetworkClient.json.decodeFromString<HealthResponse>(payload)

        assertEquals("ok", response.status)
        assertEquals("docscanner-api", response.service)
    }
}
