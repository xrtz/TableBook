package com.example.tablebook.booking

import com.example.tablebook.auth.AuthException
import com.example.tablebook.auth.SessionRepository
import com.example.tablebook.domain.booking.BookingDraft
import com.example.tablebook.domain.booking.BookingError
import com.example.tablebook.domain.booking.BookingException
import com.example.tablebook.domain.booking.BookingTable
import com.example.tablebook.domain.booking.ConfirmedReservation
import com.example.tablebook.domain.booking.CreateReservationResult
import com.example.tablebook.domain.booking.FloorPlan
import com.example.tablebook.domain.booking.FloorTable
import com.example.tablebook.domain.booking.GuestBookingRepository
import com.example.tablebook.domain.booking.Hall
import com.example.tablebook.domain.booking.OwnedReservation
import com.example.tablebook.domain.booking.ReservationStatus
import com.example.tablebook.domain.booking.TableShape
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal class HttpBookingRepository(
    private val url: String,
    private val sessions: SessionRepository,
) : GuestBookingRepository {
    override suspend fun halls(): List<Hall> = decode {
        request("/halls").body.objects().map {
            Hall(it.getLong("id"), it.getString("name"), ZoneId.of(it.getString("timeZone")))
        }
    }

    override suspend fun floorPlan(hallId: Long): FloorPlan = decode {
        val json = JSONObject(request("/halls/$hallId/floor-plan").body)
        val tables = json.getJSONArray("tables").objects().map {
            FloorTable(
                BookingTable(it.getLong("id"), it.getInt("capacity")),
                it.getString("label"), TableShape.valueOf(it.getString("shape").uppercase(Locale.ROOT)),
                it.getInt("x"), it.getInt("y"), it.getInt("size"),
            )
        }
        FloorPlan(json.getInt("width"), json.getInt("height"), tables)
    }

    override suspend fun availability(hallId: Long, startsAt: Instant, guestCount: Int): Set<Long> = decode {
        request("/halls/$hallId/availability?startsAt=$startsAt&guestCount=$guestCount").body.objects()
            .filter { it.getBoolean("available") }.map { it.getLong("tableId") }.toSet()
    }

    override suspend fun myReservations(): List<OwnedReservation> = decode {
        request("/reservations", authenticated = true).body.objects().map {
            OwnedReservation(
                it.reservation(), it.getString("tableLabel"), it.getString("hallName"),
                ZoneId.of(it.getString("timeZone")), ReservationStatus.valueOf(it.getString("status").uppercase(Locale.ROOT)),
            )
        }
    }

    override suspend fun cancelReservation(id: Long) {
        request("/reservations/$id/cancel", body = JSONObject(), authenticated = true)
    }

    override suspend fun createReservation(draft: BookingDraft, idempotencyKey: String): CreateReservationResult {
        val payload = JSONObject().put("tableId", draft.table.id)
            .put("guestCount", draft.guestCount).put("startsAt", draft.startsAt.toString())
        return try {
            val response = request("/reservations", payload, authenticated = true, key = idempotencyKey)
            decode { CreateReservationResult.Confirmed(JSONObject(response.body).reservation()) }
        } catch (failure: ResponseError) {
            CreateReservationResult.Failure.entries.firstOrNull { it.name == failure.code }
                ?: CreateReservationResult.Failure.TEMPORARILY_UNAVAILABLE
        } catch (failure: BookingException) {
            if (failure.error == BookingError.SESSION_EXPIRED) CreateReservationResult.Failure.UNAUTHENTICATED
            else CreateReservationResult.Failure.TEMPORARILY_UNAVAILABLE
        }
    }

    private suspend fun request(
        path: String,
        body: JSONObject? = null,
        authenticated: Boolean = false,
        key: String? = null,
    ): HttpReply {
        val access = if (authenticated) sessions.access() ?: throw BookingException(BookingError.SESSION_EXPIRED) else null
        var reply = execute(path, body, access?.token, key)
        if (reply.status == 401 && access != null) {
            val token = try {
                sessions.refreshAfterUnauthorized(access)
            } catch (error: AuthException) {
                throw BookingException(BookingError.CONNECTION, error)
            } ?: throw BookingException(BookingError.SESSION_EXPIRED)
            reply = execute(path, body, token, key)
        }
        if (reply.status in 200..299) return reply
        val code = runCatching { JSONObject(reply.body).getString("code") }.getOrNull()
        if (key != null && reply.status in 400..499 && code != null) throw ResponseError(code)
        throw BookingException(
            when {
                reply.status == 401 -> BookingError.SESSION_EXPIRED
                reply.status == 404 -> BookingError.NOT_FOUND
                code == "INVALID_START_TIME" -> BookingError.INVALID_START
                code == "RESERVATION_CANNOT_CANCEL" -> BookingError.CANNOT_CANCEL
                else -> BookingError.CONNECTION
            },
        )
    }

    private suspend fun execute(path: String, body: JSONObject?, token: String?, key: String?): HttpReply =
        withContext(Dispatchers.IO) {
            if (url.isEmpty()) throw BookingException(BookingError.CONNECTION)
            try {
                val connection = URI("$url/v1$path").toURL().openConnection() as HttpURLConnection
                try {
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 5_000
                    connection.readTimeout = 12_000
                    connection.setRequestProperty("Accept", "application/json")
                    token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
                    key?.let { connection.setRequestProperty("Idempotency-Key", it) }
                    if (body != null) {
                        connection.requestMethod = "POST"
                        connection.doOutput = true
                        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    }
                    val status = connection.responseCode
                    val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                    HttpReply(status, stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty())
                } finally {
                    connection.disconnect()
                }
            } catch (error: IOException) {
                throw BookingException(BookingError.CONNECTION, error)
            }
        }

    private inline fun <T> decode(parse: () -> T): T = try {
        parse()
    } catch (error: org.json.JSONException) {
        throw BookingException(BookingError.CONNECTION, error)
    } catch (error: java.time.DateTimeException) {
        throw BookingException(BookingError.CONNECTION, error)
    } catch (error: IllegalArgumentException) {
        throw BookingException(BookingError.CONNECTION, error)
    }

    private fun JSONObject.reservation() = ConfirmedReservation(
        getLong("id"), getLong("tableId"), getInt("guestCount"), Instant.parse(getString("startsAt")),
        Instant.parse(getString("endsAt")), getLong("version"),
    )

    private fun String.objects() = JSONArray(this).objects()
    private fun JSONArray.objects() = List(length()) { getJSONObject(it) }

    private data class HttpReply(val status: Int, val body: String)
    private class ResponseError(val code: String) : Exception()
}
