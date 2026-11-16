package com.example.tablebook.booking

import com.example.tablebook.domain.booking.ConfirmedReservation
import com.example.tablebook.domain.booking.OwnedReservation
import com.example.tablebook.domain.booking.ReservationStatus
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookingReminderTest {
    private val start = Instant.parse("2026-11-15T18:00:00Z")
    private val booking = OwnedReservation(
        ConfirmedReservation(3, 1, 2, start, start.plusSeconds(7200), 1),
        "01", "Hall", ZoneOffset.UTC, ReservationStatus.CONFIRMED,
    )

    @Test
    fun schedulesTwoHoursBeforeTheVisit() {
        val now = start.minusSeconds(3 * 3600)
        assertEquals(TimeUnit.HOURS.toMillis(1), reminderDelayMillis(booking, now))
        val moved = booking.copy(reservation = booking.reservation.copy(
            startsAt = start.plusSeconds(3600), endsAt = start.plusSeconds(3 * 3600), version = 2,
        ))
        assertEquals(TimeUnit.HOURS.toMillis(2), reminderDelayMillis(moved, now))
    }

    @Test
    fun onlyFutureConfirmedReservationsAreEligible() {
        val now = start.minusSeconds(60)
        assertEquals(0L, reminderDelayMillis(booking, now))
        assertNull(reminderDelayMillis(booking, start))
        assertNull(reminderDelayMillis(booking.copy(status = ReservationStatus.CANCELLED), now))
    }
}
