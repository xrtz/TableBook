package com.example.tablebook.domain.booking

import java.time.Instant
import java.time.LocalDate

data class AdminReservation(
    val reservation: ConfirmedReservation,
    val tableLabel: String,
    val guestEmail: String,
    val status: ReservationStatus,
)

interface AdminBookingRepository {
    suspend fun accountIsAdmin(): Boolean
    suspend fun halls(): List<Hall>
    suspend fun floorPlan(hallId: Long): FloorPlan
    suspend fun journal(hallId: Long, day: LocalDate, status: ReservationStatus?): List<AdminReservation>
    suspend fun createForGuest(email: String, draft: BookingDraft, key: String)
    suspend fun move(id: Long, version: Long, tableId: Long, start: Instant)
    suspend fun changeStatus(id: Long, version: Long, status: ReservationStatus)
}
