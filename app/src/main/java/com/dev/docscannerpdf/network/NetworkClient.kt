package com.dev.docscannerpdf.network

import com.dev.docscannerpdf.BuildConfig
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit

object NetworkClient {
    val json: Json = Json {
        ignoreUnknownKeys = true
    }

    private val contentType = "application/json".toMediaType()

    fun createApiService(
        baseUrl: String = ApiConfig.defaultBaseUrl,
        debugLoggingEnabled: Boolean = BuildConfig.DEBUG
    ): DocScannerApiService {
        return createRetrofit(baseUrl, debugLoggingEnabled)
            .create(DocScannerApiService::class.java)
    }

    fun createRetrofit(
        baseUrl: String = ApiConfig.defaultBaseUrl,
        debugLoggingEnabled: Boolean = BuildConfig.DEBUG
    ): Retrofit {
        return Retrofit.Builder()
            .baseUrl(ApiConfig.normalizeBaseUrl(baseUrl))
            .client(createOkHttpClient(debugLoggingEnabled))
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
    }

    fun createOkHttpClient(
        debugLoggingEnabled: Boolean = BuildConfig.DEBUG
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .apply {
                if (debugLoggingEnabled) {
                    addInterceptor(
                        HttpLoggingInterceptor().apply {
                            level = HttpLoggingInterceptor.Level.BODY
                        }
                    )
                }
            }
            .build()
    }
}
