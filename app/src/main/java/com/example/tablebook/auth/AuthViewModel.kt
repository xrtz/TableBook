package com.example.tablebook.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class AuthOperation(val busy: Boolean = false, val error: AuthFailure? = null)

internal class AuthViewModel(private val repository: SessionRepository) : ViewModel() {
    val session = repository.state
    private val mutableOperation = MutableStateFlow(AuthOperation())
    val operation = mutableOperation.asStateFlow()

    init {
        restore()
    }

    fun restore() = run { repository.restore() }

    fun signIn(email: String, password: String, register: Boolean) = run {
        repository.signIn(email, password, register)
    }

    fun logout() = run { repository.logout() }

    fun clearError() {
        if (!mutableOperation.value.busy) mutableOperation.value = AuthOperation()
    }

    private fun run(block: suspend () -> Unit) {
        if (mutableOperation.value.busy) return
        mutableOperation.value = AuthOperation(busy = true)
        viewModelScope.launch {
            try {
                block()
                mutableOperation.value = AuthOperation()
            } catch (error: CancellationException) {
                throw error
            } catch (error: AuthException) {
                mutableOperation.value = AuthOperation(error = error.failure)
            } catch (_: java.io.IOException) {
                mutableOperation.value = AuthOperation(error = AuthFailure.CONNECTION)
            } catch (_: java.security.GeneralSecurityException) {
                mutableOperation.value = AuthOperation(error = AuthFailure.CONNECTION)
            }
        }
    }
}
