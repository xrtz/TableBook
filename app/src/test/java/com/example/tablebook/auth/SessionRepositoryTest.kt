package com.example.tablebook.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionRepositoryTest {
    @Test
    fun concurrentUnauthorizedResponsesShareOneRefresh() = runBlocking {
        val initial = SessionTokens("first-access", "first-refresh")
        val updated = SessionTokens("next-access", "next-refresh")
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var calls = 0
        val api = object : AuthApi {
            override suspend fun signIn(email: String, password: String, register: Boolean) = initial

            override suspend fun refresh(refreshToken: String): SessionTokens {
                assertEquals(initial.refreshToken, refreshToken)
                calls++
                started.complete(Unit)
                finish.await()
                return updated
            }

            override suspend fun logout(refreshToken: String) = Unit
        }
        val storage = object : SessionStorage {
            var tokens: SessionTokens? = null
            override fun read(): SessionTokens? = tokens
            override fun write(tokens: SessionTokens) { this.tokens = tokens }
            override fun clear() { tokens = null }
        }
        val repository = SessionRepository(api, storage)
        repository.restore()
        repository.signIn("guest@example.test", "example long passphrase", register = false)
        val previous = requireNotNull(repository.access())
        val first = async { repository.refreshAfterUnauthorized(previous) }
        started.await()
        val second = async { repository.refreshAfterUnauthorized(previous) }
        finish.complete(Unit)
        assertEquals(updated.accessToken, first.await())
        assertEquals(updated.accessToken, second.await())
        assertEquals(1, calls)
        assertEquals(updated.copy(scope = initial.scope), storage.tokens)

        repository.logout()
        repository.signIn("guest@example.test", "example long passphrase", register = false)
        assertNull(repository.refreshAfterUnauthorized(previous))
        assertEquals(1, calls)
    }
}
