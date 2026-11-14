package com.example.tablebook.domain.booking

import java.time.Instant

interface BookingRepository {
    suspend fun createReservation(
        draft: BookingDraft,
        idempotencyKey: String,
    ): CreateReservationResult
}

data class ConfirmedReservation(
    val id: Long,
    val tableId: Long,
    val guestCount: Int,
    val startsAt: Instant,
    val endsAt: Instant,
    val version: Long,
)

sealed interface CreateReservationResult {
    data class Confirmed(val reservation: ConfirmedReservation) : CreateReservationResult

    enum class Failure : CreateReservationResult {
        TABLE_UNAVAILABLE,
        INVALID_TABLE,
        INVALID_GUEST_COUNT,
        INVALID_START_TIME,
        INVALID_IDEMPOTENCY_KEY,
        INVALID_REQUEST,
        UNAUTHENTICATED,
        IDEMPOTENCY_KEY_REUSED,
        TEMPORARILY_UNAVAILABLE,
    }
}
