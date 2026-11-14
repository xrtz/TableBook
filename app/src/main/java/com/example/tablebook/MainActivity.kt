package com.example.tablebook

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.tablebook.auth.AuthScreen
import com.example.tablebook.auth.AuthViewModel
import com.example.tablebook.auth.HttpAuthApi
import com.example.tablebook.auth.KeystoreSessionStorage
import com.example.tablebook.auth.SessionRepository
import com.example.tablebook.ui.theme.TableBookTheme

class TableBookApplication : Application() {
    internal val sessions by lazy {
        SessionRepository(HttpAuthApi(BuildConfig.API_URL), KeystoreSessionStorage(this))
    }
}

class MainActivity : ComponentActivity() {
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
            TableBookTheme {
                AuthScreen(session, operation, model::signIn, model::logout, model::restore, model::clearError)
            }
        }
    }
}
