package com.example.tablebook

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.tablebook.auth.AuthScreen
import com.example.tablebook.auth.AuthViewModel
import com.example.tablebook.auth.HttpAuthApi
import com.example.tablebook.auth.KeystoreSessionStorage
import com.example.tablebook.auth.SessionRepository
import com.example.tablebook.auth.SessionState
import com.example.tablebook.booking.HttpBookingRepository
import com.example.tablebook.feature.booking.BookingScreen
import com.example.tablebook.feature.booking.BookingViewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.tablebook.ui.theme.TableBookTheme

class TableBookApplication : Application() {
    internal val sessions by lazy {
        SessionRepository(HttpAuthApi(BuildConfig.API_URL), KeystoreSessionStorage(this))
    }
    internal val booking by lazy { HttpBookingRepository(BuildConfig.API_URL, sessions) }
}

class MainActivity : ComponentActivity() {
    private val bookingModel by viewModels<BookingViewModel> {
        viewModelFactory {
            initializer { BookingViewModel((application as TableBookApplication).booking) }
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
        setContent {
            val session by model.session.collectAsStateWithLifecycle()
            val operation by model.operation.collectAsStateWithLifecycle()
            LaunchedEffect(session) {
                if (session == SessionState.SIGNED_IN) bookingModel.activate() else bookingModel.reset()
            }
            TableBookTheme {
                if (session == SessionState.SIGNED_IN) {
                    BookingScreen(bookingModel, operation.busy, operation.error != null, model::logout)
                } else {
                    AuthScreen(session, operation, model::signIn, model::logout, model::restore, model::clearError)
                }
            }
        }
    }
}
