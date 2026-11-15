package com.example.tablebook.feature.booking

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.tablebook.domain.booking.AdminBookingRepository
import com.example.tablebook.domain.booking.AdminReservation
import com.example.tablebook.domain.booking.BookingDraft
import com.example.tablebook.domain.booking.BookingError
import com.example.tablebook.domain.booking.BookingException
import com.example.tablebook.domain.booking.FloorPlan
import com.example.tablebook.domain.booking.Hall
import com.example.tablebook.domain.booking.ReservationStatus
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AdminState(
    val isAdmin: Boolean? = null,
    val halls: List<Hall> = emptyList(),
    val hall: Hall? = null,
    val plan: FloorPlan? = null,
    val day: LocalDate? = null,
    val status: ReservationStatus? = null,
    val journal: List<AdminReservation> = emptyList(),
    val tableId: Long? = null,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val error: BookingError? = null,
)

class AdminViewModel(private val repository: AdminBookingRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(AdminState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private val createKeys = mutableMapOf<Pair<String, BookingDraft>, String>()

    fun reset() {
        job?.cancel()
        createKeys.clear()
        mutableState.value = AdminState()
    }

    fun refresh() {
        if (!mutableState.value.saving) load()
    }

    fun chooseHall(hall: Hall) = update { it.copy(hall = hall, plan = null, tableId = null, journal = emptyList()) }
    fun chooseDay(day: LocalDate) = update { it.copy(day = day, journal = emptyList()) }
    fun chooseStatus(status: ReservationStatus?) = update { it.copy(status = status, journal = emptyList()) }
    fun chooseTable(id: Long?) {
        if (!mutableState.value.saving) mutableState.update { it.copy(tableId = id) }
    }

    private fun update(transform: (AdminState) -> AdminState) {
        if (mutableState.value.saving) return
        mutableState.update(transform)
        load()
    }

    private fun load(keepError: Boolean = false) {
        job?.cancel()
        val previous = mutableState.value
        mutableState.update { it.copy(loading = true, error = if (keepError) it.error else null) }
        job = viewModelScope.launch {
            try {
                val admin = repository.accountIsAdmin()
                ensureActive()
                if (!admin) {
                    mutableState.value = AdminState(isAdmin = false)
                    return@launch
                }
                val halls = previous.halls.ifEmpty { repository.halls() }
                val hall = previous.hall ?: halls.firstOrNull()
                val plan = hall?.let { repository.floorPlan(it.id) }
                val day = previous.day ?: plan?.serverTime?.atZone(hall!!.zone)?.toLocalDate()
                val journal = if (hall != null && day != null) repository.journal(hall.id, day, previous.status) else emptyList()
                ensureActive()
                mutableState.update { it.copy(isAdmin = true, halls = halls, hall = hall, plan = plan, day = day,
                    journal = journal, loading = false) }
            } catch (error: BookingException) {
                ensureActive()
                mutableState.update { it.copy(loading = false, error = error.error) }
            }
        }
    }

    fun status(entry: AdminReservation, target: ReservationStatus) = mutate {
        repository.changeStatus(entry.reservation.id, entry.reservation.version, target)
    }

    fun schedule(entry: AdminReservation?, email: String, tableId: Long, guests: Int?, local: String) {
        val snapshot = mutableState.value
        val hall = snapshot.hall ?: return
        val table = snapshot.plan?.tables?.firstOrNull { it.table.id == tableId }?.table ?: return
        val time = runCatching { LocalDateTime.parse(local).atZone(hall.zone).toInstant() }.getOrNull()
        if (time == null || (entry == null && (guests == null || guests < 1 || email.isBlank()))) {
            mutableState.update { it.copy(error = BookingError.INVALID_REQUEST) }
            return
        }
        mutate {
            if (entry != null) {
                repository.move(entry.reservation.id, entry.reservation.version, tableId, time)
            } else {
                val draft = BookingDraft(table, requireNotNull(guests), time)
                val request = email.trim() to draft
                val key = createKeys.getOrPut(request) { UUID.randomUUID().toString() }
                repository.createForGuest(request.first, draft, key)
                createKeys.remove(request)
            }
        }
    }

    private fun mutate(action: suspend () -> Unit) {
        if (mutableState.value.saving || mutableState.value.loading) return
        mutableState.update { it.copy(saving = true, error = null) }
        job = viewModelScope.launch {
            try {
                action()
            } catch (error: BookingException) {
                ensureActive()
                mutableState.update { it.copy(error = error.error) }
            }
            ensureActive()
            mutableState.update { it.copy(saving = false) }
            load(keepError = true)
        }
    }
}
