package com.example.tablebook.feature.booking

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.tablebook.domain.booking.BookingDraft
import com.example.tablebook.domain.booking.BookingError
import com.example.tablebook.domain.booking.BookingException
import com.example.tablebook.domain.booking.ConfirmedReservation
import com.example.tablebook.domain.booking.CreateReservationResult
import com.example.tablebook.domain.booking.FloorPlan
import com.example.tablebook.domain.booking.FloorTable
import com.example.tablebook.domain.booking.GuestBookingRepository
import com.example.tablebook.domain.booking.Hall
import com.example.tablebook.domain.booking.OwnedReservation
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BookingState(
    val halls: List<Hall> = emptyList(),
    val hall: Hall? = null,
    val plan: FloorPlan? = null,
    val start: Instant = Instant.EPOCH,
    val guests: Int = 2,
    val available: Set<Long> = emptySet(),
    val selected: FloorTable? = null,
    val confirmed: ConfirmedReservation? = null,
    val bookingError: BookingError? = null,
    val creationFailure: CreateReservationResult.Failure? = null,
    val showReservations: Boolean = false,
    val reservations: List<OwnedReservation> = emptyList(),
    val loading: Boolean = false,
    val working: Boolean = false,
)

private data class Submission(val tableId: Long, val startsAt: Instant, val guestCount: Int)

class BookingViewModel(private val repository: GuestBookingRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(BookingState())
    val state = mutableState.asStateFlow()
    private val submissions = mutableMapOf<Submission, String>()
    private var readJob: Job? = null
    private var writeJob: Job? = null

    fun activate() {
        if (mutableState.value == BookingState()) refresh()
    }

    fun reset() {
        readJob?.cancel()
        writeJob?.cancel()
        submissions.clear()
        mutableState.value = BookingState()
    }

    fun chooseHall(hall: Hall) {
        val current = mutableState.value
        if (current.working || current.hall == hall) return
        mutableState.value = current.copy(hall = hall, plan = null, selected = null, confirmed = null, available = emptySet())
        refresh()
    }

    fun chooseTime(local: LocalDateTime) {
        val current = mutableState.value
        if (current.working) return
        updateFilters(start = local.atZone(current.hall?.zone ?: ZoneId.systemDefault()).toInstant())
    }

    fun chooseGuests(guests: Int) {
        if (guests !in 1..99 || mutableState.value.working) return
        updateFilters(guests = guests)
    }

    private fun updateFilters(
        start: Instant = mutableState.value.start,
        guests: Int = mutableState.value.guests,
    ) {
        mutableState.update { it.copy(start = start, guests = guests, selected = null, confirmed = null) }
        refresh()
    }

    fun select(table: FloorTable?) {
        mutableState.update { current ->
            if (current.working || (table != null && (current.loading || table.table.id !in current.available))) current
            else current.copy(selected = table, confirmed = null, bookingError = null, creationFailure = null)
        }
    }

    fun showReservations(show: Boolean) {
        if (mutableState.value.working) return
        mutableState.update { it.copy(showReservations = show, selected = null, confirmed = null) }
        refresh()
    }

    fun refresh() = load(clearErrors = true)

    private fun load(clearErrors: Boolean) {
        readJob?.cancel()
        val snapshot = mutableState.value
        mutableState.update {
            it.copy(
                loading = true,
                bookingError = if (clearErrors) null else it.bookingError,
                creationFailure = if (clearErrors) null else it.creationFailure,
            )
        }
        readJob = viewModelScope.launch {
            try {
                if (snapshot.showReservations) {
                    val reservations = repository.myReservations()
                    ensureActive()
                    mutableState.update { it.copy(reservations = reservations, loading = false) }
                } else {
                    val halls = snapshot.halls.ifEmpty { repository.halls() }
                    ensureActive()
                    val hall = snapshot.hall ?: halls.firstOrNull()
                    if (hall == null) {
                        mutableState.update { it.copy(halls = halls, loading = false) }
                        return@launch
                    }
                    val start = if (snapshot.start == Instant.EPOCH) {
                        Instant.now().truncatedTo(ChronoUnit.HOURS).plus(1, ChronoUnit.HOURS)
                    } else snapshot.start
                    val plan = snapshot.plan ?: repository.floorPlan(hall.id)
                    ensureActive()
                    mutableState.update { it.copy(halls = halls, hall = hall, plan = plan, start = start) }
                    val free = repository.availability(hall.id, start, snapshot.guests)
                    ensureActive()
                    mutableState.update { it.copy(available = free, loading = false) }
                }
            } catch (error: BookingException) {
                ensureActive()
                mutableState.update { it.copy(bookingError = error.error, loading = false, available = emptySet()) }
            }
        }
    }

    fun confirm() {
        val current = mutableState.value
        val table = current.selected ?: return
        if (current.working || current.loading) return
        val draft = BookingDraft(table.table, current.guests, current.start)
        val submission = Submission(table.table.id, draft.startsAt, draft.guestCount)
        if (table.table.id !in current.available && submission !in submissions) return
        val key = submissions.getOrPut(submission) { UUID.randomUUID().toString() }
        mutableState.update { it.copy(working = true, creationFailure = null) }
        writeJob = viewModelScope.launch {
            val result = repository.createReservation(draft, key)
            ensureActive()
            when (result) {
                is CreateReservationResult.Confirmed -> {
                    submissions.remove(submission)
                    mutableState.update { it.copy(confirmed = result.reservation, selected = null, working = false) }
                    load(clearErrors = false)
                }
                is CreateReservationResult.Failure -> {
                    if (result != CreateReservationResult.Failure.TEMPORARILY_UNAVAILABLE) submissions.remove(submission)
                    val conflict = result == CreateReservationResult.Failure.TABLE_UNAVAILABLE
                    mutableState.update {
                        it.copy(creationFailure = result, working = false, selected = if (conflict) null else it.selected)
                    }
                    if (conflict) load(clearErrors = false)
                }
            }
        }
    }

    fun cancelReservation(id: Long) {
        if (mutableState.value.working) return
        mutableState.update { it.copy(working = true, bookingError = null) }
        writeJob = viewModelScope.launch {
            try {
                repository.cancelReservation(id)
            } catch (error: BookingException) {
                ensureActive()
                mutableState.update { it.copy(bookingError = error.error) }
            }
            ensureActive()
            mutableState.update { it.copy(working = false) }
            load(clearErrors = false)
        }
    }
}
