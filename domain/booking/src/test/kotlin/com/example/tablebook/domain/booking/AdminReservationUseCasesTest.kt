package com.example.tablebook.domain.booking

import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AdminReservationUseCasesTest {
    private val now = Instant.parse("2026-11-16T15:00:00Z")
    private val start = now.plusSeconds(3600)
    private val entry = AdminReservation(
        ConfirmedReservation(123, 1, 3, start, start.plusSeconds(7200), version = 5),
        "01", "guest@example.test", ReservationStatus.CONFIRMED,
    )
    private val touched = mutableListOf<Pair<Long, Long>>()
    private val repository = object : AdminBookingRepository {
        override suspend fun accountIsAdmin(): Boolean = error("Unexpected request")
        override suspend fun halls(): List<Hall> = error("Unexpected request")
        override suspend fun floorPlan(hallId: Long): FloorPlan = error("Unexpected request")
        override suspend fun journal(hallId: Long, day: LocalDate, status: ReservationStatus?): List<AdminReservation> =
            error("Unexpected request")
        override suspend fun createForGuest(email: String, draft: BookingDraft, key: String): Unit = error("Unexpected request")
        override suspend fun move(id: Long, version: Long, tableId: Long, start: Instant) {
            touched.add(id to version)
        }
        override suspend fun changeStatus(id: Long, version: Long, status: ReservationStatus) {
            touched.add(id to version)
        }
    }

    @Test
    fun moveRejectsATableWithoutEnoughSeatsAndKeepsTheVersionOnValidRequest() = runBlocking {
        val move = MoveReservationUseCase(repository)
        val rejected = runCatching { move(entry, BookingTable(2, 2), start, now) }
        assertEquals(BookingError.INVALID_REQUEST, (rejected.exceptionOrNull() as? BookingException)?.error)
        move(entry, BookingTable(2, 4), start, now)
        assertEquals(listOf(123L to 5L), touched)
    }

    @Test
    fun statusChangeRejectsSkippingSeatingAndKeepsTheVersionOnValidRequest() = runBlocking {
        val change = ChangeReservationStatusUseCase(repository)
        val rejected = runCatching { change(entry, ReservationStatus.COMPLETED) }
        assertEquals(BookingError.STALE_RESERVATION, (rejected.exceptionOrNull() as? BookingException)?.error)
        change(entry, ReservationStatus.SEATED)
        assertEquals(listOf(123L to 5L), touched)
    }
}
