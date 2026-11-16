package com.example.tablebook.domain.booking

import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LoadBookingPlanUseCaseTest {
    private val hall = Hall(1, "Hall", ZoneId.of("Asia/Kathmandu"))
    private val plan = FloorPlan(100, 100, emptyList(), Instant.parse("2026-11-16T15:00:00Z"))
    private var requests = 0
    private var availabilityError: BookingError? = null
    private val repository = object : GuestBookingRepository {
        override suspend fun halls(): List<Hall> = error("Unexpected request")
        override suspend fun floorPlan(hallId: Long): FloorPlan {
            assertEquals(hall.id, hallId)
            requests++
            return plan
        }
        override suspend fun availability(hallId: Long, startsAt: Instant, guestCount: Int): Set<Long> {
            assertEquals(hall.id, hallId)
            assertEquals(3, guestCount)
            availabilityError?.let { throw BookingException(it) }
            assertEquals(Instant.parse("2026-11-16T15:15:00Z"), startsAt)
            return setOf(2)
        }
        override suspend fun myReservations(): List<OwnedReservation> = error("Unexpected request")
        override suspend fun cancelReservation(id: Long): Unit = error("Unexpected request")
        override suspend fun createReservation(
            draft: BookingDraft,
            idempotencyKey: String,
        ): CreateReservationResult = error("Unexpected request")
    }
    private val load = LoadBookingPlanUseCase(repository)

    @Test
    fun nextHourUsesServerTimeInTheHallTimeZone() = runBlocking {
        val result = load(hall, cachedPlan = null, startsAt = null, guestCount = 3)
        assertEquals(1, requests)
        assertEquals(Instant.parse("2026-11-16T15:15:00Z"), result.startsAt)
        assertEquals(setOf(2L), result.availableTables)
    }

    @Test
    fun availabilityFailureKeepsThePlanAndTheSelectedTime() = runBlocking {
        availabilityError = BookingError.INVALID_START
        val start = plan.serverTime.minusSeconds(3600)
        val result = load(hall, cachedPlan = plan, startsAt = start, guestCount = 3)
        assertSame(plan, result.plan)
        assertEquals(start, result.startsAt)
        assertEquals(BookingError.INVALID_START, result.error)
        assertEquals(emptySet<Long>(), result.availableTables)
        assertEquals(0, requests)
    }
}
