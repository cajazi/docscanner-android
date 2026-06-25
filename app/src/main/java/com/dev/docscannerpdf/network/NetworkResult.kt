package com.dev.docscannerpdf.network

import retrofit2.Response

sealed interface NetworkResult<out T> {
    data class Success<T>(val data: T) : NetworkResult<T>

    data class Error(
        val code: Int,
        val message: String,
        val errorBody: String? = null
    ) : NetworkResult<Nothing>

    data class Exception(val throwable: Throwable) : NetworkResult<Nothing>
}

suspend inline fun <T> safeApiCall(
    crossinline call: suspend () -> Response<T>
): NetworkResult<T> {
    return try {
        val response = call()
        val body = response.body()
        if (response.isSuccessful && body != null) {
            NetworkResult.Success(body)
        } else {
            NetworkResult.Error(
                code = response.code(),
                message = response.message(),
                errorBody = response.errorBody()?.string()
            )
        }
    } catch (throwable: Throwable) {
        NetworkResult.Exception(throwable)
    }
}
