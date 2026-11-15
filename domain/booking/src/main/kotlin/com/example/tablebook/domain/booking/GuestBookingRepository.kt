package com.example.tablebook.domain.booking

import java.time.Instant
import java.time.ZoneId

data class Hall(val id: Long, val name: String, val zone: ZoneId)

enum class TableShape { ROUND, SQUARE }

data class FloorTable(
    val table: BookingTable,
    val label: String,
    val shape: TableShape,
    val x: Int,
    val y: Int,
    val size: Int,
)

data class FloorPlan(val width: Int, val height: Int, val tables: List<FloorTable>, val serverTime: Instant)

enum class ReservationStatus { CONFIRMED, SEATED, COMPLETED, CANCELLED }

data class OwnedReservation(
    val reservation: ConfirmedReservation,
    val tableLabel: String,
    val hallName: String,
    val zone: ZoneId,
    val status: ReservationStatus,
)

enum class BookingError {
    INVALID_START, CANNOT_CANCEL, NOT_FOUND, SESSION_EXPIRED, CONNECTION,
    FORBIDDEN, CONFLICT, STALE_RESERVATION, GUEST_NOT_FOUND, INVALID_REQUEST,
}

class BookingException(val error: BookingError, cause: Throwable? = null) : Exception(cause)

interface GuestBookingRepository : BookingRepository {
    suspend fun halls(): List<Hall>
    suspend fun floorPlan(hallId: Long): FloorPlan
    suspend fun availability(hallId: Long, startsAt: Instant, guestCount: Int): Set<Long>
    suspend fun myReservations(): List<OwnedReservation>
    suspend fun cancelReservation(id: Long)
}
