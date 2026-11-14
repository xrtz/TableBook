package com.example.tablebook.domain.booking

import java.time.Duration
import java.time.Instant

data class BookingTable(val id: Long, val capacity: Int)

data class BookingDraft(
    val table: BookingTable,
    val guestCount: Int,
    val startsAt: Instant,
)

sealed interface BookingValidation {
    data class Valid(val endsAt: Instant) : BookingValidation

    enum class Invalid : BookingValidation {
        TABLE,
        GUEST_COUNT,
        START_TIME,
    }
}

private val bookingDuration = Duration.ofHours(2)
private val earliestStartExclusive = Instant.parse("0001-01-01T00:00:00Z")
private val latestStart = Instant.parse("9999-12-31T23:59:59.999999999Z")
    .minus(bookingDuration)

fun BookingDraft.validate(now: Instant): BookingValidation = when {
    table.id <= 0 || table.capacity <= 0 -> BookingValidation.Invalid.TABLE
    guestCount !in 1..table.capacity -> BookingValidation.Invalid.GUEST_COUNT
    startsAt < now || startsAt <= earliestStartExclusive || startsAt > latestStart ->
        BookingValidation.Invalid.START_TIME
    else -> BookingValidation.Valid(endsAt = startsAt.plus(bookingDuration))
}
