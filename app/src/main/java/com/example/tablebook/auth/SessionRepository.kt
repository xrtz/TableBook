package com.example.tablebook.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal enum class SessionState { LOADING, SIGNED_OUT, SIGNED_IN }
internal data class SessionAccess(val token: String, val generation: Long)

internal class SessionRepository(private val api: AuthApi, private val storage: SessionStorage) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(SessionState.LOADING)
    val state = mutableState.asStateFlow()
    private var tokens: SessionTokens? = null
    private var generation = 0L

    suspend fun restore() = mutex.withLock {
        if (mutableState.value == SessionState.LOADING) {
            tokens = withContext(Dispatchers.IO) { storage.read() }
            publish()
        }
    }

    suspend fun signIn(email: String, password: String, register: Boolean) = mutex.withLock {
        check(tokens == null)
        persist(api.signIn(email, password, register))
    }

    suspend fun access(): SessionAccess? = mutex.withLock {
        tokens?.let { SessionAccess(it.accessToken, generation) }
    }

    // Call after a 401 with the session access used by the failed request.
    suspend fun refreshAfterUnauthorized(rejected: SessionAccess): String? = mutex.withLock {
        val current = tokens ?: return@withLock null
        if (rejected.generation != generation) return@withLock null
        if (current.accessToken != rejected.token) return@withLock current.accessToken
        withContext(NonCancellable) {
            try {
                val refreshed = api.refresh(current.refreshToken)
                persist(refreshed)
                refreshed.accessToken
            } catch (error: AuthException) {
                if (error.failure != AuthFailure.EXPIRED) throw error
                clear()
                null
            }
        }
    }

    suspend fun logout() = mutex.withLock {
        withContext(NonCancellable) {
            tokens?.let { api.logout(it.refreshToken) }
            clear()
        }
    }

    private suspend fun persist(value: SessionTokens) {
        withContext(NonCancellable) {
            withContext(Dispatchers.IO) { storage.write(value) }
            tokens = value
            publish()
        }
    }

    private suspend fun clear() {
        withContext(NonCancellable) {
            withContext(Dispatchers.IO) { storage.clear() }
            tokens = null
            generation++
            publish()
        }
    }

    private fun publish() {
        mutableState.value = if (tokens == null) SessionState.SIGNED_OUT else SessionState.SIGNED_IN
    }
}
