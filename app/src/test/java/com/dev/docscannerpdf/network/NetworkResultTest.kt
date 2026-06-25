package com.dev.docscannerpdf.network

import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class NetworkResultTest {
    @Test
    fun safeApiCall_mapsSuccessfulResponseToSuccess() = runBlocking {
        val result = safeApiCall {
            Response.success(HealthResponse(status = "ok"))
        }

        assertTrue(result is NetworkResult.Success)
        assertEquals("ok", (result as NetworkResult.Success).data.status)
    }

    @Test
    fun safeApiCall_mapsHttpErrorToError() = runBlocking {
        val result = safeApiCall<HealthResponse> {
            Response.error(500, "server error".toResponseBody())
        }

        assertTrue(result is NetworkResult.Error)
        assertEquals(500, (result as NetworkResult.Error).code)
        assertEquals("server error", result.errorBody)
    }

    @Test
    fun safeApiCall_mapsThrownExceptionToException() = runBlocking {
        val throwable = IllegalStateException("network unavailable")
        val result = safeApiCall<HealthResponse> {
            throw throwable
        }

        assertTrue(result is NetworkResult.Exception)
        assertSame(throwable, (result as NetworkResult.Exception).throwable)
    }
}
