package com.example.tablebook.domain.booking

import java.time.Instant

class CreateReservationUseCase(private val repository: BookingRepository) {
    suspend operator fun invoke(
        draft: BookingDraft,
        idempotencyKey: String,
        now: Instant,
        retry: Boolean,
    ): CreateReservationResult {
        // A previous request may have committed even if the current start time is in the past.
        if (!retry) {
            when (draft.validate(now)) {
                BookingValidation.Invalid.TABLE -> return CreateReservationResult.Failure.INVALID_TABLE
                BookingValidation.Invalid.GUEST_COUNT -> return CreateReservationResult.Failure.INVALID_GUEST_COUNT
                BookingValidation.Invalid.START_TIME -> return CreateReservationResult.Failure.INVALID_START_TIME
                is BookingValidation.Valid -> Unit
            }
        }
        return repository.createReservation(draft, idempotencyKey)
    }
}
