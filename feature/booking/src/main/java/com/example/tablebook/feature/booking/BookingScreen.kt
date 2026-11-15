package com.example.tablebook.feature.booking

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.tablebook.domain.booking.BookingError
import com.example.tablebook.domain.booking.CreateReservationResult
import com.example.tablebook.domain.booking.OwnedReservation
import com.example.tablebook.domain.booking.ReservationStatus
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun BookingScreen(model: BookingViewModel, logoutBusy: Boolean, logoutError: Boolean, onLogout: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val busy = state.working || logoutBusy
    Scaffold { insets ->
        Column(Modifier.fillMaxSize().padding(insets).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.booking_title), style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = onLogout, enabled = !busy) { Text(stringResource(R.string.booking_logout)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!state.showReservations, { model.showReservations(false) }, { Text(stringResource(R.string.booking_floor)) }, enabled = !busy)
                FilterChip(state.showReservations, { model.showReservations(true) }, { Text(stringResource(R.string.booking_mine)) }, enabled = !busy)
            }
            if (state.loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            val error = if (logoutError) R.string.booking_connection
                else state.bookingError?.message() ?: state.creationFailure?.message()
            error?.let {
                Text(stringResource(it), color = MaterialTheme.colorScheme.error)
                val past = !state.showReservations && (state.bookingError == BookingError.INVALID_START ||
                    state.creationFailure == CreateReservationResult.Failure.INVALID_START_TIME)
                TextButton(
                    onClick = if (past) model::chooseNextTime else model::refresh,
                    enabled = !busy && !state.loading,
                ) {
                    Text(stringResource(if (past) R.string.booking_next_time else R.string.booking_refresh))
                }
            }
            if (state.showReservations) {
                Reservations(state, busy, model::cancelReservation, model::refresh)
            } else {
                FloorContent(model, state, busy)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FloorContent(model: BookingViewModel, state: BookingState, busy: Boolean) {
    val hall = state.hall
    val plan = state.plan
    if (hall == null || plan == null) {
        if (!state.loading && state.bookingError == null) Text(stringResource(R.string.booking_no_halls))
        return
    }
    val start = state.start.atZone(hall.zone).toLocalDateTime()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.halls, key = { it.id }) { option ->
                    FilterChip(option.id == hall.id, { model.chooseHall(option) }, { Text(option.name) }, enabled = !busy)
                }
            }
        }
        item {
            BookingFilters(start, state.guests, busy, model::chooseTime, model::chooseGuests)
        }
        item {
            AnimatedVisibility(state.confirmed != null) {
                state.confirmed?.let { reservation ->
                    Card(Modifier.fillMaxWidth()) {
                        val label = plan.tables.firstOrNull { it.table.id == reservation.tableId }?.label
                            ?: reservation.tableId.toString()
                        Text(
                            stringResource(R.string.booking_success, label,
                                timeFormat.format(reservation.startsAt.atZone(hall.zone)),
                                timeFormat.format(reservation.endsAt.atZone(hall.zone))),
                            Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
        }
        item {
            FloorMap(plan, state.available, state.guests, state.selected?.table?.id, !busy && !state.loading, model::select)
        }
        item {
            Text(stringResource(R.string.booking_available_tables), style = MaterialTheme.typography.titleMedium)
            val available = plan.tables.filter { it.table.id in state.available }
            if (available.isEmpty() && !state.loading && state.bookingError == null) {
                Text(stringResource(R.string.booking_no_tables))
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(available, key = { it.table.id }) { table ->
                    FilterChip(
                        selected = table.table.id == state.selected?.table?.id,
                        onClick = { model.select(table) },
                        label = { Text(stringResource(R.string.booking_table_number, table.label)) },
                        enabled = !busy && !state.loading,
                    )
                }
            }
        }
    }
    state.selected?.let { table ->
        ModalBottomSheet(onDismissRequest = { model.select(null) }) {
            Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.booking_table_number, table.label), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.booking_interval, timeFormat.format(start),
                    timeFormat.format(state.start.plusSeconds(7200).atZone(hall.zone))))
                state.creationFailure?.let {
                    Text(stringResource(it.message()), color = MaterialTheme.colorScheme.error)
                }
                Button(
                    onClick = model::confirm,
                    enabled = !busy && !state.loading,
                ) { Text(stringResource(R.string.booking_confirm)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookingFilters(start: LocalDateTime, guests: Int, busy: Boolean, changeTime: (LocalDateTime) -> Unit, changeGuests: (Int) -> Unit) {
    var picker by rememberSaveable { mutableStateOf<Picker?>(null) }
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { picker = Picker.DATE }, enabled = !busy) {
                Text(dateFormat.format(start))
            }
            OutlinedButton(onClick = { picker = Picker.TIME }, enabled = !busy) {
                AnimatedContent(timeFormat.format(start), label = "booking-time") { Text(it) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { changeGuests(guests - 1) }, enabled = !busy && guests > 1) { Text(stringResource(R.string.booking_fewer)) }
            AnimatedContent(guests, label = "booking-guests") {
                Text(pluralStringResource(R.plurals.booking_guests, it, it))
            }
            TextButton(onClick = { changeGuests(guests + 1) }, enabled = !busy && guests < 99) { Text(stringResource(R.string.booking_more)) }
        }
    }
    when (picker) {
        Picker.DATE -> {
            val calendar = rememberDatePickerState(
                initialSelectedDateMillis = start.toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
            )
            DatePickerDialog(
                onDismissRequest = { picker = null },
                confirmButton = {
                    TextButton(onClick = {
                        calendar.selectedDateMillis?.let {
                            changeTime(LocalDate.ofEpochDay(Math.floorDiv(it, 86_400_000L)).atTime(start.toLocalTime()))
                        }
                        picker = null
                    }) { Text(stringResource(R.string.booking_apply)) }
                },
            ) { DatePicker(calendar) }
        }
        Picker.TIME -> {
            val clock = rememberTimePickerState(start.hour, start.minute, is24Hour = true)
            AlertDialog(
                onDismissRequest = { picker = null },
                title = { Text(stringResource(R.string.booking_pick_time)) },
                text = { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TimePicker(clock) } },
                confirmButton = {
                    TextButton(onClick = {
                        changeTime(start.toLocalDate().atTime(LocalTime.of(clock.hour, clock.minute)))
                        picker = null
                    }) { Text(stringResource(R.string.booking_apply)) }
                },
            )
        }
        null -> Unit
    }
}

@Composable
private fun Reservations(state: BookingState, busy: Boolean, cancel: (Long) -> Unit, refresh: () -> Unit) {
    var pending by remember(state.showReservations) { mutableStateOf<OwnedReservation?>(null) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            TextButton(onClick = refresh, enabled = !busy && !state.loading) { Text(stringResource(R.string.booking_refresh)) }
            if (state.reservations.isEmpty() && !state.loading && state.bookingError == null) {
                Text(stringResource(R.string.booking_no_reservations))
            }
        }
        items(state.reservations, key = { it.reservation.id }) { owned ->
            val reservation = owned.reservation
            val from = reservation.startsAt.atZone(owned.zone)
            val to = reservation.endsAt.atZone(owned.zone)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(owned.hallName, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.booking_table_number, owned.tableLabel))
                    Text(dateFormat.format(from))
                    Text(stringResource(R.string.booking_interval, timeFormat.format(from), timeFormat.format(to)))
                    Text(pluralStringResource(R.plurals.booking_guests, reservation.guestCount, reservation.guestCount))
                    Text(stringResource(owned.status.message()))
                    if (owned.status == ReservationStatus.CONFIRMED && reservation.startsAt.isAfter(Instant.now())) {
                        TextButton(onClick = { pending = owned }, enabled = !busy) { Text(stringResource(R.string.booking_cancel)) }
                    }
                }
            }
        }
    }
    pending?.let { owned ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.booking_cancel_question)) },
            text = { Text(stringResource(R.string.booking_table_number, owned.tableLabel)) },
            confirmButton = {
                TextButton(onClick = { pending = null; cancel(owned.reservation.id) }) {
                    Text(stringResource(R.string.booking_cancel))
                }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.booking_keep)) } },
        )
    }
}

private enum class Picker { DATE, TIME }
private val dateFormat get() = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
private val timeFormat get() = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

fun BookingError.message(): Int = when (this) {
    BookingError.INVALID_START -> R.string.booking_invalid_start
    BookingError.CANNOT_CANCEL -> R.string.booking_cannot_cancel
    BookingError.NOT_FOUND -> R.string.booking_not_found
    BookingError.SESSION_EXPIRED -> R.string.booking_session_expired
    BookingError.CONNECTION -> R.string.booking_connection
    BookingError.FORBIDDEN -> R.string.admin_forbidden
    BookingError.CONFLICT -> R.string.booking_conflict
    BookingError.STALE_RESERVATION -> R.string.admin_stale
    BookingError.GUEST_NOT_FOUND -> R.string.admin_guest_missing
    BookingError.INVALID_REQUEST -> R.string.booking_invalid_request
}

private fun CreateReservationResult.Failure.message(): Int = when (this) {
    CreateReservationResult.Failure.TABLE_UNAVAILABLE -> R.string.booking_conflict
    CreateReservationResult.Failure.INVALID_START_TIME -> R.string.booking_invalid_start
    CreateReservationResult.Failure.UNAUTHENTICATED -> R.string.booking_session_expired
    CreateReservationResult.Failure.INVALID_GUEST_COUNT -> R.string.booking_wrong_capacity
    CreateReservationResult.Failure.TEMPORARILY_UNAVAILABLE -> R.string.booking_connection
    else -> R.string.booking_invalid_request
}

fun ReservationStatus.message(): Int = when (this) {
    ReservationStatus.CONFIRMED -> R.string.booking_status_confirmed
    ReservationStatus.SEATED -> R.string.booking_status_seated
    ReservationStatus.COMPLETED -> R.string.booking_status_completed
    ReservationStatus.CANCELLED -> R.string.booking_status_cancelled
}
