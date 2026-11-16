package com.example.tablebook.booking

import android.app.Service
import android.content.Intent
import android.os.Binder
import com.example.tablebook.BuildConfig
import com.example.tablebook.TableBookApplication
import com.example.tablebook.auth.AuthException
import com.example.tablebook.auth.SessionRepository
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class BookingEventsService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private val changes = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1)
    private var connection: Job? = null
    private var subscription: Long? = null

    inner class Connection : Binder() {
        val updates = changes.asSharedFlow()

        fun subscribe(hallId: Long?) {
            if (connection?.isActive == true && subscription == hallId) return
            connection?.cancel()
            subscription = hallId
            val app = application as TableBookApplication
            connection = scope.launch { listen(app.sessions, hallId) }
        }

        fun disconnect() {
            connection?.cancel()
            connection = null
        }
    }

    override fun onBind(intent: Intent) = Connection()

    override fun onUnbind(intent: Intent): Boolean {
        connection?.cancel()
        connection = null
        return false
    }

    override fun onDestroy() {
        scope.cancel()
        client.dispatcher.executorService.shutdown()
        super.onDestroy()
    }

    private suspend fun listen(sessions: SessionRepository, hallId: Long?) {
        if (BuildConfig.API_URL.isEmpty()) return
        val query = hallId?.let { "?hallId=$it" }.orEmpty()
        var backoff = 1_000L
        while (currentCoroutineContext().isActive) {
            val access = sessions.access() ?: return
            val request = Request.Builder().url("${BuildConfig.API_URL}/v1/events$query")
                .header("Authorization", "Bearer ${access.token}").build()
            val messages = Channel<String>(Channel.CONFLATED)
            val listener = EventsListener(messages)
            val socket = client.newWebSocket(request, listener)
            try {
                for (payload in messages) {
                    val event = decode<BookingEvent>(payload)
                    if (event?.type == "bookings_changed") {
                        backoff = 1_000L
                        changes.emit(Unit)
                    }
                }
            } finally {
                socket.cancel()
                messages.cancel()
            }
            if (listener.expired) {
                try {
                    if (sessions.refreshAfterUnauthorized(access) == null) return
                } catch (_: AuthException) {
                    // Retry after a temporary network error.
                }
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(30_000L)
        }
    }
}

private class EventsListener(private val messages: Channel<String>) : WebSocketListener() {
    @Volatile var expired = false
        private set

    override fun onMessage(webSocket: WebSocket, text: String) {
        messages.trySend(text)
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        expired = code == 1008
        webSocket.close(code, null)
        messages.close()
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        expired = response?.code == 401
        messages.close()
    }
}

@Serializable
private data class BookingEvent(val type: String)

private inline fun <reified T> decode(payload: String): T? = try {
    Json.decodeFromString<T>(payload)
} catch (_: SerializationException) {
    null
}
