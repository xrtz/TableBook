package com.example.tablebook.domain.booking

import java.time.Instant
import java.util.Locale

class CreateReservationForGuestUseCase(private val repository: AdminBookingRepository) {
    suspend operator fun invoke(
        email: String,
        draft: BookingDraft,
        idempotencyKey: String,
        now: Instant,
        retry: Boolean,
    ) {
        val guestEmail = email.trim().lowercase(Locale.ROOT)
        if (guestEmail.isBlank()) throw BookingException(BookingError.INVALID_REQUEST)
        if (!retry) draft.validateForAdmin(now)
        repository.createForGuest(guestEmail, draft, idempotencyKey)
    }
}

class MoveReservationUseCase(private val repository: AdminBookingRepository) {
    suspend operator fun invoke(entry: AdminReservation, table: BookingTable, startsAt: Instant, now: Instant) {
        if (entry.status != ReservationStatus.CONFIRMED) throw BookingException(BookingError.STALE_RESERVATION)
        val reservation = entry.reservation
        BookingDraft(table, reservation.guestCount, startsAt).validateForAdmin(now)
        repository.move(reservation.id, reservation.version, table.id, startsAt)
    }
}

class ChangeReservationStatusUseCase(private val repository: AdminBookingRepository) {
    suspend operator fun invoke(entry: AdminReservation, target: ReservationStatus) {
        val allowed = when (entry.status) {
            ReservationStatus.CONFIRMED -> target == ReservationStatus.SEATED || target == ReservationStatus.CANCELLED
            ReservationStatus.SEATED -> target == ReservationStatus.COMPLETED
            else -> false
        }
        if (!allowed) throw BookingException(BookingError.STALE_RESERVATION)
        repository.changeStatus(entry.reservation.id, entry.reservation.version, target)
    }
}

private fun BookingDraft.validateForAdmin(now: Instant) {
    when (validate(now)) {
        BookingValidation.Invalid.START_TIME -> throw BookingException(BookingError.INVALID_START)
        BookingValidation.Invalid.TABLE, BookingValidation.Invalid.GUEST_COUNT ->
            throw BookingException(BookingError.INVALID_REQUEST)
        is BookingValidation.Valid -> Unit
    }
}
