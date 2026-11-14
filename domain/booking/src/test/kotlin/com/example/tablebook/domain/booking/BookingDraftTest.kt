package com.example.tablebook.domain.booking

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class BookingDraftTest {
    private val now = Instant.parse("2026-11-14T17:00:00Z")
    private val draft = BookingDraft(BookingTable(id = 3, capacity = 4), guestCount = 4, startsAt = now)

    @Test
    fun `start at the current time ends two hours later`() {
        assertEquals(
            BookingValidation.Valid(Instant.parse("2026-11-14T19:00:00Z")),
            draft.validate(now),
        )
    }

    @Test
    fun `table must have an ID and positive capacity`() {
        assertEquals(BookingValidation.Invalid.TABLE, draft.copy(table = BookingTable(0, 4)).validate(now))
        assertEquals(BookingValidation.Invalid.TABLE, draft.copy(table = BookingTable(3, 0)).validate(now))
    }

    @Test
    fun `guest count must be positive and fit the table`() {
        assertEquals(BookingValidation.Invalid.GUEST_COUNT, draft.copy(guestCount = 0).validate(now))
        assertEquals(BookingValidation.Invalid.GUEST_COUNT, draft.copy(guestCount = 5).validate(now))
    }

    @Test
    fun `start cannot be in the past or overflow the supported interval`() {
        assertEquals(
            BookingValidation.Invalid.START_TIME,
            draft.copy(startsAt = now.minusSeconds(1)).validate(now),
        )
        assertEquals(
            BookingValidation.Invalid.START_TIME,
            draft.copy(startsAt = Instant.MAX).validate(now),
        )
    }
}
