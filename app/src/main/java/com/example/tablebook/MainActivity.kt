package com.example.tablebook

import android.app.Application
import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.tablebook.auth.AuthScreen
import com.example.tablebook.auth.AuthViewModel
import com.example.tablebook.auth.HttpAuthApi
import com.example.tablebook.auth.KeystoreSessionStorage
import com.example.tablebook.auth.SessionRepository
import com.example.tablebook.auth.SessionState
import com.example.tablebook.booking.HttpBookingRepository
import com.example.tablebook.booking.BookingEventsService
import com.example.tablebook.booking.BookingReminderScheduler
import com.example.tablebook.domain.booking.ChangeReservationStatusUseCase
import com.example.tablebook.domain.booking.CreateReservationForGuestUseCase
import com.example.tablebook.domain.booking.CreateReservationUseCase
import com.example.tablebook.domain.booking.LoadBookingPlanUseCase
import com.example.tablebook.domain.booking.MoveReservationUseCase
import com.example.tablebook.feature.booking.BookingScreen
import com.example.tablebook.feature.booking.AdminScreen
import com.example.tablebook.feature.booking.AdminViewModel
import com.example.tablebook.feature.booking.BookingViewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.tablebook.ui.theme.TableBookTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class TableBookApplication : Application() {
    internal val reminders by lazy { BookingReminderScheduler(this) }
    internal val sessions by lazy {
        SessionRepository(HttpAuthApi(BuildConfig.API_URL), KeystoreSessionStorage(this)) { reminders.stop(it) }
    }
    internal val booking by lazy {
        HttpBookingRepository(BuildConfig.API_URL, sessions) { scope -> reminders.sync(scope) }
    }
}

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) lifecycleScope.launch {
            val app = application as TableBookApplication
            app.sessions.access()?.scope?.let { scope ->
                app.sessions.inScope(scope) { app.reminders.sync(scope) }
            }
        }
    }
    private val adminModel by viewModels<AdminViewModel> {
        viewModelFactory {
            initializer {
                val booking = (application as TableBookApplication).booking
                AdminViewModel(
                    booking,
                    CreateReservationForGuestUseCase(booking),
                    MoveReservationUseCase(booking),
                    ChangeReservationStatusUseCase(booking),
                )
            }
        }
    }
    private val bookingModel by viewModels<BookingViewModel> {
        viewModelFactory {
            initializer {
                val booking = (application as TableBookApplication).booking
                BookingViewModel(booking, LoadBookingPlanUseCase(booking), CreateReservationUseCase(booking))
            }
        }
    }
    private val model by viewModels<AuthViewModel> {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val sessions = (application as TableBookApplication).sessions
                @Suppress("UNCHECKED_CAST")
                return AuthViewModel(sessions) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        observeBookingEvents()
        setContent {
            val session by model.session.collectAsStateWithLifecycle()
            val operation by model.operation.collectAsStateWithLifecycle()
            val admin by adminModel.state.collectAsStateWithLifecycle()
            LaunchedEffect(session) {
                if (session == SessionState.SIGNED_IN) adminModel.refresh()
                else {
                    adminModel.reset()
                    bookingModel.reset()
                }
            }
            LaunchedEffect(session, admin.isAdmin) {
                if (session == SessionState.SIGNED_IN && admin.isAdmin == false) bookingModel.activate()
            }
            TableBookTheme {
                if (session == SessionState.SIGNED_IN) {
                    when (admin.isAdmin) {
                        true -> AdminScreen(adminModel, operation.busy, operation.error != null, model::logout)
                        false -> BookingScreen(bookingModel, operation.busy, operation.error != null, model::logout)
                        null -> Scaffold { padding ->
                            Column(Modifier.padding(padding)) {
                                if (admin.error == null) {
                                    Text(stringResource(R.string.account_checking))
                                } else {
                                    Text(stringResource(R.string.auth_connection_error))
                                    TextButton(onClick = adminModel::refresh) { Text(stringResource(R.string.auth_retry)) }
                                }
                                TextButton(onClick = model::logout, enabled = !operation.busy) {
                                    Text(stringResource(R.string.auth_logout))
                                }
                            }
                        }
                    }
                } else {
                    AuthScreen(session, operation, model::signIn, model::logout, model::restore, model::clearError)
                }
            }
        }
    }

    private fun observeBookingEvents() {
        val app = application as TableBookApplication
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val bound = MutableStateFlow<BookingEventsService.Connection?>(null)
                val connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, service: IBinder) {
                        bound.value = service as BookingEventsService.Connection
                    }

                    override fun onServiceDisconnected(name: ComponentName) {
                        bound.value = null
                    }
                }
                if (!bindService(Intent(this@MainActivity, BookingEventsService::class.java), connection, Context.BIND_AUTO_CREATE)) {
                    return@repeatOnLifecycle
                }
                try {
                    val destination = combine(model.session, adminModel.state, bookingModel.state) { session, admin, guest ->
                        if (session != SessionState.SIGNED_IN) null
                        else admin.isAdmin?.let { isAdmin ->
                            EventDestination(isAdmin, if (isAdmin) admin.hall?.id else if (guest.showReservations) null else guest.hall?.id)
                        }
                    }.distinctUntilChanged()
                    combine(destination, bound) { target, service -> target to service }.collectLatest { (target, service) ->
                        if (target == null) {
                            service?.disconnect()
                            return@collectLatest
                        }
                        val scope = app.sessions.access()?.scope ?: return@collectLatest
                        if (!target.admin) {
                            app.sessions.inScope(scope) {
                                app.reminders.start(scope)
                                askNotificationPermission()
                            }
                        }
                        if (service == null) return@collectLatest
                        service.subscribe(target.hallId)
                        service.updates.collect {
                            if (target.admin) {
                                adminModel.onBookingChanged()
                            } else {
                                bookingModel.onBookingChanged()
                                app.sessions.inScope(scope) { app.reminders.sync(scope) }
                            }
                        }
                    }
                } finally {
                    unbindService(connection)
                }
            }
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val preferences = getPreferences(Context.MODE_PRIVATE)
        if (preferences.getBoolean("booking_notification_prompted", false)) return
        preferences.edit().putBoolean("booking_notification_prompted", true).apply()
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

private data class EventDestination(val admin: Boolean, val hallId: Long?)
