package com.playeverywhere999.vpn_for_friends_v3

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.io.IOException
import java.util.concurrent.TimeUnit

class ServerRepository(
    private val baseUrl: String
) {
    private val client: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Sends the provided token to the server and expects a WireGuard config string in response.
     * Returns the config string on success or throws IOException on network/HTTP failure.
     */
    @Throws(IOException::class)
    fun fetchWireGuardConfigByToken(token: String): String {
        val url = baseUrl.trimEnd('/') + "/api/peers/connect"
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val jsonBody = "{" + "\"token\":\"" + token + "\"}"
        val body = jsonBody.toRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val msg = "HTTP ${'$'}{response.code}: ${'$'}{response.message}"
                Log.e("ServerRepository", "fetchWireGuardConfigByToken failed: ${'$'}msg")
                throw IOException("Server error: ${'$'}msg")
            }
            val responseBody = response.body?.string()
            if (responseBody.isNullOrBlank()) {
                throw IOException("Empty server response")
            }
            // Server can return the raw wg-quick config string. If it returns JSON, adapt here.
            return responseBody
        }
    }
}

