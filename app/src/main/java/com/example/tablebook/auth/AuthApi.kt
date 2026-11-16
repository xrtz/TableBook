package com.example.tablebook.auth

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

internal data class SessionTokens(
    val accessToken: String,
    val refreshToken: String,
    val scope: String = java.util.UUID.randomUUID().toString(),
)

enum class AuthFailure {
    INVALID_CREDENTIALS, INVALID_REGISTRATION, EMAIL_TAKEN, EXPIRED, CONNECTION,
}

internal class AuthException(val failure: AuthFailure, cause: Throwable? = null) : Exception(cause)

internal interface AuthApi {
    suspend fun signIn(email: String, password: String, register: Boolean): SessionTokens
    suspend fun refresh(refreshToken: String): SessionTokens
    suspend fun logout(refreshToken: String)
}

internal class HttpAuthApi(private val baseUrl: String) : AuthApi {
    override suspend fun signIn(email: String, password: String, register: Boolean): SessionTokens {
        val action = if (register) "register" else "login"
        return post(action, JSONObject().put("email", email).put("password", password)).tokens()
    }

    override suspend fun refresh(refreshToken: String): SessionTokens =
        post("refresh", JSONObject().put("refreshToken", refreshToken)).tokens()

    override suspend fun logout(refreshToken: String) {
        post("logout", JSONObject().put("refreshToken", refreshToken))
    }

    private suspend fun post(action: String, body: JSONObject): String = withContext(Dispatchers.IO) {
        if (baseUrl.isEmpty()) throw AuthException(AuthFailure.CONNECTION)
        try {
            val connection = URI("$baseUrl/v1/auth/$action").toURL().openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 5_000
                connection.readTimeout = 12_000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                if (status in 200..299) {
                    connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                } else {
                    val message = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    val code = message?.let { runCatching { JSONObject(it).optString("code") }.getOrNull() }
                    val failure = when (code) {
                        "INVALID_CREDENTIALS" -> AuthFailure.INVALID_CREDENTIALS
                        "INVALID_REGISTRATION" -> AuthFailure.INVALID_REGISTRATION
                        "EMAIL_TAKEN" -> AuthFailure.EMAIL_TAKEN
                        "UNAUTHENTICATED" -> AuthFailure.EXPIRED
                        else -> AuthFailure.CONNECTION
                    }
                    throw AuthException(failure)
                }
            } finally {
                connection.disconnect()
            }
        } catch (error: IOException) {
            throw AuthException(AuthFailure.CONNECTION, error)
        }
    }

    private fun String.tokens(): SessionTokens = try {
        val json = JSONObject(this)
        SessionTokens(json.getString("accessToken"), json.getString("refreshToken"))
    } catch (error: JSONException) {
        throw AuthException(AuthFailure.CONNECTION, error)
    }
}
