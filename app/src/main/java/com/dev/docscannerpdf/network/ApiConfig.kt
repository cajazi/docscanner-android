package com.dev.docscannerpdf.network

object ApiConfig {
    const val EMULATOR_BASE_URL = "http://10.0.2.2:3000/"

    // Replace with your machine's LAN IP when testing from a physical device.
    const val PHYSICAL_DEVICE_BASE_URL = "http://192.168.1.100:3000/"

    val defaultBaseUrl: String = EMULATOR_BASE_URL

    fun normalizeBaseUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim()
        require(trimmed.isNotEmpty()) { "Base URL cannot be blank." }
        return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
    }
}
