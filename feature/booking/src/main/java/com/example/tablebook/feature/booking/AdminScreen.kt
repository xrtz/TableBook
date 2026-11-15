package com.example.tablebook.feature.booking

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.tablebook.domain.booking.AdminReservation
import com.example.tablebook.domain.booking.FloorPlan
import com.example.tablebook.domain.booking.ReservationStatus
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private sealed interface Edit {
    data object Create : Edit
    data class Move(val entry: AdminReservation) : Edit
}

@Composable
fun AdminScreen(model: AdminViewModel, logoutBusy: Boolean, logoutError: Boolean, onLogout: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val enabled = !state.loading && !state.saving && !logoutBusy
    var edit by remember { mutableStateOf<Edit?>(null) }
    var cancellation by remember { mutableStateOf<AdminReservation?>(null) }
    Scaffold { padding ->
        Column(Modifier.padding(padding).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.admin_title), style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = onLogout, enabled = enabled) { Text(stringResource(R.string.booking_logout)) }
            }
            if (!enabled) LinearProgressIndicator(Modifier.fillMaxWidth())
            val error = if (logoutError) R.string.booking_connection else state.error?.message()
            error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = model::refresh, enabled = enabled) { Text(stringResource(R.string.booking_refresh)) }
            val hall = state.hall
            val day = state.day
            val plan = state.plan
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.halls, key = { it.id }) { option ->
                            FilterChip(hall == option, { model.chooseHall(option) }, { Text(option.name) }, enabled = enabled)
                        }
                    }
                }
                if (day != null) item { JournalDate(day, enabled, model::chooseDay) }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            FilterChip(state.status == null, { model.chooseStatus(null) },
                                { Text(stringResource(R.string.admin_all_statuses)) }, enabled = enabled)
                        }
                        items(ReservationStatus.entries) { status ->
                            FilterChip(state.status == status, { model.chooseStatus(status) },
                                { Text(stringResource(status.message())) }, enabled = enabled)
                        }
                    }
                }
                if (plan != null) item {
                    AdminFloorMap(plan, state.tableId, enabled) { model.chooseTable(it.table.id) }
                    Text(stringResource(R.string.admin_map_legend))
                    Row {
                        TextButton(onClick = { model.chooseTable(null) }, enabled = enabled && state.tableId != null) {
                            Text(stringResource(R.string.admin_all_tables))
                        }
                        Button(onClick = { edit = Edit.Create }, enabled = enabled && plan.tables.isNotEmpty()) {
                            Text(stringResource(R.string.admin_new))
                        }
                    }
                }
                val entries = state.journal.filter { state.tableId == null || state.tableId == it.reservation.tableId }
                if (entries.isEmpty() && enabled) item { Text(stringResource(R.string.admin_empty)) }
                items(entries, key = { it.reservation.id }) { entry ->
                    val zone = requireNotNull(hall).zone
                    val start = entry.reservation.startsAt.atZone(zone).format(displayDateTime)
                    val end = entry.reservation.endsAt.atZone(zone).format(displayDateTime)
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.booking_table_number, entry.tableLabel),
                                style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.booking_interval, start, end))
                            Text(stringResource(entry.status.message()))
                            if (entry.guestEmail.isNotBlank()) Text(entry.guestEmail)
                            Text(stringResource(R.string.admin_guest_count, entry.reservation.guestCount))
                            when (entry.status) {
                                ReservationStatus.CONFIRMED -> {
                                    TextButton(onClick = { edit = Edit.Move(entry) }, enabled = enabled) {
                                        Text(stringResource(R.string.admin_move))
                                    }
                                    TextButton(onClick = { model.status(entry, ReservationStatus.SEATED) }, enabled = enabled) {
                                        Text(stringResource(R.string.admin_seat))
                                    }
                                    TextButton(onClick = { cancellation = entry }, enabled = enabled) {
                                        Text(stringResource(R.string.booking_cancel))
                                    }
                                }
                                ReservationStatus.SEATED -> TextButton(
                                    onClick = { model.status(entry, ReservationStatus.COMPLETED) }, enabled = enabled,
                                ) { Text(stringResource(R.string.admin_complete)) }
                                else -> Unit
                            }
                        }
                    }
                }
            }
            if (plan != null && hall != null && day != null) edit?.let { current ->
                val entry = (current as? Edit.Move)?.entry
                val now = plan.serverTime.atZone(hall.zone)
                val default = if (day == now.toLocalDate()) now.truncatedTo(ChronoUnit.HOURS).plusHours(1).toLocalDateTime()
                    else day.atTime(12, 0)
                val start = entry?.reservation?.startsAt?.atZone(hall.zone)?.toLocalDateTime() ?: default
                ScheduleDialog(plan, entry, state.tableId, start.format(inputDateTime), { edit = null }) { email, table, guests, time ->
                    edit = null
                    model.schedule(entry, email, table, guests, time)
                }
            }
            cancellation?.let { entry ->
                AlertDialog(
                    onDismissRequest = { cancellation = null },
                    title = { Text(stringResource(R.string.booking_cancel_question)) },
                    confirmButton = {
                        TextButton(onClick = {
                            cancellation = null
                            model.status(entry, ReservationStatus.CANCELLED)
                        }) { Text(stringResource(R.string.booking_cancel)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { cancellation = null }) { Text(stringResource(R.string.booking_keep)) }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JournalDate(day: LocalDate, enabled: Boolean, select: (LocalDate) -> Unit) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }, enabled = enabled) {
        Text(stringResource(R.string.admin_day, day.toString()))
    }
    if (open) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = day.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(onDismissRequest = { open = false }, confirmButton = {
            TextButton(onClick = {
                picker.selectedDateMillis?.let { select(LocalDate.ofEpochDay(Math.floorDiv(it, 86_400_000L))) }
                open = false
            }) { Text(stringResource(R.string.booking_apply)) }
        }) { DatePicker(picker) }
    }
}

@Composable
private fun ScheduleDialog(
    plan: FloorPlan, entry: AdminReservation?, tableFilter: Long?, initial: String, dismiss: () -> Unit,
    submit: (String, Long, Int?, String) -> Unit,
) {
    var table by remember { mutableStateOf(entry?.reservation?.tableId ?: tableFilter ?: plan.tables.first().table.id) }
    var email by remember { mutableStateOf("") }
    var guests by remember { mutableStateOf("2") }
    var time by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(if (entry == null) R.string.admin_new else R.string.admin_move)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (entry == null) {
                    OutlinedTextField(email, { email = it }, label = { Text(stringResource(R.string.admin_guest_email)) }, singleLine = true)
                    OutlinedTextField(guests, { guests = it }, label = { Text(stringResource(R.string.admin_guests)) }, singleLine = true)
                }
                OutlinedTextField(time, { time = it }, label = { Text(stringResource(R.string.admin_datetime)) }, singleLine = true)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(plan.tables, key = { it.table.id }) { tile ->
                        FilterChip(table == tile.table.id, { table = tile.table.id },
                            { Text(stringResource(R.string.booking_table_number, tile.label)) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { submit(email, table, guests.toIntOrNull(), time) }) {
                Text(stringResource(R.string.booking_apply))
            }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.admin_close)) } },
    )
}

private val inputDateTime = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm")
private val displayDateTime = DateTimeFormatter.ofPattern("dd.MM HH:mm")
