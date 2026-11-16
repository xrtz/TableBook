package com.example.tablebook.booking

import com.example.tablebook.auth.AuthException
import com.example.tablebook.auth.SessionRepository
import com.example.tablebook.domain.booking.AdminBookingRepository
import com.example.tablebook.domain.booking.AdminReservation
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
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject

internal class HttpBookingRepository(
    private val url: String,
    private val sessions: SessionRepository,
    private val bookingChanged: (String) -> Unit = {},
) : GuestBookingRepository, AdminBookingRepository {
    override suspend fun accountIsAdmin(): Boolean = decode {
        JSONObject(request("/auth/me", authenticated = true).body).getString("role") == "admin"
    }

    override suspend fun journal(hallId: Long, day: LocalDate, status: ReservationStatus?): List<AdminReservation> = decode {
        val filter = status?.let { "&status=${it.name.lowercase(Locale.ROOT)}" }.orEmpty()
        request("/admin/reservations?hallId=$hallId&day=$day$filter", authenticated = true).body.objects().map {
            AdminReservation(it.reservation(), it.getString("tableLabel"), it.getString("guestEmail"),
                ReservationStatus.valueOf(it.getString("status").uppercase(Locale.ROOT)))
        }
    }

    override suspend fun createForGuest(email: String, draft: BookingDraft, key: String) {
        val body = JSONObject().put("email", email).put("guestCount", draft.guestCount)
            .put("tableId", draft.table.id).put("startsAt", draft.startsAt.toString())
        request("/admin/reservations", body, authenticated = true, key = key)
    }

    override suspend fun move(id: Long, version: Long, tableId: Long, start: Instant) {
        val body = JSONObject().put("tableId", tableId).put("startsAt", start.toString()).put("expectedVersion", version)
        request("/admin/reservations/$id/schedule", body, authenticated = true)
    }

    override suspend fun changeStatus(id: Long, version: Long, status: ReservationStatus) {
        val body = JSONObject().put("status", status.name.lowercase(Locale.ROOT)).put("expectedVersion", version)
        request("/admin/reservations/$id/status", body, authenticated = true)
    }
    override suspend fun halls(): List<Hall> = decode {
        get<List<HallDto>>("/halls").map {
            Hall(it.id, it.name, ZoneId.of(it.timeZone))
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
        FloorPlan(json.getInt("width"), json.getInt("height"), tables, Instant.parse(json.getString("serverTime")))
    }

    override suspend fun availability(hallId: Long, startsAt: Instant, guestCount: Int): Set<Long> = decode {
        get<List<AvailabilityDto>>("/halls/$hallId/availability?startsAt=$startsAt&guestCount=$guestCount")
            .filter { it.available }.map { it.tableId }.toSet()
    }

    override suspend fun myReservations(): List<OwnedReservation> = readReservations().reservations

    suspend fun readReservations(expectedScope: String? = null): ReservationSnapshot = decode {
        val reply = request("/reservations", authenticated = true, expectedScope = expectedScope)
        val reservations = reply.body.objects().map {
            OwnedReservation(
                it.reservation(), it.getString("tableLabel"), it.getString("hallName"),
                ZoneId.of(it.getString("timeZone")), ReservationStatus.valueOf(it.getString("status").uppercase(Locale.ROOT)),
            )
        }
        ReservationSnapshot(
            reservations,
            reply.date?.let { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() },
        )
    }

    override suspend fun cancelReservation(id: Long) {
        request("/reservations/$id/cancel", body = JSONObject(), authenticated = true)
    }

    override suspend fun createReservation(draft: BookingDraft, idempotencyKey: String): CreateReservationResult {
        val payload = JSONObject().put("tableId", draft.table.id)
            .put("guestCount", draft.guestCount).put("startsAt", draft.startsAt.toString())
        return try {
            val response = request("/reservations", payload, authenticated = true, key = idempotencyKey, typedCreate = true)
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
        typedCreate: Boolean = false,
        expectedScope: String? = null,
    ): HttpReply {
        val access = if (authenticated) sessions.access() ?: throw BookingException(BookingError.SESSION_EXPIRED) else null
        if (expectedScope != null && access?.scope != expectedScope) throw BookingException(BookingError.SESSION_EXPIRED)
        var reply = execute(path, body, access?.token, key)
        if (reply.status == 401 && access != null) {
            val token = try {
                sessions.refreshAfterUnauthorized(access)
            } catch (error: AuthException) {
                throw BookingException(BookingError.CONNECTION, error)
            } ?: throw BookingException(BookingError.SESSION_EXPIRED)
            reply = execute(path, body, token, key)
        }
        if (reply.status in 200..299) {
            if (access != null && body != null && !path.startsWith("/admin/")) {
                sessions.inScope(access.scope) { bookingChanged(access.scope) }
            }
            return reply
        }
        val code = runCatching { JSONObject(reply.body).getString("code") }.getOrNull()
        if (typedCreate && reply.status in 400..499 && code != null) throw ResponseError(code)
        throw BookingException(
            when {
                reply.status == 401 -> BookingError.SESSION_EXPIRED
                reply.status == 403 -> BookingError.FORBIDDEN
                code == "GUEST_NOT_FOUND" -> BookingError.GUEST_NOT_FOUND
                reply.status == 404 -> BookingError.NOT_FOUND
                code == "INVALID_START_TIME" -> BookingError.INVALID_START
                code == "RESERVATION_CANNOT_CANCEL" -> BookingError.CANNOT_CANCEL
                code == "TABLE_UNAVAILABLE" -> BookingError.CONFLICT
                code == "STALE_RESERVATION" -> BookingError.STALE_RESERVATION
                reply.status in 400..499 -> BookingError.INVALID_REQUEST
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
                    HttpReply(status, stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty(),
                        connection.getHeaderField("Date"))
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

    private suspend inline fun <reified T> get(path: String): T =
        bookingJson.decodeFromString<T>(request(path).body)

    private fun JSONObject.reservation() = ConfirmedReservation(
        getLong("id"), getLong("tableId"), getInt("guestCount"), Instant.parse(getString("startsAt")),
        Instant.parse(getString("endsAt")), getLong("version"),
    )

    private fun String.objects() = JSONArray(this).objects()
    private fun JSONArray.objects() = List(length()) { getJSONObject(it) }

    private data class HttpReply(val status: Int, val body: String, val date: String?)
    private class ResponseError(val code: String) : Exception()
}

private val bookingJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class HallDto(val id: Long, val name: String, val timeZone: String)

@Serializable
private data class AvailabilityDto(val tableId: Long, val available: Boolean)

internal data class ReservationSnapshot(val reservations: List<OwnedReservation>, val serverTime: Instant?)
