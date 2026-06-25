package com.dev.docscannerpdf.network

import org.junit.Assert.assertEquals
import org.junit.Test

class ApiConfigTest {
    @Test
    fun normalizeBaseUrl_addsTrailingSlashWhenMissing() {
        assertEquals(
            "http://192.168.1.25:3000/",
            ApiConfig.normalizeBaseUrl("http://192.168.1.25:3000")
        )
    }

    @Test
    fun normalizeBaseUrl_keepsExistingTrailingSlash() {
        assertEquals(
            "http://10.0.2.2:3000/",
            ApiConfig.normalizeBaseUrl(ApiConfig.EMULATOR_BASE_URL)
        )
    }
}
